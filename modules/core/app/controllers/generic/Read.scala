package controllers.generic

import backend.{BackendContentType, BackendReadable, BackendResource}
import defines.{ContentTypes, PermissionType}
import models._
import play.api.libs.concurrent.Execution.Implicits._
import play.api.mvc.{Result, _}
import utils.{Page, PageParams, RangePage, RangeParams}

import scala.concurrent.Future

/**
 * Controller trait which handles the listing and showing of Entities that
 * implement the AccessibleEntity trait.
 *
 * @tparam MT Meta-model
 */
trait Read[MT] extends Generic[MT] {

  case class ItemPermissionRequest[A](
    item: MT,
    userOpt: Option[UserProfile],
    request: Request[A]
  ) extends WrappedRequest[A](request)
  with WithOptionalUser

  case class ItemMetaRequest[A](
    item: MT,
    annotations: Page[Annotation],
    links: Page[Link],
    userOpt: Option[UserProfile],
    request: Request[A]
  ) extends WrappedRequest[A](request)
  with WithOptionalUser

  case class ItemPageRequest[A](
    page: Page[MT],
    params: PageParams,
    userOpt: Option[UserProfile],
    request: Request[A]
  ) extends WrappedRequest[A](request)
  with WithOptionalUser

  case class ItemHistoryRequest[A](
    item: MT,
    page: RangePage[SystemEvent],
    params: RangeParams,
    userOpt: Option[UserProfile],
    request: Request[A]
  ) extends WrappedRequest[A](request)
  with WithOptionalUser

  case class ItemVersionsRequest[A](
    item: MT,
    page: Page[Version],
    params: PageParams,
    userOpt: Option[UserProfile],
    request: Request[A]
  ) extends WrappedRequest[A](request)
  with WithOptionalUser

  private def WithPermissionFilter(perm: PermissionType.Value, contentType: ContentTypes.Value) = new ActionFilter[ItemPermissionRequest] {
    override protected def filter[A](request: ItemPermissionRequest[A]): Future[Option[Result]] = {
      if (request.userOpt.exists(_.hasPermission(contentType, perm)))  Future.successful(None)
      else authorizationFailed(request).map(r => Some(r))
    }
  }

  private def WithItemPermissionFilter(perm: PermissionType.Value)(implicit ct: BackendContentType[MT]) =
    WithPermissionFilter(perm, ct.contentType)

  protected def ItemPermissionAction(itemId: String)(implicit rd: BackendReadable[MT], ct: BackendContentType[MT]) =
    OptionalUserAction andThen new ActionTransformer[OptionalProfileRequest, ItemPermissionRequest] {
      def transform[A](input: OptionalProfileRequest[A]): Future[ItemPermissionRequest[A]] = {
        implicit val userOpt = input.userOpt
        input.userOpt.map { profile =>
          val itemF = backend.get[MT](itemId)
          val scopedPermsF = backend.getScopePermissions(profile.id, itemId)
          val permsF = backend.getItemPermissions(profile.id, ct.contentType, itemId)
          for {
            item <- itemF
            scopedPerms <- scopedPermsF
            perms <- permsF
            newProfile = profile.copy(itemPermissions = Some(perms), globalPermissions = Some(scopedPerms))
          } yield ItemPermissionRequest[A](item, Some(newProfile), input)
        }.getOrElse {
          for {
            item <- backend.get[MT](itemId)
          } yield ItemPermissionRequest[A](item, None, input)
        }
      }
    }

  protected def WithItemPermissionAction(itemId: String, perm: PermissionType.Value)(implicit rd: BackendReadable[MT], ct: BackendContentType[MT]) =
    ItemPermissionAction(itemId) andThen WithItemPermissionFilter(perm)

  protected def WithParentPermissionAction(itemId: String, perm: PermissionType.Value, contentType: ContentTypes.Value)(implicit rd: BackendReadable[MT], ct: BackendContentType[MT]) =
    ItemPermissionAction(itemId) andThen WithPermissionFilter(perm, contentType)

  protected def ItemMetaAction(itemId: String)(implicit rd: BackendReadable[MT], ct: BackendContentType[MT]) =
    ItemPermissionAction(itemId) andThen new ActionTransformer[ItemPermissionRequest, ItemMetaRequest] {
      def transform[A](request: ItemPermissionRequest[A]): Future[ItemMetaRequest[A]] = {
        implicit val userOpt = request.userOpt
        val annotationsF = backend.getAnnotationsForItem[Annotation](itemId)
        val linksF = backend.getLinksForItem[Link](itemId)
        for {
          annotations <- annotationsF
          links <- linksF
        } yield ItemMetaRequest[A](request.item, annotations, links, request.userOpt, request)
      }
    }

  protected def ItemPageAction(implicit rd: BackendReadable[MT], rs: BackendResource[MT]) =
    OptionalUserAction andThen new ActionTransformer[OptionalProfileRequest, ItemPageRequest] {
      def transform[A](input: OptionalProfileRequest[A]): Future[ItemPageRequest[A]] = {
        implicit val userOpt = input.userOpt
        val params = PageParams.fromRequest(input)
        for {
          page <- backend.list[MT](params)
        } yield ItemPageRequest[A](page, params, input.userOpt, input)
      }
    }

  protected def ItemHistoryAction(itemId: String)(implicit rd: BackendReadable[MT], ct: BackendContentType[MT]) =
    ItemPermissionAction(itemId) andThen new ActionTransformer[ItemPermissionRequest,ItemHistoryRequest] {
      override protected def transform[A](request: ItemPermissionRequest[A]): Future[ItemHistoryRequest[A]] = {
        implicit val req = request
        val params = RangeParams.fromRequest(request)
        val getF: Future[MT] = backend.get(itemId)
        val historyF: Future[RangePage[SystemEvent]] = backend.history[SystemEvent](itemId, params)
        for {
          item <- getF
          events <- historyF
        } yield ItemHistoryRequest(request.item, events, params, request.userOpt, request)
      }
    }

  protected def ItemVersionsAction(itemId: String)(implicit rd: BackendReadable[MT], ct: BackendContentType[MT]) =
    ItemPermissionAction(itemId) andThen new ActionTransformer[ItemPermissionRequest, ItemVersionsRequest] {
      override protected def transform[A](request: ItemPermissionRequest[A]): Future[ItemVersionsRequest[A]] = {
        implicit val req = request
        val params = PageParams.fromRequest(request)
        val getF: Future[MT] = backend.get(itemId)
        val versionsF: Future[Page[Version]] = backend.versions[Version](itemId, params)
        for {
          item <- getF
          versions <- versionsF
        } yield ItemVersionsRequest(request.item, versions, params, request.userOpt, request)
      }
    }


  @deprecated(message = "Use backend directly", since = "1.0.2")
  object getEntity {
    def async(id: String, user: Option[UserProfile])(f: MT => Future[Result])(
        implicit rd: BackendReadable[MT], rs: BackendResource[MT], userOpt: Option[UserProfile], request: RequestHeader): Future[Result] = {
      backend.get(id).flatMap { item =>
        f(item)
      }
    }

    def apply(id: String, user: Option[UserProfile])(f: MT => Result)(
        implicit rd: BackendReadable[MT], rs: BackendResource[MT], userOpt: Option[UserProfile], request: RequestHeader): Future[Result] = {
      async(id, user)(f.andThen(t => Future.successful(t)))
    }
  }

  @deprecated(message = "Use backend directly", since = "1.0.2")
  object getEntityT {
    def async[T](resource: BackendResource[T], id: String)(f: T => Future[Result])(
        implicit userOpt: Option[UserProfile], request: RequestHeader, rd: BackendReadable[T], rs: BackendResource[MT]): Future[Result] = {
      backend.get[T](resource, id).flatMap { item =>
        f(item)
      }
    }
    def apply[T](resource: BackendResource[T], id: String)(f: T => Result)(
      implicit rd: BackendReadable[T], rs: BackendResource[MT], userOpt: Option[UserProfile], request: RequestHeader): Future[Result] = {
      async(resource, id)(f.andThen(t => Future.successful(t)))
    }
  }

  @deprecated(message = "Use ItemMetaAction instead", since = "1.0.2")
  object getAction {
    def async(id: String)(f: MT => Page[Annotation] => Page[Link] => Option[UserProfile] => Request[AnyContent] => Future[Result])(
        implicit rd: BackendReadable[MT], ct: BackendContentType[MT]) = {
      itemPermissionAction.async[MT](id) { item => implicit maybeUser => implicit request =>
          // NB: Effectively disable paging here by using a high limit
        val annsReq = backend.getAnnotationsForItem[Annotation](id)
        val linkReq = backend.getLinksForItem[Link](id)
        for {
          anns <- annsReq
          links <- linkReq
          r <- f(item)(anns)(links)(maybeUser)(request)
        } yield r
      }
    }

    def apply(id: String)(f: MT => Page[Annotation] => Page[Link] => Option[UserProfile] => Request[AnyContent] => Result)(
      implicit rd: BackendReadable[MT], ct: BackendContentType[MT]) = {
      async(id)(f.andThen(_.andThen(_.andThen(_.andThen(_.andThen(t => Future.successful(t)))))))
    }
  }

  object getWithChildrenAction {
    def async[CT](id: String)(f: MT => Page[CT] => PageParams =>  Page[Annotation] => Page[Link] => Option[UserProfile] => Request[AnyContent] => Future[Result])(
          implicit rd: BackendReadable[MT], ct: BackendContentType[MT], crd: BackendReadable[CT]) = {
      ItemMetaAction(id).async { implicit request =>
        val params = PageParams.fromRequest(request)
        for {
          children <- backend.listChildren[MT,CT](id, params)
          r <- f(request.item)(children)(params)(request.annotations)(request.links)(request.userOpt)(request)
        } yield r
      }
    }

    def apply[CT](id: String)(f: MT => Page[CT] => PageParams =>  Page[Annotation] => Page[Link] => Option[UserProfile] => Request[AnyContent] => Result)(
      implicit rd: BackendReadable[MT], ct: BackendContentType[MT], crd: BackendReadable[CT]) = {
      async(id)(f.andThen(_.andThen(_.andThen(_.andThen(_.andThen(_.andThen(_.andThen(t => Future.successful(t)))))))))
    }
  }

  @deprecated(message = "Use ItemPageAction instead", since = "1.0.2")
  def pageAction(f: Page[MT] => PageParams => Option[UserProfile] => Request[AnyContent] => Result)(
      implicit rd: BackendReadable[MT], rs: BackendResource[MT]) = {
    OptionalUserAction.async { implicit request =>
      val params = PageParams.fromRequest(request)
      backend.list(params).map { page =>
        f(page)(params)(request.userOpt)(request)
      }
    }
  }

  @deprecated(message = "Use ItemHistoryAction instead", since = "1.0.2")
  def historyAction(id: String)(
      f: MT => RangePage[SystemEvent] => RangeParams => Option[UserProfile] => Request[AnyContent] => Result)(implicit rd: BackendReadable[MT], rs: BackendResource[MT]) = {
    OptionalUserAction.async { implicit request =>
      val params = RangeParams.fromRequest(request)
      val getF: Future[MT] = backend.get(id)
      val historyF: Future[RangePage[SystemEvent]] = backend.history[SystemEvent](id, params)
      for {
        item <- getF
        events <- historyF
      } yield f(item)(events)(params)(request.userOpt)(request)
    }
  }

  @deprecated(message = "Use ItemVersionsAction instead", since = "1.0.2")
  def versionsAction(id: String)(
    f: MT => Page[Version] => PageParams => Option[UserProfile] => Request[AnyContent] => Result)(implicit rd: BackendReadable[MT], rs: BackendResource[MT]) = {
    OptionalUserAction.async { implicit request =>
      val params = PageParams.fromRequest(request)
      val getF: Future[MT] = backend.get(id)
      val versionsF: Future[Page[Version]] = backend.versions[Version](id, params)
      for {
        item <- getF
        events <- versionsF
      } yield f(item)(events)(params)(request.userOpt)(request)
    }
  }
}
