package models

import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

import models.base._
import defines.{ContentTypes, EntityType, EventType}
import models.json._
import play.api.libs.json._
import play.api.libs.functional.syntax._
import play.api.i18n.Messages
import play.api.libs.json.JsObject
import services.data.{ContentType, Readable}

object SystemEventF {

  final val TIMESTAMP = "timestamp"
  final val LOG_MESSAGE = "logMessage"
  final val EVENT_TYPE = "eventType"
  final val FORMAT = "yyyy-MM-dd'T'HH:mm:ss.SSZ"

  import SystemEventF.{EVENT_TYPE => EVENT_PROP}
  import Entity._

  implicit val systemEventFormat: Format[SystemEventF] = (
    (__ \ TYPE).formatIfEquals(EntityType.SystemEvent) and
    (__ \ ID).formatNullable[String] and
    // NB: Default Joda DateTime format is less flexible then
    // using the constructor from a string
    (__ \ DATA \ TIMESTAMP).format[String].inmap[ZonedDateTime](
      s => ZonedDateTime.parse(s),
      dt => DateTimeFormatter.ISO_DATE_TIME.format(dt)
    ) and
    (__ \ DATA \ LOG_MESSAGE).formatNullable[String] and
    (__ \ DATA \ EVENT_PROP).formatNullable[EventType.Value]
  )(SystemEventF.apply, unlift(SystemEventF.unapply))

  implicit object Converter extends Readable[SystemEventF] {
    val restReads: Format[SystemEventF] = systemEventFormat
  }
}

case class SystemEventF(
  isA: EntityType.Value = EntityType.SystemEvent,
  id: Option[String],
  timestamp: ZonedDateTime,
  logMessage: Option[String] = None,
  eventType: Option[EventType.Value] = None
) extends Model {
  lazy val datetime: String = DateTimeFormatter.ISO_DATE_TIME.format(timestamp)
}

object SystemEvent {
  import play.api.libs.functional.syntax._
  import SystemEventF._
  import Entity._
  import eu.ehri.project.definitions.Ontology._

  implicit val metaReads: Reads[SystemEvent] = (
    __.read[SystemEventF] and
    (__ \ RELATIONSHIPS \ EVENT_HAS_SCOPE).lazyReadHeadNullable(AnyModel.Converter.restReads) and
    (__ \ RELATIONSHIPS \ EVENT_HAS_FIRST_SUBJECT).lazyReadHeadNullable(AnyModel.Converter.restReads) and
    (__ \ RELATIONSHIPS \ EVENT_HAS_ACTIONER).lazyReadHeadNullable(Accessor.Converter.restReads) and
    (__ \ RELATIONSHIPS \ VERSION_HAS_EVENT).readHeadNullable(Version.Converter.restReads) and
    (__ \ META).readWithDefault(Json.obj())
  )(SystemEvent.apply _)

  implicit object SystemEventResource extends ContentType[SystemEvent]  {
    val entityType = EntityType.SystemEvent
    val contentType = ContentTypes.SystemEvent
    val restReads: Reads[SystemEvent] = metaReads
  }
}

case class SystemEvent(
  model: SystemEventF,
  scope: Option[AnyModel] = None,
  firstSubject: Option[AnyModel] = None,
  actioner: Option[Accessor] = None,
  version: Option[Version] = None,
  meta: JsObject = JsObject(Seq())
) extends AnyModel
  with MetaModel
  with Holder[AnyModel] {

  type T = SystemEventF

  def time: String = DateTimeFormatter.ISO_INSTANT.format(model.timestamp)

  /**
   * If the event is of a certain type (link, annotate) the effective
   * subject is the scope in which the link or annotation is made,
   * rather than the link/annotation itself.
   */
  def effectiveSubject: Option[AnyModel] =
    if (model.eventType.contains(EventType.link) || model.eventType.contains(EventType.annotation))
      scope else firstSubject

  import EventType._

  /**
   * For display purposes, collapse certain specific event types to a more
   * general "effective" type: create, modify,delete, etc.
   */
  def effectiveType: Option[EventType.Value] = model.eventType.map {
    case `createDependent`|`modifyDependent`|`deleteDependent` => modification
    case `setGlobalPermissions`|`setItemPermissions`|`setVisibility`|`addGroup`|`removeGroup` => modification
    case et => et
  }

  override def toStringLang(implicit messages: play.api.i18n.Messages): String =
    Messages("systemEvent." + model.eventType.map(_.toString).getOrElse("unknown"))(messages)
}

