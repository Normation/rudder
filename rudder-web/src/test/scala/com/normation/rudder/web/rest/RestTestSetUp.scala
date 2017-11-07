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

package com.normation.rudder.web.rest

import org.specs2.matcher.MatchResult

import net.liftweb.common.Full
import net.liftweb.http.LiftRules
import net.liftweb.http.LiftRulesMocker
import net.liftweb.http.Req
import net.liftweb.mocks.MockHttpServletRequest
import net.liftweb.mockweb.MockWeb
import com.normation.utils.StringUuidGeneratorImpl
import com.normation.cfclerk.services.UpdateTechniqueLibrary
import com.normation.eventlog.EventActor
import com.normation.eventlog.ModificationId
import com.normation.cfclerk.services.TechniquesLibraryUpdateNotification
import net.liftweb.common.Box
import com.normation.cfclerk.services.TechniquesLibraryUpdateType
import com.normation.cfclerk.domain.TechniqueName
import net.liftweb.http.LiftResponse
import net.liftweb.util.NamedPF
import net.liftweb.http.S
import net.liftweb.common.EmptyBox
import net.liftweb.json.JsonAST.JValue

/*
 * This file provides all the necessary plumbing to allow test REST API.
 *
 * Also responsible for setting up datas and mock services.
 */
object RestTestSetUp {

  val uuidGen = new StringUuidGeneratorImpl()
  val reloadTechniques = new RestTechniqueReload(new UpdateTechniqueLibrary() {
    def update(modId: ModificationId, actor:EventActor, reason: Option[String]) : Box[Map[TechniqueName, TechniquesLibraryUpdateType]] = {
      Full(Map())
    }
    def registerCallback(callback:TechniquesLibraryUpdateNotification) : Unit = {}
  }, uuidGen)

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

  val api = APIDispatcher(restExtractorService)
  // TODO
  // api.addEndpoints(Map((ApiVersion(42,false)-> List(....))))

  val liftRules = {
    val l = new LiftRules()
    l.statelessDispatch.append(RestStatus)
    l.statelessDispatch.append(reloadTechniques)
    l.statelessDispatch.append(api)
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
}
