package backend.rest

import scala.concurrent.{ExecutionContext, Future}
import acl._
import defines._
import models.PermissionGrant
import play.api.libs.json.Json
import play.api.Play.current
import play.api.cache.Cache
import utils.{Page, FutureCache, PageParams}
import backend.{BackendReadable, Permissions, EventHandler, ApiUser}


trait RestPermissions extends Permissions with RestDAO {

  val eventHandler: EventHandler

  import Constants._

  private def baseUrl = s"http://$host:$port/$mount"
  private def requestUrl = s"$baseUrl/permission"

  def listPermissionGrants(userId: String, params: PageParams)(implicit apiUser: ApiUser, rd: BackendReadable[PermissionGrant], executionContext: ExecutionContext): Future[Page[PermissionGrant]] =
    listWithUrl(enc(requestUrl, "list", userId), params)

  def listItemPermissionGrants(id: String, params: PageParams)(implicit apiUser: ApiUser, rd: BackendReadable[PermissionGrant], executionContext: ExecutionContext): Future[Page[PermissionGrant]] =
    listWithUrl(enc(requestUrl, "listForItem", id), params)

  def listScopePermissionGrants(id: String, params: PageParams)(implicit apiUser: ApiUser, rd: BackendReadable[PermissionGrant], executionContext: ExecutionContext): Future[Page[PermissionGrant]] =
    listWithUrl(enc(requestUrl, "listForScope", id), params)

  private def listWithUrl(url: String, params: PageParams)(implicit apiUser: ApiUser, rd: BackendReadable[PermissionGrant], executionContext: ExecutionContext): Future[Page[PermissionGrant]] = {
    userCall(url).withQueryString(params.queryParams: _*).get().map { response =>
      parsePage[PermissionGrant](response, context = Some(url))
    }
  }

  def getGlobalPermissions(userId: String)(implicit apiUser: ApiUser, executionContext: ExecutionContext): Future[GlobalPermissionSet] = {
    val url = enc(requestUrl, userId)
    FutureCache.getOrElse[GlobalPermissionSet](url, cacheTime) {
      userCall(url).get()
        .map(r => checkErrorAndParse[GlobalPermissionSet](r, context = Some(url)))
    }
  }

  def setGlobalPermissions(userId: String, data: Map[String, List[String]])(implicit apiUser: ApiUser, executionContext: ExecutionContext): Future[GlobalPermissionSet] = {
    val url = enc(requestUrl, userId)
    FutureCache.set(url, cacheTime) {
      userCall(url).post(Json.toJson(data))
        .map(r => checkErrorAndParse[GlobalPermissionSet](r, context = Some(url)))
    }
  }

  def getItemPermissions(userId: String, contentType: ContentTypes.Value, id: String)(implicit apiUser: ApiUser, executionContext: ExecutionContext): Future[ItemPermissionSet] = {
    val url = enc(requestUrl, userId, id)
    FutureCache.getOrElse[ItemPermissionSet](url, cacheTime) {
      userCall(url).get().map { response =>
        checkErrorAndParse(response, context = Some(url))(ItemPermissionSet.restReads(contentType))
      }
    }
  }

  def setItemPermissions(userId: String, contentType: ContentTypes.Value, id: String, data: List[String])(implicit apiUser: ApiUser, executionContext: ExecutionContext): Future[ItemPermissionSet] = {
    val url = enc(requestUrl, userId, id)
    FutureCache.set(url, cacheTime) {
      userCall(url).post(Json.toJson(data)).map { response =>
        checkErrorAndParse(response, context = Some(url))(ItemPermissionSet.restReads(contentType))
      }
    }
  }

  def getScopePermissions(userId: String, id: String)(implicit apiUser: ApiUser, executionContext: ExecutionContext): Future[GlobalPermissionSet] = {
    val url = enc(requestUrl, userId, "scope", id)
    FutureCache.getOrElse[GlobalPermissionSet](url, cacheTime) {
      userCall(url).get()
        .map(r => checkErrorAndParse[GlobalPermissionSet](r, context = Some(url)))
    }
  }

  def setScopePermissions(userId: String, id: String, data: Map[String,List[String]])(implicit apiUser: ApiUser, executionContext: ExecutionContext): Future[GlobalPermissionSet] = {
    val url = enc(requestUrl, userId, "scope", id)
    FutureCache.set(url, cacheTime) {
      userCall(url).post(Json.toJson(data))
        .map(r => checkErrorAndParse[GlobalPermissionSet](r, context = Some(url)))
    }
  }

  def addGroup(groupId: String, userId: String)(implicit apiUser: ApiUser, executionContext: ExecutionContext): Future[Boolean] = {
    userCall(enc(baseUrl, EntityType.Group, groupId, userId)).post(Map[String, List[String]]()).map { response =>
      checkError(response)
      Cache.remove(userId)
      Cache.remove(groupId)
      Cache.remove(enc(requestUrl, userId))
      true
    }
  }

  def removeGroup(groupId: String, userId: String)(implicit apiUser: ApiUser, executionContext: ExecutionContext): Future[Boolean] = {
    userCall(enc(baseUrl, EntityType.Group, groupId, userId)).delete().map { response =>
      checkError(response)
      Cache.remove(userId)
      Cache.remove(groupId)
      Cache.remove(enc(requestUrl, userId))
      true
    }
  }
}


case class PermissionDAO(eventHandler: EventHandler) extends RestPermissions
