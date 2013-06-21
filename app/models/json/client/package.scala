package models.json

import models._
import play.api.libs.json.Json

/**
 * User: michaelb
 * 
 * Json formats for client. These are auto-generated by the framework
 * macro and simply match the case classes.
 */
package object client {

  implicit val accessPointFormat = Json.format[AccessPointF]
  implicit val addressFormat = Json.format[AddressF]
  implicit val annotationFormat = Json.format[AnnotationF]
  implicit val authoritativeSetFormat = Json.format[AuthoritativeSetF]
  implicit val conceptFormat = Json.format[ConceptF]
  implicit val conceptDescriptionFormat = Json.format[ConceptDescriptionF]
  implicit val countryFormat = Json.format[CountryF]
  implicit val datePeriodFormat = Json.format[DatePeriodF]
  implicit val documentaryUnitFormat = Json.format[DocumentaryUnitF]
  implicit val groupFormat = Json.format[GroupF]
  implicit val historicalAgentFormat = Json.format[HistoricalAgentF]

  private implicit val isaarDetailsFormat = Json.format[IsaarDetail]
  private implicit val isaarControlFormat = Json.format[IsaarControl]
  implicit val isaarFormat = Json.format[HistoricalAgentDescriptionF]

  private implicit val isadGContextFormat = Json.format[IsadGContext]
  private implicit val isadGContentFormat = Json.format[IsadGContent]
  private implicit val isadGConditionsFormat = Json.format[IsadGConditions]
  private implicit val isadGMaterialsFormat = Json.format[IsadGMaterials]
  private implicit val isadGControlFormat = Json.format[IsadGControl]
  implicit val isadGFormat = Json.format[DocumentaryUnitDescriptionF]

  private implicit val isdiahDetailsFormat = Json.format[IsdiahDetails]
  private implicit val isdiahAccessFormat = Json.format[IsdiahAccess]
  private implicit val isdiahServicesFormat = Json.format[IsdiahServices]
  private implicit val isdiahControlFormat = Json.format[IsdiahControl]
  implicit val isdiahFormat = Json.format[RepositoryDescriptionF]

  implicit val linkFormat = Json.format[LinkF]
  implicit val repositoryFormat = Json.format[RepositoryF]
  implicit val userProfileFormat = Json.format[UserProfileF]
  implicit val vocabularyFormat = Json.format[VocabularyF]

}
