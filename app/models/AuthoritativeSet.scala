package models

import base._

import models.base.Persistable
import defines.EntityType
import play.api.libs.json.{Format, Json}
import defines.EnumUtils.enumWrites
import models.json.{ClientConvertable, RestConvertable}


object AuthoritativeSetF {

  val NAME = "name"
  val DESCRIPTION = "description"

  lazy implicit val authoritativeSetFormat: Format[AuthoritativeSetF] = json.AuthoritativeSetFormat.restFormat

  implicit object Converter extends RestConvertable[AuthoritativeSetF] with ClientConvertable[AuthoritativeSetF] {
    lazy val restFormat = models.json.rest.authoritativeSetFormat
    lazy val clientFormat = models.json.client.authoritativeSetFormat
  }
}

case class AuthoritativeSetF(
  val id: Option[String],
  val identifier: String,
  val name: Option[String],
  val description: Option[String]
) extends Persistable {
  val isA = EntityType.AuthoritativeSet
}


object AuthoritativeSet {
  final val VOCAB_REL = "inCvoc"
  final val NT_REL = "narrower"
}

case class AuthoritativeSet(e: Entity)
  extends NamedEntity
  with AnnotatableEntity
  with Formable[AuthoritativeSetF] {

  lazy val formable: AuthoritativeSetF = Json.toJson(e).as[AuthoritativeSetF]
  lazy val formableOpt: Option[AuthoritativeSetF] = Json.toJson(e).asOpt[AuthoritativeSetF]
}
