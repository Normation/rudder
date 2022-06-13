/*
*************************************************************************************
* Copyright 2022 Normation SAS
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

package com.normation.rudder.rest.lift

import com.normation.rudder.api.ApiVersion
import com.normation.rudder.domain.appconfig.FeatureSwitch
import com.normation.rudder.domain.logger.ApplicationLogger
import com.normation.rudder.git.ZipUtils
import com.normation.rudder.git.ZipUtils.Zippable
import com.normation.rudder.rest.ApiPath
import com.normation.rudder.rest.AuthzToken
import com.normation.rudder.rest.RudderJsonResponse
import com.normation.rudder.rest.RudderJsonResponse.ResponseSchema
import com.normation.rudder.rest.implicits._
import com.normation.rudder.rest.lift.DummyImportAnswer._
import com.normation.rudder.rest.{ArchiveApi => API}

import net.liftweb.http.LiftResponse
import net.liftweb.http.OutputStreamResponse
import net.liftweb.http.Req

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets

import zio._
import zio.syntax._
import com.normation.errors._
import com.normation.zio._

/*
 * Machinery to enable/disable the API given the value of the feature switch in config service.
 * If disabled, always return an error with the info about how to enable it.
 */
final case class FeatureSwitch0[A <: LiftApiModule0](enable: A, disable: A)(featureSwitchState: IOResult[FeatureSwitch]) extends LiftApiModule0 {
  override val schema = enable.schema
  override def process0(version: ApiVersion, path: ApiPath, req: Req, params: DefaultParams, authzToken: AuthzToken): LiftResponse = {
    featureSwitchState.either.runNow match {
      case Left(err) =>
        ApplicationLogger.error(err.fullMsg)
        RudderJsonResponse.internalError(ResponseSchema.fromSchema(schema), err.fullMsg)(params.prettify).toResponse
      case Right(FeatureSwitch.Disabled) =>
        disable.process0(version, path, req, params, authzToken)
      case Right(FeatureSwitch.Enabled) =>
        enable.process0(version, path, req, params, authzToken)
    }
  }
}

class ArchiveApi(
  featureSwitchState: IOResult[FeatureSwitch]
) extends LiftApiModuleProvider[API] {

  def schemas = API

  def getLiftEndpoints(): List[LiftApiModule] = {
    API.endpoints.map(e => e match {
      case API.Import       => FeatureSwitch0(Import, ImportDisabled)(featureSwitchState)
      case API.ExportSimple => FeatureSwitch0(ExportSimple, ExportSimpleDisabled)(featureSwitchState)
    })
  }

  /*
   * Default answer to use when the feature is disabled
   */
  trait ApiDisabled extends LiftApiModule0 {
    override def process0(version: ApiVersion, path: ApiPath, req: Req, params: DefaultParams, authzToken: AuthzToken): LiftResponse = {
      RudderJsonResponse.internalError(
          ResponseSchema.fromSchema(schema)
        , """This API is disabled. It is in beta version and no compatibility is ensured. You can enable it with """ +
          """the setting `rudder_featureSwitch_archiveApi` in settings API set to `{"value":"enabled"}`"""
      )(params.prettify).toResponse
    }
  }

  object ExportSimpleDisabled extends LiftApiModule0 with ApiDisabled { val schema = API.ExportSimple }

  /*
   * This API does not returns a standard JSON response, it returns a ZIP archive.
   */
  object ExportSimple extends LiftApiModule0 {
    val schema = API.ExportSimple
    def process0(version: ApiVersion, path: ApiPath, req: Req, params: DefaultParams, authzToken: AuthzToken): LiftResponse = {
      def getContent(use: InputStream => IOResult[Any]): IOResult[Any] = {
         ZIO.bracket(IOResult.effect(new ByteArrayInputStream("Hello world!".getBytes(StandardCharsets.UTF_8))))(is => effectUioUnit(is.close)) { is =>
            use(is)
         }
      }
      val name = "archive"
      val archive = Chunk(
          Zippable(name, None)
        , Zippable(s"${name}/placeholder", Some(getContent _))
        , Zippable(s"${name}/placeholder2", Some(getContent _))
      )

      //do zip
      val send = (os: OutputStream) => ZipUtils.zip(os, archive).runNow

      val headers = List(
          ("Pragma", "public")
        , ("Expires", "0")
        , ("Cache-Control", "must-revalidate, post-check=0, pre-check=0")
        , ("Cache-Control", "public")
        , ("Content-Description", "File Transfer")
        , ("Content-type", "application/octet-stream")
        , ("Content-Disposition", s"""attachment; filename="${name}.zip"""")
        , ("Content-Transfer-Encoding", "binary")
      )

      new OutputStreamResponse(send, -1, headers, Nil, 200)

    }
  }

  object ImportDisabled extends LiftApiModule0 with ApiDisabled { override val schema = API.Import }

  object Import extends LiftApiModule0 {
    val schema = API.Import
    def process0(version: ApiVersion, path: ApiPath, req: Req, params: DefaultParams, authzToken: AuthzToken): LiftResponse = {
      val res: IOResult[JRArchiveImported] = JRArchiveImported(true).succeed
      res.toLiftResponseOne(params, schema, _ => None)
    }
  }
}

/*
 * A dummy object waiting for implementation for import
 */
object DummyImportAnswer {

  import zio.json._

  case class JRArchiveImported(success: Boolean)

  implicit lazy val encodeJRArchiveImported: JsonEncoder[JRArchiveImported] = DeriveJsonEncoder.gen

}
