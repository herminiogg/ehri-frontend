package controllers.base

import defines.ContentType
import models.PermissionSet
import models.Persistable
import models.base.Accessor
import play.api.libs.concurrent.execution.defaultContext
import play.api.mvc.Call
import play.api.mvc.RequestHeader

trait PermissionsController[F <: Persistable, T <: Accessor] extends EntityController[F, T] {

  import play.api.mvc.Call
  import play.api.mvc.RequestHeader

  val permsAction: String => Call
  val setPermsAction: String => Call
  type PermViewType = (Accessor, PermissionSet[Accessor], Call, Option[models.sql.User], RequestHeader) => play.api.templates.Html
  val permView: PermViewType

  def permissions(id: String) = userProfileAction { implicit maybeUser =>
    implicit request =>
      maybeUser.flatMap(_.profile).map { userProfile =>
        AsyncRest {
          for {
            itemOrErr <- rest.EntityDAO(entityType, Some(userProfile)).get(id)
            permsOrErr <- rest.PermissionDAO(userProfile).get(builder(itemOrErr.right.get))
          } yield {

            permsOrErr.right.map { perms =>
              Ok(permView(builder(itemOrErr.right.get), perms, setPermsAction(id), maybeUser, request))
            }
          }
        }
      }.getOrElse(Unauthorized(views.html.errors.permissionDenied()))
  }

  def permissionsPost(id: String) = userProfileAction { implicit maybeUser =>
    implicit request =>
      val data = request.body.asFormUrlEncoded.getOrElse(Map())
      val perms: Map[String, List[String]] = ContentType.values.toList.map { ct =>
        (ct.toString, data.get(ct.toString).map(_.toList).getOrElse(List()))
      }.toMap

      maybeUser.flatMap(_.profile).map { userProfile =>
        AsyncRest {
          for {
            itemOrErr <- rest.EntityDAO(entityType, Some(userProfile)).get(id)
            newpermsOrErr <- rest.PermissionDAO(userProfile).set(builder(itemOrErr.right.get), perms)
          } yield {
            newpermsOrErr.right.map { perms =>
              Redirect(permsAction(id))
            }
          }
        }
      }.getOrElse(Unauthorized(views.html.errors.permissionDenied()))
  }
}