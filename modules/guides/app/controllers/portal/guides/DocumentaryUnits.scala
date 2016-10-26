package controllers.portal.guides

import auth.AccountManager
import backend.DataApi
import javax.inject._

import auth.handler.AuthHandler
import backend.rest.cypher.Cypher
import controllers.generic.SearchType
import controllers.portal.FacetConfig
import controllers.portal.base.{Generic, PortalController}
import models.{Guide, GuidePage, _}
import play.api.cache.CacheApi
import play.api.i18n.MessagesApi
import utils.search.{SearchConstants, SearchEngine, SearchItemResolver}
import views.MarkdownRenderer

import scala.concurrent.ExecutionContext


@Singleton
case class DocumentaryUnits @Inject()(
  implicit config: play.api.Configuration,
  cache: CacheApi,
  globalConfig: global.GlobalConfig,
  authHandler: AuthHandler,
  executionContext: ExecutionContext,
  searchEngine: SearchEngine,
  searchResolver: SearchItemResolver,
  dataApi: DataApi,
  accounts: AccountManager,
  pageRelocator: utils.MovedPageLookup,
  messagesApi: MessagesApi,
  markdown: MarkdownRenderer,
  guides: GuideService,
  cypher: Cypher
) extends PortalController
  with Generic[DocumentaryUnit]
  with SearchType[DocumentaryUnit]
  with FacetConfig {

  def browse(path: String, id: String) = GetItemAction(id).async { implicit request =>
    futureItemOr404 {
      guides.find(path, activeOnly = true).map { guide =>
        val filterKey = if (!hasActiveQuery(request)) SearchConstants.PARENT_ID
          else SearchConstants.ANCESTOR_IDS

        findType[DocumentaryUnit](
          filters = Map(filterKey -> request.item.id),
          facetBuilder = docSearchFacets
        ).map { result =>
          Ok(views.html.guides.documentaryUnit(
            guide,
            GuidePage.document(Some(request.item.toStringLang)),
            guides.findPages(guide),
            request.item,
            result,
            controllers.portal.guides.routes.DocumentaryUnits.browse(path, id),
            request.annotations,
            request.links,
            request.watched
          ))
        }
      }
    }
  }
}