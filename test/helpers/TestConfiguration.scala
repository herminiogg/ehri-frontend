package helpers

import akka.stream.Materializer
import auth.oauth2.{MockOAuth2Flow, OAuth2Flow}
import auth._
import auth.handler.cookie.CookieIdContainer
import auth.handler.{AuthHandler, AuthIdContainer}
import backend._
import backend.aws.MockFileStorage
import backend.feedback.MockFeedbackService
import backend.helpdesk.MockHelpdeskService
import backend.rest.{IdSearchResolver, RestApi}
import controllers.base.SessionPreferences
import models.{Account, CypherQuery, Feedback}
import org.specs2.execute.{AsResult, Result}
import play.api.Application
import play.api.http.Writeable
import play.api.inject.guice.GuiceApplicationLoader
import play.api.libs.json.{Json, Writes}
import play.api.libs.mailer.{Email, MailerClient}
import play.api.test.Helpers._
import play.api.test._
import utils.{MockBufferedMailer, MockMovedPageLookup, MovedPageLookup}
import utils.search.{MockSearchIndexMediator, _}

import scala.concurrent.{Await, ExecutionContext, Future}


/**
 * Mixin trait that provides some handy methods to test actions that
 * have authorisation, such as fakeApplication and fakeLoggedInHtmlRequest.
 */
trait TestConfiguration {

  this: PlayRunners with RouteInvokers =>

  import helpers.RestApiRunner._

  // Stateful buffers for capturing stuff like feedback, search
  // parameters, and reset tokens. These persist across tests in
  // a very unclean way but are useful for determining the last-used
  // whatsit etc...
  protected val feedbackBuffer = collection.mutable.HashMap.empty[Int,Feedback]
  protected val cypherQueryBuffer = collection.mutable.HashMap.empty[Int,CypherQuery]
  protected val helpdeskBuffer = collection.mutable.HashMap.empty[Int, Seq[(String, Double)]]
  protected val mailBuffer = collection.mutable.ListBuffer.empty[Email]
  protected val storedFileBuffer = collection.mutable.ListBuffer.empty[java.net.URI]
  protected val searchParamBuffer = collection.mutable.ListBuffer.empty[ParamLog]
  protected val indexEventBuffer = collection.mutable.ListBuffer.empty[String]

  private def mockMailer: MailerClient = MockBufferedMailer(mailBuffer)
  private def mockIndexer: SearchIndexMediator = MockSearchIndexMediator(indexEventBuffer)
  private def mockFeedback: FeedbackService = MockFeedbackService(feedbackBuffer)
  private def mockHelpdesk: HelpdeskService = MockHelpdeskService(helpdeskBuffer)
  private def mockCypherQueries: CypherQueryService = MockCypherQueryService(cypherQueryBuffer)

  // NB: The mutable state for the user DAO is still stored globally
  // in the mocks package.
  protected def mockAccounts: AccountManager = MockAccountManager()
  private def mockOAuth2Flow: OAuth2Flow = MockOAuth2Flow()
  private def mockRelocator: MovedPageLookup = MockMovedPageLookup()
  private def mockFileStorage: FileStorage = MockFileStorage(storedFileBuffer)
  private def mockHtmlPages: HtmlPages = MockHtmlPages()

  // More or less the same as run config but synchronous (so
  // we can validate the actions)
  // Note: this is defined as an implicit object here so it
  // can be used by the DAO classes directly.
  protected val testEventHandler = new EventHandler {
    def handleCreate(id: String) = mockIndexer.handle.indexId(id)
    def handleUpdate(id: String) = mockIndexer.handle.indexId(id)
    def handleDelete(id: String) = mockIndexer.handle.clearId(id)
  }

  protected val searchLogger = new SearchLogger {
    override def log(params: ParamLog): Unit = searchParamBuffer += params
  }

  import play.api.inject.bind

  protected val appBuilder = new play.api.inject.guice.GuiceApplicationBuilder()
    .overrides(
      // since we run some concurrent requests as the same user its
      // important not to use the CacheIdContainer, since each new
      // login evicts the last.
      bind[AuthIdContainer].to(classOf[CookieIdContainer]),
      bind[MailerClient].toInstance(mockMailer),
      bind[OAuth2Flow].toInstance(mockOAuth2Flow),
      bind[FileStorage].toInstance(mockFileStorage),
      bind[MovedPageLookup].toInstance(mockRelocator),
      bind[AccountManager].toInstance(mockAccounts),
      bind[SearchEngine].to[MockSearchEngine],
      bind[HelpdeskService].toInstance(mockHelpdesk),
      bind[FeedbackService].toInstance(mockFeedback),
      bind[CypherQueryService].toInstance(mockCypherQueries),
      bind[EventHandler].toInstance(testEventHandler),
      bind[DataApi].to[RestApi],
      bind[SearchIndexMediator].toInstance(mockIndexer),
      bind[HtmlPages].toInstance(mockHtmlPages),
      // NB: Graph IDs are not stable during testing due to
      // DB churn, so using the String ID resolver rather than
      // the more efficient GID one used in production
      bind[SearchItemResolver].to[IdSearchResolver]
    ).bindings(
      bind[SearchLogger].toInstance(searchLogger)
    )

  // Might want to mock the dataApi at at some point!
  protected def dataApi(implicit app: play.api.Application, apiUser: ApiUser, executionContext: ExecutionContext): DataApiHandle =
    app.injector.instanceOf[DataApi].withContext(apiUser)(executionContext)

  protected val CSRF_TOKEN_NAME = "csrfToken"
  protected val CSRF_HEADER_NAME = "Csrf-Token"
  protected val CSRF_HEADER_NOCHECK = "nocheck"
  protected val fakeCsrfString = "fake-csrf-token"
  protected val testPassword = "testpass"

  /**
   * Override this value for configuration common to
   * an entire class of specs.
   */
  protected def getConfig = Map.empty[String,Any]

  /**
   * Test running Fake Application. We have general all-test configuration,
   * handled in `config`, and per-test configuration (`specificConfig`) that
   * will be merged.
   * @param specificConfig A map of config values for this test
   */
  protected abstract class ITestApp(val specificConfig: Map[String,Any] = Map.empty) extends WithApplicationLoader(
    new GuiceApplicationLoader(appBuilder.configure(backendConfig ++ getConfig ++ specificConfig))) {
    implicit def implicitMaterializer: Materializer = app.materializer
    implicit def implicitExecContext: ExecutionContext = app.injector.instanceOf[ExecutionContext]
  }

  /**
   * Test running Fake Application. We have general all-test configuration,
   * handled in `config`, and per-test configuration (`specificConfig`) that
   * will be merged.
   * @param specificConfig A map of config values for this test
   */
  protected abstract class DBTestApp(resource: String, specificConfig: Map[String,Any] = Map.empty) extends WithSqlFile(
    resource)(new GuiceApplicationLoader(appBuilder.configure(backendConfig ++ getConfig ++ specificConfig)))

  /**
   * Run a spec after loading the given resource name as SQL fixtures.
   */
  protected abstract class WithSqlFile(val resource: String)(implicit appLoader: play.api.ApplicationLoader)
    extends WithApplicationLoader(appLoader) {
    override def around[T: AsResult](t: => T): Result = {
      running(app) {
        withDatabaseFixture(resource) { implicit db =>
          AsResult.effectively(t)
        }
      }
    }
  }

  /**
   * Convenience extensions for the FakeRequest object.
   */
  protected implicit class FakeRequestExtensions[A](fr: FakeRequest[A]) {

    import scala.concurrent.duration._
    def withLoggedIn(implicit app: Application): String => FakeRequest[A] = { id =>
      val handler: AuthHandler = app.injector.instanceOf[AuthHandler]
      val token = Await.result(handler.newSession(id), 10.seconds)
      fr.withHeaders("PLAY2_AUTH_TEST_TOKEN" -> token)
    }

    /**
     * Set the request to be authenticated for the given user.
     */
    def withUser(user: Account)(implicit app: play.api.Application): FakeRequest[A] = {
      fr.withLoggedIn(app)(user.id)
    }

    /**
     * Add a dummy CSRF to the fake request.
     */
    def withCsrf: FakeRequest[A] = if (fr.method == POST)
      fr.withSession(CSRF_TOKEN_NAME -> fakeCsrfString)
        .withHeaders(CSRF_HEADER_NAME -> CSRF_HEADER_NOCHECK) else fr

    /**
     * Add a serialized preferences object to the fake request's session.
     */
    def withPreferences[T: Writes](p: T): FakeRequest[A] =
      fr.withSession(SessionPreferences.DEFAULT_STORE_KEY -> Json.stringify(Json.toJson(p)(implicitly[Writes[T]])))

    /**
     * Set the accepting header to the given mime-types.
     */
    def accepting(m: String*): FakeRequest[A] = m.foldLeft(fr) { (c, m) =>
      c.withHeaders(ACCEPT -> m)
    }

    /**
     * Call the request.
     */
    def call()(implicit app: play.api.Application, w: Writeable[A]): Future[play.api.mvc.Result] =
      route(app, fr).getOrElse(sys.error(s"Unexpected null route for ${fr.uri} (${fr.method})"))

    /**
     * Call the request with the given body.
     */
    def callWith[T](body: T)(implicit app: play.api.Application, w: Writeable[T]): Future[play.api.mvc.Result] =
      route(app, fr, body).getOrElse(sys.error(s"Unexpected null route for ${fr.uri} (${fr.method})"))
  }
}