/*
*************************************************************************************
* Copyright 2016 Normation SAS
*************************************************************************************
*
* This file is part of Rudder.
*
* Rudder is free software: you can redistribute it and/or modify
* it under the terms of the GNU General Public License as published by
* the Free Software Foundation, either version 3 of the License, or
* (at your option) any later version.
*
* In accordance with the terms of section 7 (7. Additional Terms.) of
* the GNU General Public License version 3, the copyright holders add
* the following Additional permissions:
* Notwithstanding to the terms of section 5 (5. Conveying Modified Source
* Versions) and 6 (6. Conveying Non-Source Forms.) of the GNU General
* Public License version 3, when you create a Related Module, this
* Related Module is not considered as a part of the work and may be
* distributed under the license agreement of your choice.
* A "Related Module" means a set of sources files including their
* documentation that, without modification of the Source Code, enables
* supplementary functions or services in addition to those offered by
* the Software.
*
* Rudder is distributed in the hope that it will be useful,
* but WITHOUT ANY WARRANTY; without even the implied warranty of
* MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
* GNU General Public License for more details.
*
* You should have received a copy of the GNU General Public License
* along with Rudder.  If not, see <http://www.gnu.org/licenses/>.

*
*************************************************************************************
*/

package com.normation.rudder.rest

import org.specs2.matcher.MatchResult
import net.liftweb.common.Full
import net.liftweb.http.LiftRules
import net.liftweb.http.LiftRulesMocker
import net.liftweb.http.Req
import net.liftweb.mocks.MockHttpServletRequest
import net.liftweb.mockweb.MockWeb
import com.normation.utils.StringUuidGenerator
import com.normation.cfclerk.services.{TechniquesLibraryUpdateNotification, TechniquesLibraryUpdateType, UpdateTechniqueLibrary}
import com.normation.eventlog.{EventActor, EventLog, ModificationId}
import net.liftweb.common.Box
import com.normation.cfclerk.domain.TechniqueName
import net.liftweb.http.LiftResponse
import net.liftweb.util.NamedPF
import net.liftweb.http.S
import net.liftweb.common.EmptyBox
import net.liftweb.json.JsonAST.JValue
import com.normation.rudder.UserService
import com.normation.rudder.User
import com.normation.rudder.AuthorizationType
import com.normation.rudder.RudderAccount
import com.normation.rudder.api.{ApiAuthorization => ApiAuthz}
import com.normation.rudder.batch.{AsyncDeploymentAgent, StartDeploymentMessage, UpdateDynamicGroups}
import com.normation.rudder.repository._
import com.normation.rudder.rest.v1.RestTechniqueReload
import com.normation.rudder.rest.v1.RestStatus
import com.normation.rudder.rest.lift.{LiftApiProcessingLogger, LiftHandler, SystemApiService11}
import com.normation.rudder.services.{ClearCacheService, DebugInfoScriptResult, DebugInfoService}
import com.normation.rudder.services.policies.TestNodeConfiguration
import com.normation.rudder.services.user.PersonIdentService
import org.eclipse.jgit.lib.PersonIdent
import org.joda.time.DateTime

import scalaz.zio._
import scalaz.zio.syntax._

/*
 * This file provides all the necessary plumbing to allow test REST API.
 *
 * Also responsible for setting up data and mock services.
 */
object RestTestSetUp {

  val uuidGen = new StringUuidGenerator() {
    override def newUuid: String = "Test Uuid"
  }

  implicit val userService = new UserService {
    val user = new User{
      val account = RudderAccount.User("test-user", "pass")
      def checkRights(auth : AuthorizationType) = true
      def getApiAuthz = ApiAuthz.allAuthz
    }
    val getCurrentUser = user
  }

  // Instantiate Service needed to feed System API constructor

  val fakeUpdatePTLibService = new UpdateTechniqueLibrary() {
      def update(modId: ModificationId, actor:EventActor, reason: Option[String])  = {
        Full(Map())
      }
      def registerCallback(callback:TechniquesLibraryUpdateNotification) : Unit = {}
    }

  val fakeUpdateDynamicGroups = new UpdateDynamicGroups(null, null, null, null, 0) {
    override def startManualUpdate: Unit = ()
  }

  val fakeAsyncDeployment =  new AsyncDeploymentAgent() {
    override def launchDeployment(dest: StartDeploymentMessage): Unit = ()
  }

  val reloadTechniques = new RestTechniqueReload(fakeUpdatePTLibService, uuidGen)
  val restExtractorService =
  RestExtractorService (
      null //roRuleRepository
    , null //roDirectiveRepository
    , null //roNodeGroupRepository
    , null //techniqueRepository
    , null //queryParser
    , null //userPropertyService
    , null //workflowService
  )

  val restDataSerializer = RestDataSerializerImpl(
      null //techniqueRepository
    , null //diffService
  )

  // TODO
  // all other apis

  class FakeClearCacheService extends ClearCacheService {
    override def action(actor: EventActor) = null
    override def clearNodeConfigurationCache(storeEvent: Boolean, actor: EventActor) = null
  }



  val fakeNotArchivedElements = NotArchivedElements(Seq[CategoryNotArchived](), Seq[ActiveTechniqueNotArchived](), Seq[DirectiveNotArchived]())
  val fakePersonIdent = new PersonIdent("test-user", "test.user@normation.com")
  val fakeGitCommitId = GitCommitId("6d6b2ceb46adeecd845ad0c0812fee07e2727104")
  val fakeGitArchiveId = GitArchiveId(GitPath("fake/git/path"), fakeGitCommitId, fakePersonIdent)


  class FakeItemArchiveManager extends ItemArchiveManager {

    override def exportAll(commiter: PersonIdent, modId: ModificationId, actor: EventActor, reason: Option[String], includeSystem: Boolean)
     = ZIO.succeed((fakeGitArchiveId, fakeNotArchivedElements))
    override def exportRules(commiter: PersonIdent, modId: ModificationId, actor: EventActor, reason: Option[String], includeSystem: Boolean)
     = ZIO.succeed(fakeGitArchiveId)
    override def exportTechniqueLibrary(commiter: PersonIdent, modId: ModificationId, actor: EventActor, reason: Option[String], includeSystem: Boolean)
     = ZIO.succeed((fakeGitArchiveId, fakeNotArchivedElements))
    override def exportGroupLibrary(commiter: PersonIdent, modId: ModificationId, actor: EventActor, reason: Option[String], includeSystem: Boolean)
     = ZIO.succeed(fakeGitArchiveId)
    override def exportParameters(commiter: PersonIdent, modId: ModificationId, actor: EventActor, reason: Option[String], includeSystem: Boolean)
     = ZIO.succeed(fakeGitArchiveId)


    override def importAll(archiveId: GitCommitId, commiter: PersonIdent, modId: ModificationId, actor: EventActor, reason: Option[String], includeSystem: Boolean)
     = ZIO.succeed(fakeGitCommitId)
    override def importRules(archiveId: GitCommitId, commiter: PersonIdent, modId: ModificationId, actor: EventActor, reason: Option[String], includeSystem: Boolean)
     = ZIO.succeed(fakeGitCommitId)
    override def importTechniqueLibrary(archiveId: GitCommitId, commiter: PersonIdent, modId: ModificationId, actor: EventActor, reason: Option[String], includeSystem: Boolean)
     = ZIO.succeed(fakeGitCommitId)
    override def importGroupLibrary(archiveId: GitCommitId, commiter: PersonIdent, modId: ModificationId, actor: EventActor, reason: Option[String], includeSystem: Boolean)
     = ZIO.succeed(fakeGitCommitId)
    override def importParameters(archiveId: GitCommitId, commiter: PersonIdent, modId: ModificationId, actor: EventActor, reason: Option[String], includeSystem: Boolean)
     = ZIO.succeed(fakeGitCommitId)
    override def rollback(archiveId: GitCommitId, commiter: PersonIdent, modId: ModificationId, actor: EventActor, reason: Option[String], rollbackedEvents: Seq[EventLog], target: EventLog, rollbackType: String, includeSystem: Boolean)
     = ZIO.succeed(fakeGitCommitId)

    /**
      * These methods are called by the Archive API to get the git archives.
      * The API then provides the logic to transform the Box[Map][DateTime, GitArchiveId] into JSON
      * Here, we want to make these methods returning fake archives for testing the API logic.
      */


    val fakeArchives = Map[DateTime, GitArchiveId](
            new DateTime(42) -> fakeGitArchiveId
    )

    override def getFullArchiveTags = ZIO.succeed(fakeArchives)

    override def getGroupLibraryTags = ZIO.succeed(fakeArchives)

    override def getTechniqueLibraryTags = ZIO.succeed(fakeArchives)

    override def getRulesTags = ZIO.succeed(fakeArchives)

    override def getParametersTags = ZIO.succeed(fakeArchives)
  }


  val fakeItemArchiveManager = new FakeItemArchiveManager
  val fakeClearCacheService = new FakeClearCacheService
  val fakePersonIndentService = new PersonIdentService {
    override def getPersonIdentOrDefault(username: String) = ZIO.succeed(fakePersonIdent)
  }
  lazy val apiAuthorizationLevelService = new DefaultApiAuthorizationLevel(LiftApiProcessingLogger)
  lazy val apiDispatcher = new RudderEndpointDispatcher(LiftApiProcessingLogger)



 val testNodeConfiguration = new TestNodeConfiguration()
 val fakeRepo = testNodeConfiguration.repo
 val fakeScriptLauncher = new DebugInfoService {
   override def launch() = Full(DebugInfoScriptResult("test", new Array[Byte](42)))
 }

  val apiService11 = new SystemApiService11(
        fakeUpdatePTLibService
      , fakeScriptLauncher
      , fakeClearCacheService
      , fakeAsyncDeployment
      , uuidGen
      , fakeUpdateDynamicGroups
      , fakeItemArchiveManager
      , fakePersonIndentService
      , fakeRepo
  )

  val systemApi = new com.normation.rudder.rest.lift.SystemApi(restExtractorService, apiService11, "5.0", "5.0.0", "some time")
  val authzToken = AuthzToken(EventActor("fakeToken"))
  val systemStatusPath = "api" + systemApi.Status.schema.path

  val ApiVersions = ApiVersion(11 , false) :: Nil

  val rudderApi = {
    //append to list all new format api to test it
    val modules = List(new com.normation.rudder.rest.lift.SystemApi(restExtractorService, apiService11, "5.0", "5.0.0", "some time"))
    val api = new LiftHandler(apiDispatcher, ApiVersions, new AclApiAuthorization(LiftApiProcessingLogger, userService, apiAuthorizationLevelService.aclEnabled _), None)
    modules.foreach { module =>
      api.addModules(module.getLiftEndpoints)
    }
    api
  }

  val liftRules = {
    val l = new LiftRules()
    l.statelessDispatch.append(RestStatus)
    l.statelessDispatch.append(reloadTechniques)
    l.statelessDispatch.append(rudderApi.getLiftRestApi())
    //TODO: add all other rest classes here
    l
  }

  /*
   * Correctly build and scope mutable things to use the request in a safe
   * way in the context of LiftRules.
   */
  def doReq[T](mockReq: MockHttpServletRequest)(tests: Req => MatchResult[T]) = {
    LiftRulesMocker.devTestLiftRulesInstance.doWith(liftRules) {
      MockWeb.useLiftRules.doWith(true) {
        MockWeb.testReq(mockReq)(tests)
      }
    }
  }

  /**
   * Execute the request and get the response.
   * The request must be a stateless one, else a failure
   * will follow.
   */
  def execRequestResponse[T](mockReq: MockHttpServletRequest)(tests: Box[LiftResponse] => MatchResult[T])= {
    doReq(mockReq){ req =>
      //the test logic is taken from LiftServlet#doServices.
      //perhaps we should call directly that methods, but it need
      //much more set-up, and I don't know for sure *how* to set-up things.
      NamedPF.applyBox(req, LiftRules.statelessDispatch.toList).map(_.apply() match {
        case Full(a) => Full(LiftRules.convertResponse((a, Nil, S.responseCookies, req)))
        case r => r
      }) match {
        case Full(x)      => tests(x)
        case eb: EmptyBox => tests(eb)
      }
    }
  }

  private[this] def mockRequest (path : String, method : String) = {
    val mockReq = new MockHttpServletRequest("http://localhost:8080")
    mockReq.method = method
    mockReq.path = path
    mockReq
  }
  def GET(path: String) = mockRequest(path,"GET")
  def POST(path: String) = mockRequest(path, "POST")
  def DELETE(path: String) = mockRequest(path,"DELETE")

  private[this] def mockJsonRequest (path : String, method : String, data : JValue) = {
    val mockReq = mockRequest(path,method)
    mockReq.body = data
    mockReq
  }

  def jsonPUT(path: String, json : JValue) = {
    mockJsonRequest(path,"PUT", json)
  }

  def jsonPOST(path: String, json : JValue) = {
    mockJsonRequest(path,"POST", json)
  }

  def testGET[T](path: String)(tests: Req => MatchResult[T]) = {
    doReq(GET(path))(tests)
  }

  def testDELETE[T](path: String)(tests: Req => MatchResult[T]) = {
    doReq(DELETE(path))(tests)
  }

  def testPUT[T](path: String, json : JValue)(tests: Req => MatchResult[T]) = {
    doReq(jsonPUT(path, json))(tests)
  }
  def testPOST[T](path: String, json : JValue)(tests: Req => MatchResult[T]) = {
    doReq(jsonPOST(path, json))(tests)
  }

  //This is a method to call when testing POST endpoint without data to pass.
  //Ex: reload techniques endpoints which reload all techniques.
  //It has to be a POST because it executes an action on the server side even if it doesn't need additional data to do so.
  def testEmptyPost[T](path: String)(tests: Req => MatchResult[T]): MatchResult[T] = {
    doReq(POST(path))(tests)
  }
}
