package controllers.institutions

import actors.ingest
import akka.actor.Props
import akka.http.scaladsl.model.Uri
import akka.stream.Materializer
import akka.stream.scaladsl.{Sink, Source}
import akka.util.ByteString
import controllers.AppComponents
import controllers.base.AdminController
import controllers.generic._
import defines.FileStage
import models._
import play.api.cache.AsyncCacheApi
import play.api.http.{ContentTypes, HeaderNames}
import play.api.libs.json.{Format, Json, Writes}
import play.api.libs.streams.Accumulator
import play.api.mvc._
import services.data.{ApiUser, DataHelpers}
import services.datasets.ImportDatasetService
import services.ingest.IngestService.{IngestData, IngestDataType, IngestJob}
import services.ingest._
import services.storage.FileStorage

import java.util.UUID
import javax.inject._
import scala.concurrent.Future
import scala.concurrent.duration._


case class FileToUpload(name: String, `type`: String, size: Long)

object FileToUpload {
  implicit val _json: Format[FileToUpload] = Json.format[FileToUpload]
}

case class IngestPayload(
  logMessage: String,
  allowUpdates: Boolean = false,
  tolerant: Boolean = false,
  commit: Boolean = false,
  properties: Option[String] = None,
  files: Seq[String] = Seq.empty
)

object IngestPayload {
  implicit val _json: Format[IngestPayload] = Json.format[IngestPayload]
}

case class ValidationResult(key: String, eTag: Option[String], errors: Seq[XmlValidationError])

object ValidationResult {
  implicit val _json: Writes[ValidationResult] = Json.writes[ValidationResult]
}

@Singleton
case class ImportFiles @Inject()(
  controllerComponents: ControllerComponents,
  appComponents: AppComponents,
  dataHelpers: DataHelpers,
  @Named("dam") storage: FileStorage,
  eadValidator: EadValidator,
  ingestService: IngestService,
  importLogService: ImportLogService,
  asyncCache: AsyncCacheApi,
  datasets: ImportDatasetService,
)(implicit mat: Materializer) extends AdminController with StorageHelpers with Update[Repository] {

  private val repositoryDataRoutes = controllers.institutions.routes.ImportFiles


  def manager(id: String): Action[AnyContent] = EditAction(id).async { implicit request =>
    storage.isVersioned.map { versioned =>
      Ok(views.html.admin.repository.datamanager(request.item, versioned))
    }
  }

  def toggleVersioning(id: String, enabled: Boolean): Action[AnyContent] = AdminAction.async { implicit request =>
    storage.setVersioned(enabled).map { _ =>
      Redirect(repositoryDataRoutes.manager(id))
    }
  }

  def listFiles(id: String, ds: String, stage: FileStage.Value, path: Option[String], from: Option[String]): Action[AnyContent] = EditAction(id).async { implicit request =>
    storage.listFiles(prefix = Some(prefix(id, ds, stage) + path.getOrElse("")),
      from.map(key => s"${prefix(id, ds, stage)}$key"), max = 500).map { list =>
      Ok(Json.toJson(list.copy(files = list.files.map(f => f.copy(key = f.key.replace(prefix(id, ds, stage), ""))))))
    }
  }

  def countFiles(id: String, ds: String, stage: FileStage.Value, path: Option[String]): Action[AnyContent] = EditAction(id).async { implicit request =>
    import scala.concurrent.duration._

    val pathPrefix: String = prefix(id, ds, stage) + path.getOrElse("")
    asyncCache.getOrElseUpdate(s"bucket:count:${storage.name}/$pathPrefix", 1.minute) {
      storage.count(Some(prefix(id, ds, stage) + path.getOrElse(""))).map { count =>
        Json.obj("path" -> pathPrefix, "count" -> count)
      }
    }.map(Ok(_))
  }

  def fileUrls(id: String, ds: String, stage: FileStage.Value): Action[Seq[String]] = EditAction(id).apply(parse.json[Seq[String]]) { implicit request =>
    val keys = request.body.map(path => s"${prefix(id, ds, stage)}$path")
    val result = keys.map(key => key.replace(prefix(id, ds, stage), "") -> storage.uri(key)).toMap
    Ok(Json.toJson(result))
  }

  private def streamToStorage(id: String, ds: String, stage: FileStage.Value, fileName: String): BodyParser[Source[ByteString, _]] = BodyParser { implicit r =>
    Accumulator.source[ByteString]
      .mapFuture(src => storage.putBytes(s"${prefix(id, ds, stage)}$fileName", src, r.contentType))
      .map(f => Source.single(ByteString(Json.prettyPrint(Json.obj("url" -> f.toString)))))
      .map(Right.apply)
  }

  def uploadStream(id: String, ds: String, stage: FileStage.Value, fileName: String): Action[Source[ByteString, _]] =
    EditAction(id).apply(streamToStorage(id, ds, stage, fileName)) { implicit request =>
      // Upload via the server. Normally you'd PUT direct from the client
      // to the storage, but this is useful for testing
      Ok.chunked(request.body).as(ContentTypes.JSON)
    }

  def download(id: String, ds: String, stage: FileStage.Value, fileName: String, versionId: Option[String]): Action[AnyContent] = EditAction(id).async { implicit req =>
    storage.get(s"${prefix(id, ds, stage)}$fileName", versionId = versionId).map {
      case Some((meta, bytes)) =>
        Ok.chunked(bytes)
          .as(meta.contentType.getOrElse(ContentTypes.BINARY))
          .withHeaders(meta.eTag.toSeq.map(tag => HeaderNames.ETAG -> tag): _*)
          .withHeaders(HeaderNames.CONTENT_DISPOSITION -> s"attachment;filename=$fileName")
      case _ => NotFound
    }
  }

  def info(id: String, ds: String, stage: FileStage.Value, fileName: String, versionId: Option[String]): Action[AnyContent] = EditAction(id).async { implicit req =>
    val pre = prefix(id, ds, stage)
    val path = pre + fileName
    for {
      Some((meta, _)) <- storage.info(path, versionId)
      versions <- storage.listVersions(path)
    } yield Ok(Json.obj(
      "meta" -> meta.copy(key = meta.key.replace(pre, "")),
      "presignedUrl" -> storage.uri(path, 2.hours, versionId = versionId, contentType = meta.contentType),
      "versions" -> versions.files.map(v => v.copy(key = v.key.replace(pre, "")))
    ))
  }

  def uploadHandle(id: String, ds: String, stage: FileStage.Value): Action[FileToUpload] = EditAction(id).apply(parse.json[FileToUpload]) { implicit request =>
    val path = s"${prefix(id, ds, stage)}${request.body.name}"
    val url = storage.uri(path, contentType = Some(request.body.`type`))
    Ok(Json.obj("presignedUrl" -> url))
  }

  def deleteFiles(id: String, ds: String, stage: FileStage.Value): Action[Seq[String]] = EditAction(id).async(parse.json[Seq[String]]) { implicit request =>
    def deleteBatch(batch: Seq[String]): Future[Int] = {
      storage.deleteFiles(batch: _*).map(_.size)
    }

    val pre = prefix(id, ds, stage)
    val src: Source[String, _] = if (request.body.isEmpty)
      storage.streamFiles(Some(pre)).map(f => f.key)
    else Source(request.body.map(p => pre + p).toList)

    val r = src
      .grouped(200)
      .mapAsync(2)(deleteBatch)
      .runWith(Sink.fold(0)(_ + _))

    r.map ( num => Ok(Json.obj("deleted" -> num)))
  }

  def validateFiles(id: String, ds: String, stage: FileStage.Value): Action[Map[String, String]] = Action.async(parse.json[Map[String, String]]) { implicit request =>
    // Input is a map of eTag -> key
    // Convert the map to a sequence and reverse key/value
    val pre = prefix(id, ds, stage)
    val src: Source[(String, Option[String]), _] = if (request.body.isEmpty) storage
        .streamFiles(Some(pre))
        .map(f => f.key -> f.eTag)
    else Source(request.body.map{ case (t, k) => (pre + k) -> Some(t)}.toList)

    val results: Future[Seq[ValidationResult]] = src
      .mapAsync(3) { case (key, tag) =>
        val url = storage.uri(key).toString
        eadValidator.validateEad(Uri(url)).map(errs =>
          ValidationResult(key.replace(pre, ""), tag, errs))
      }
      .runWith(Sink.seq)

    results.map(out => Ok(Json.toJson(out))).recover {
      case e => BadRequest(Json.obj("error" -> e.getMessage))
    }
  }

  def ingestFiles(id: String, ds: String, stage: FileStage.Value): Action[IngestPayload] = EditAction(id).async(parse.json[IngestPayload]) { implicit request =>
    import scala.concurrent.duration._

    val urlsF = getUrlMap(request.body, prefix(id, ds, stage))
    for (urls <- urlsF; dataset <- datasets.get(id, ds)) yield {

      val task = IngestParams(
        scopeType = defines.ContentTypes.Repository,
        scope = id,
        data = UrlMapPayload(urls),
        allowUpdate = request.body.allowUpdates,
        log = request.body.logMessage,
        tolerant = request.body.tolerant,
        commit = request.body.commit,
        properties = request.body.properties.map(ref =>
          PropertiesHandle(storage.uri(s"${prefix(id, ds, FileStage.Config)}$ref", 2.hours).toString))
          .getOrElse(PropertiesHandle.empty),
        fonds = dataset.fonds
      )
      // Tag this task with a unique ID...
      val jobId = UUID.randomUUID().toString

      // Type is json, since it's a mapping of key -> URL
      val contentType = play.api.http.ContentTypes.JSON

      // Use the sync endpoint if this fonds is synced and we a) are doing the complete
      // set and b) have a fonds.
      // Don't allow sync on the repository scope, because it is too dangerous.
      val idt = if (dataset.sync && request.body.files.isEmpty && dataset.fonds.isDefined)
        IngestDataType.EadSync else IngestDataType.Ead
      println(s"Type: $idt: ${dataset.sync}, ${request.body.files.isEmpty}, ${dataset.fonds}")

      val ingestTask = IngestData(task, idt, contentType, implicitly[ApiUser], instance)
      val job = IngestJob(jobId, ingestTask)
      val onDone: (IngestJob, ImportLog) => Future[Unit] = (job, log) =>
        if (job.data.params.commit && log.event.isDefined) importLogService.save(id, ds, job.data, log)
        else Future.successful(())

      mat.system.actorOf(Props(ingest.DataImporterManager(job, ingestService, onDone)), jobId)

      Ok(Json.obj(
        "url" -> controllers.admin.routes.Tasks.taskMonitorWS(jobId).webSocketURL(conf.https),
        "jobId" -> jobId
      ))
    }
  }

  private def getUrlMap(data: IngestPayload, prefix: String): Future[Map[String, java.net.URI]] = {
    // NB: Not doing this with regular Future.successful so as to
    // limit parallelism to the specified amount
    val keys = if (data.files.isEmpty) storage.streamFiles(Some(prefix)).map(_.key)
    else Source(data.files.map(prefix + _).toList)

    keys
      .mapAsync(4)(path => storage.info(path).map(info => path -> info))
      .collect { case (path, Some((meta, _))) =>
        val id = meta.versionId.map(vid => s"$path?versionId=$vid").getOrElse(path)
        id -> storage.uri(meta.key, duration = 2.hours, versionId = meta.versionId)
      }
      .runFold(Map.empty[String, java.net.URI])(_ + _)
  }
}
