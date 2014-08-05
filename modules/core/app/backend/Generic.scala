package backend

import models.json.{RestConvertable, RestResource, RestReadable}
import scala.concurrent.{ExecutionContext, Future}
import utils.{Page, PageParams}
import defines.{EntityType, ContentTypes}
import play.api.libs.json.JsObject

/**
 * @author Mike Bryant (http://github.com/mikesname)
 */
trait Generic {
  def get[MT](resource: RestResource[MT], id: String)(implicit apiUser: ApiUser, rd: RestReadable[MT], executionContext: ExecutionContext): Future[MT]

  def get[MT](id: String)(implicit apiUser: ApiUser, rs: RestResource[MT], rd: RestReadable[MT], executionContext: ExecutionContext): Future[MT]

  def getJson[MT](id: String)(implicit apiUser: ApiUser, rs: RestResource[MT], executionContext: ExecutionContext): Future[JsObject]

  def create[MT, T](item: T, accessors: Seq[String] = Nil, params: Map[String, Seq[String]] = Map.empty, logMsg: Option[String] = None)(implicit apiUser: ApiUser, rs: RestResource[MT], wrt: RestConvertable[T], rd: RestReadable[MT], executionContext: ExecutionContext): Future[MT]

  def createInContext[MT, T, TT](id: String, contentType: ContentTypes.Value, item: T, accessors: Seq[String] = Nil, params: Map[String, Seq[String]] = Map(), logMsg: Option[String] = None)(implicit apiUser: ApiUser, wrt: RestConvertable[T], rs: RestResource[MT], rd: RestReadable[TT], executionContext: ExecutionContext): Future[TT]

  def update[MT, T](id: String, item: T, logMsg: Option[String] = None)(implicit apiUser: ApiUser, wrt: RestConvertable[T], rs: RestResource[MT], rd: RestReadable[MT], executionContext: ExecutionContext): Future[MT]

  def patch[MT](id: String, data: JsObject, logMsg: Option[String] = None)(implicit apiUser: ApiUser, rs: RestResource[MT], rd: RestReadable[MT], executionContext: ExecutionContext): Future[MT]

  def delete[MT](entityType: EntityType.Value, id: String, logMsg: Option[String] = None)(implicit apiUser: ApiUser, executionContext: ExecutionContext): Future[Boolean]

  def delete[MT](id: String, logMsg: Option[String] = None)(implicit apiUser: ApiUser, rs: RestResource[MT], executionContext: ExecutionContext): Future[Boolean]

  def listJson[MT](params: PageParams = PageParams.empty)(implicit apiUser: ApiUser, rs: RestResource[MT], executionContext: ExecutionContext): Future[Page[JsObject]]

  def list[MT](params: PageParams = PageParams.empty)(implicit apiUser: ApiUser, rs: RestResource[MT], rd: RestReadable[MT], executionContext: ExecutionContext): Future[Page[MT]]

  def listChildren[MT, CMT](id: String, params: PageParams = PageParams.empty)(implicit apiUser: ApiUser, rs: RestResource[MT], rd: RestReadable[CMT], executionContext: ExecutionContext): Future[Page[CMT]]

  def count[MT](params: PageParams = PageParams.empty)(implicit apiUser: ApiUser, rs: RestResource[MT], executionContext: ExecutionContext): Future[Long]

  def countChildren[MT](id: String, params: PageParams = PageParams.empty)(implicit apiUser: ApiUser, rs: RestResource[MT], executionContext: ExecutionContext): Future[Long]
}
