/*
 *************************************************************************************
 * Copyright 2023 Normation SAS
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

package com.normation.rudder.ncf.migration

import better.files.File
import com.normation.cfclerk.domain.ReportingLogic
import com.normation.cfclerk.domain.ReportingLogic.WeightedReport
import com.normation.errors._
import com.normation.inventory.domain.Version
import com.normation.rudder.domain.logger.ApplicationLoggerPure
import com.normation.rudder.ncf._
import com.normation.rudder.ncf.yaml.YamlTechniqueSerializer
import com.normation.rudder.repository.xml.TechniqueFiles
import java.nio.charset.StandardCharsets
import zio._
import zio.json._
import zio.json.yaml._

/**
 * An object able to migrate a (set of) old techniques with a
 * JSON metadata file to one with Yaml.
 *
 * It understands the last available JSON file format in Rudder 7.3.
 */
object MigrateJsonTechniquesService {

  private object OldTechniqueSerializer {
    case class JRTechniqueElem(
        id:               String,
        component:        String,
        reportingLogic:   Option[JRReportingLogic],
        condition:        String,
        calls:            Option[List[JRTechniqueElem]],
        method:           Option[String],
        parameters:       Option[List[JRMethodCallValue]],
        disableReporting: Option[Boolean]
    )

    case class JREditorTechnique(
        name:        String,
        version:     String,
        id:          String,
        category:    String,
        calls:       List[JRTechniqueElem],
        description: String,
        parameter:   Seq[JRTechniqueParameter],
        resources:   Seq[JRTechniqueResource]
    )

    case class JRTechniqueParameter(
        id:          String,
        name:        String,
        description: String,
        mayBeEmpty:  Boolean
    )

    case class JRTechniqueResource(
        name:  String,
        state: String
    )

    case class JRMethodCallValue(
        name:  String,
        value: String
    )

    case class JRReportingLogic(
        `type`: String,
        value:  Option[String]
    )

    implicit val oldResourceJsonDecoder:   JsonDecoder[JRTechniqueResource]  = DeriveJsonDecoder.gen
    implicit val oldCallJsonDecoder:       JsonDecoder[JRMethodCallValue]    = DeriveJsonDecoder.gen
    implicit val oldPReportingJsonDecoder: JsonDecoder[JRReportingLogic]     = DeriveJsonDecoder.gen
    implicit val oldParamJsonDecoder:      JsonDecoder[JRTechniqueParameter] = DeriveJsonDecoder.gen
    implicit lazy val oldElemJsonDecoder:  JsonDecoder[JRTechniqueElem]      = DeriveJsonDecoder.gen
    implicit val oldJsonDecoder:           JsonDecoder[JREditorTechnique]    = DeriveJsonDecoder.gen
    implicit val j:                        JsonDecoder[EditorTechnique]      = JsonDecoder[JREditorTechnique].map(toEditorTechnique)

    private def toMethodElem(elem: JRTechniqueElem): MethodElem = {
      val reportingLogic = elem.reportingLogic
        .flatMap(r => ReportingLogic.parse(r.`type` ++ (r.value.map(v => s":$v")).getOrElse("")).toOption)
        .getOrElse(WeightedReport)
      elem.calls match {
        case Some(calls) =>
          MethodBlock(
            elem.id,
            elem.component,
            reportingLogic,
            elem.condition,
            calls.map(toMethodElem)
          )
        case None        =>
          MethodCall(
            BundleName(elem.method.getOrElse("")),
            elem.id,
            elem.parameters.getOrElse(Nil).map(p => (ParameterId(p.name), p.value)).toMap,
            elem.condition,
            elem.component,
            elem.disableReporting.getOrElse(false)
          )
      }
    }

    private def toEditorTechnique(oldTechnique: JREditorTechnique): EditorTechnique = {
      val (desc, doc) =
        if (oldTechnique.description.contains("\n")) ("", oldTechnique.description) else (oldTechnique.description, "")
      EditorTechnique(
        BundleName(oldTechnique.id),
        new Version(oldTechnique.version),
        oldTechnique.name,
        oldTechnique.category,
        oldTechnique.calls.map(toMethodElem),
        desc,
        doc,
        oldTechnique.parameter.map(p =>
          TechniqueParameter(ParameterId(p.id), canonify(p.name), p.name, p.description, p.mayBeEmpty)
        ),
        oldTechnique.resources.flatMap(s => ResourceFileState.parse(s.state).map(ResourceFile(s.name, _)).toSeq),
        Map(),
        None
      )
    }
  }

  /*
   * Read a JSON string in old Rudder 7.3 metadata format as an Editor technique
   */
  def fromOldJsonTechnique(json: String): PureResult[EditorTechnique] = {
    import OldTechniqueSerializer._
    json
      .fromJson[EditorTechnique]
      .left
      .map(err => Inconsistency(s"Error when trying to read a technique with Rudder 7.3 JSON metadata descriptor: ${err}"))
  }

  /*
   * transform a JSON string in old Rudder 7.3 format into a YAML string in 8.0 format
   */
  def toYaml(json: String): PureResult[String] = {
    import YamlTechniqueSerializer._

    for {
      technique <- fromOldJsonTechnique(json).left.map(err =>
                     Inconsistency(s"Error when trying to parse a technique.json in Rudder 7.3 format: ${err}")
                   )
      yaml      <- technique.toYaml().left.map(Inconsistency(_))
    } yield yaml
  }

  /*
   * The yaml name is the canonified version of the old json technique "name".
   * We must use the same algo that what was used in technique editor to display what value to use in code.
   * See
   *   - AgentValueParser.elm => canonifyHelper
   *   - EditorTechnique.scala => NcfId#canonify
   */
  def canonify(value: String): String = value.replaceAll("[^a-zA-Z0-9_]", "_")

  // migrate JSON technique at base path. If technique.json does not exists, does nothing (noop)
  def migrateJson(techniquePath: File): IOResult[Unit] = {
    val jsonFile = techniquePath / TechniqueFiles.json
    val yamlFile = techniquePath / TechniqueFiles.yaml

    ZIO
      .whenZIO(IOResult.attempt(jsonFile.exists)) {
        for {
          json <- IOResult.attempt(s"Error when reading file '${jsonFile.pathAsString}'") {
                    jsonFile.contentAsString(StandardCharsets.UTF_8)
                  }
          yaml <- toYaml(json).toIO
          _    <- IOResult.attempt(yamlFile.write(yaml))
          // on success, delete json file ; file generation will be done latter on on technique reload
          _    <- IOResult
                    .attempt(jsonFile.delete())
                    .chainError(s"Error when deleting migrated json metadata file: '${jsonFile.pathAsString}'")
          _    <- ApplicationLoggerPure.debug(s"Technique '${techniquePath}' migrated to YAML format")
        } yield ()
      }
      .unit
  }
}
