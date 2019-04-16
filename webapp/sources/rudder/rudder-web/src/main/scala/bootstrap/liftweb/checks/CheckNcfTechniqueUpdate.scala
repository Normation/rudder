/*
*************************************************************************************
* Copyright 2017 Normation SAS
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

package bootstrap.liftweb
package checks

import net.liftweb.common._
import java.io.File
import com.normation.eventlog.ModificationId
import com.normation.utils.StringUuidGenerator
import com.normation.rudder.rest.RestExtractorService
import com.normation.rudder.ncf.TechniqueWriter
import scalaj.http.Http
import monix.execution.Scheduler.{ global => scheduler }
import scala.concurrent.duration._
import com.normation.eventlog.EventActor
import com.normation.rudder.api.ApiAccount
import net.liftweb.json.JsonAST.JObject
import net.liftweb.json.JsonAST.JArray

import com.normation.box._

sealed trait NcfTechniqueUpgradeError {
  def msg : String
  def exception : Option[Throwable]

}
object NcfTechniqueUpgradeError {
  case class NcfApiAuthFailed   (msg : String, exception : Option[Throwable]) extends NcfTechniqueUpgradeError
  case class NcfApiRequestFailed(msg : String, exception : Option[Throwable]) extends NcfTechniqueUpgradeError
  case class JsonExtractionError(msg : String, exception : Option[Throwable]) extends NcfTechniqueUpgradeError
  case class WriteTechniqueError(msg : String, exception : Option[Throwable]) extends NcfTechniqueUpgradeError
  case class FlagFileError      (msg : String, exception : Option[Throwable]) extends NcfTechniqueUpgradeError

  type Result[T] = Either[NcfTechniqueUpgradeError, T]
  def tryo[T]( f : => T, errorMessage : String, catcher : (String,Option[Throwable]) => NcfTechniqueUpgradeError) : Result[T] = {
    try {
      Right(f)
    } catch {
      case e : Throwable => Left(catcher(errorMessage,Some(e)))
    }

  }

}

/**
 * Check at webapp startup if ncf Technique needs to be rewritten by Rudder
 * Controlled by presence of flag file /opt/rudder/etc/force_ncf_technique_update
 */
class CheckNcfTechniqueUpdate(
    restExtractor  : RestExtractorService
  , techniqueWrite : TechniqueWriter
  , systemApiToken : ApiAccount
  , uuidGen        : StringUuidGenerator
) extends BootstrapChecks {

  override val description = "Regenerate all ncf techniques"

  override def checks() : Unit = {

    def request(path : String) = {
      Http(s"http://localhost/ncf/api/${path}").
        param("path", "/var/rudder/configuration-repository/ncf/").
        header("X-API-TOKEN", systemApiToken.token.value)
    }

    val authRequest = request("auth")
    val methodsRequest = request("generic_methods")
    val techniquesRequest = request("techniques")

    val ncfTechniqueUpdateFlag = "/opt/rudder/etc/force_ncf_technique_update"
    val file =  new File(ncfTechniqueUpdateFlag)

    def updateNcfTechniques : Unit = {
      import net.liftweb.json.parse
      import com.normation.utils.Control.sequence
      import NcfTechniqueUpgradeError._
      ( for {
        authResponse       <- tryo ( authRequest.asString , "An error occurred while fetching generic methods from ncf API", NcfApiAuthFailed)
        authResult         <- if(authResponse.isSuccess) {
                                Right(authResponse.body)
                              } else {
                                Left(NcfApiAuthFailed(s"Failure during authentication to ncf api: code ${authResponse.code}: ${authResponse.body}", None))
                              }
        // First get generic methods
        methodsResponse    <- tryo ( methodsRequest.asString , "An error occurred while fetching generic methods from ncf API", NcfApiRequestFailed)
        methodsResult      <- if(methodsResponse.isSuccess) {
                                Right(methodsResponse.body)
                              } else {
                                Left(NcfApiRequestFailed(s"Failure getting generic methods from ncf api: code ${methodsResponse.code}: ${methodsResponse.body}", None))
                              }
        methods            <- (parse(methodsResult) \ "data" \ "generic_methods" match {
                                case JObject(fields) =>
                                  restExtractor.extractGenericMethod(JArray(fields.map(_.value))).map(_.map(m => (m.id,m)).toMap) match {
                                    case Full(m) => Right(m)
                                    case eb : EmptyBox =>
                                      val fail = eb ?~!  s"An Error occured while extracting data from generic methods ncf API"
                                      Left(JsonExtractionError(fail.messageChain, None))
                                  }
                                case a => Left(JsonExtractionError(s"Could not extract methods from ncf api, expecting an object got: ${a}", None))
                              })

        // Then take care of techniques
        techniquesResponse <- tryo ( techniquesRequest.asString, "An error occurred while fetching techniques from ncf API", NcfApiRequestFailed)
        techniquesResult   <- if( techniquesResponse.isSuccess) {
                                Right(techniquesResponse.body)
                              } else {
                                Left(NcfApiRequestFailed(s"Failure getting techniques from ncf api: code ${techniquesResponse.code}: ${techniquesResponse.body}", None))
                              }

        techniques         <- (parse(techniquesResult) \ "data" \ "techniques" match {
                                case JObject(fields) =>
                                  sequence(fields.map(_.value))(restExtractor.extractNcfTechnique(_, methods)) match {
                                    case Full(m) => Right(m)
                                    case eb : EmptyBox =>
                                      val fail = eb ?~!  s"An Error occured while extracting data from techniques ncf API"
                                      Left(JsonExtractionError(fail.messageChain, None))

                                  }
                                case a => Left(JsonExtractionError(s"Could not extract techniques from ncf api, expecting an object got: ${a}", None))
                              })

        // Actually write techniques
        techniqueUpdated   <- (sequence(techniques)(
                                techniqueWrite.writeAll(_, methods, ModificationId(uuidGen.newUuid), EventActor(systemApiToken.name.value)).toBox
                              )) match {
                                case Full(m) => Right(m)
                                case eb : EmptyBox =>
                                  val fail = eb ?~! "An error occured while writing ncf Techniques"
                                  Left(WriteTechniqueError(fail.messageChain, None))
                              }
        flagDeleted        <- tryo (
                                  file.delete()
                                , s"An error occured while deleting file ${ncfTechniqueUpdateFlag} after techniques were written, you may need to delete it manually"
                                , FlagFileError
                              )
      } yield {

      } ) match {
        case Right(_) =>
          BootraspLogger.logEffect.info("All ncf techniques were updated")
        case Left(NcfApiAuthFailed(msg,e)) =>
          BootraspLogger.logEffect.warn(s"Could not authenticate in ncf API, maybe it was not initialized yet, retrying in 5 seconds")
          scheduler.scheduleOnce(5.seconds)(updateNcfTechniques)
        case Left(FlagFileError(_,_)) =>
          BootraspLogger.logEffect.warn(s"All ncf techniques were updated, but we could not delete flag file ${ncfTechniqueUpdateFlag}, please delete it manually")
        case Left(e : NcfTechniqueUpgradeError) =>
          BootraspLogger.logEffect.error(s"An error occured while updating ncf techniques: ${e.msg}")
      }
    }

    try {
      if (file.exists) {
        scheduler.scheduleOnce(10.seconds)(updateNcfTechniques)
      } else {
        BootraspLogger.logEffect.info(s"Flag file '${ncfTechniqueUpdateFlag}' does not exist, do not regenerate ncf Techniques")
      }
    } catch {
      // Exception while checking the flag existence
      case e : Exception =>
        BootraspLogger.logEffect.error(s"An error occurred while accessing flag file '${ncfTechniqueUpdateFlag}', cause is: ${e.getMessage}")
    }

  }

}
