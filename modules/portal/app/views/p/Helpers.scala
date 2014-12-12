package views.p

import models.{UserProfile, Annotation}
import java.net.{MalformedURLException, URL}
import models.base.AnyModel
import defines.PermissionType
import play.api.mvc.Call
import controllers.portal.ReversePortal

/**
 * Portal view helpers.
 *
 * @author Mike Bryant (http://github.com/mikesname)
 */
object Helpers {

  /**
   * Sort a set of annotations into three types.
   * @param annotations A list of annotations
   * @param userOpt An optional user context
   * @return A tuple of annotation sequences: the current user's, promoted, and other
   */
  def sortAnnotations(annotations: Seq[models.Annotation])(
      implicit userOpt: Option[UserProfile]): (Seq[Annotation], Seq[Annotation], Seq[Annotation]) = {
    val (mine,others) = annotations.filterNot(_.isPromoted).partition(_.isOwnedBy(userOpt))
    val promoted = annotations.filter(_.isPromoted)
    (mine, promoted, others)
  }

  def normalizeUrl(s: String): String = {
    try {
      new URL(s).toString
    } catch {
      case e: MalformedURLException if e.getMessage.startsWith("no protocol") => "http://" + s
      case _: MalformedURLException => s
    }
  }

  def isAnnotatable(item: AnyModel, userOpt: Option[models.UserProfile]) = userOpt.exists { user =>
    item.contentType.exists {
      ct => user.hasPermission(ct, PermissionType.Annotate)
    }
  }

  def linkTo(item: AnyModel): Call = {
    import defines.EntityType
    val portalRoutes: ReversePortal = controllers.portal.routes.Portal
    item.isA match {
      case EntityType.Country => portalRoutes.browseCountry(item.id)
      case EntityType.Concept => portalRoutes.browseConcept(item.id)
      case EntityType.DocumentaryUnit => portalRoutes.browseDocument(item.id)
      case EntityType.Repository => portalRoutes.browseRepository(item.id)
      case EntityType.HistoricalAgent => portalRoutes.browseHistoricalAgent(item.id)
      case EntityType.UserProfile => controllers.portal.social.routes.Social.browseUser(item.id)
      case EntityType.Group => portalRoutes.browseGroup(item.id)
      case EntityType.Link => portalRoutes.browseLink(item.id)
      case EntityType.Annotation => portalRoutes.browseAnnotation(item.id)
      case EntityType.Vocabulary => portalRoutes.browseVocabulary(item.id)
      case _ => {
        play.api.Logger.logger.error(s"Link to unexpected item: ${item.toStringLang} ${item.isA}")
        Call("GET", "#")
      }
    }
  }

  /**
   * Fetch a gravitar URL for the user, defaulting to the stock picture.
   */
  def gravitar(img: Option[String]): String =
    img.map(_.replaceFirst("https?://", "//"))
      .getOrElse(controllers.portal.routes.Assets.at("img/default-gravitar.png").url)
}
