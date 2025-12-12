/*
 *************************************************************************************
 * Copyright 2020 Normation SAS
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

package bootstrap.liftweb.checks.endconfig.consistency

import _root_.zio.*
import better.files.*
import bootstrap.liftweb.BootstrapChecks
import bootstrap.liftweb.BootstrapLogger
import com.normation.GitVersion
import com.normation.box.*
import com.normation.errors.*
import com.normation.eventlog.ModificationId
import com.normation.rudder.domain.eventlog.*
import com.normation.rudder.domain.properties.*
import com.normation.rudder.repository.RoParameterRepository
import com.normation.rudder.repository.WoParameterRepository
import com.normation.utils.StringUuidGenerator
import com.normation.zio.ZioRuntime
import zio.json.*
import zio.json.ast.*

/**
 * init or reset content of `rudder` global parameters to their default values.
 */
class CheckRudderGlobalProperties(
    roParamRepo: RoParameterRepository,
    woParamRepo: WoParameterRepository,
    uuidGen:     StringUuidGenerator
) extends BootstrapChecks {

  protected[consistency] val resource = "rudder-system-global-parameter.conf"

  override val description = "Check that `rudder` global properties matches default value"

  private def toProperties(value: Either[String, Json]): IOResult[Chunk[GlobalParameter]] = value
    .map(_.asArray)
    .toIO
    .notOptional(s"Resources `$resource` must contain an array of json object with keys name, description, value.")
    .flatMap(list => ZIO.foreach(list)(_.as[GlobalPropertiesJson].toIO.map(_.toGlobalParam)))

  private def updateOne(modId: ModificationId, p: GlobalParameter): IOResult[Unit] = {
    for {
      saved <- roParamRepo.getGlobalParameter(p.name)
      _     <- saved match {
                 case None                                                      =>
                   BootstrapLogger.info(s"Creating missing global properties '${p.name}' with value: '${p.valueAsString}''") *>
                   woParamRepo.saveParameter(
                     p,
                     modId,
                     RudderEventActor,
                     Some(s"Creating global system parameter '${p.name}' to its default value")
                   )
                 case Some(s) if p.value != s.value || p.provider != s.provider =>
                   val provider = p.provider.getOrElse(PropertyProvider.systemPropertyProvider).value
                   BootstrapLogger.info(
                     s"Resetting global properties '${p.name}' from $provider provider to value: ${p.valueAsString}"
                   ) *>
                   woParamRepo.updateParameter(
                     p,
                     modId,
                     RudderEventActor,
                     Some(s"Resetting global system properties '${p.name}' to its default value")
                   )
                 case _                                                         => ZIO.unit
               }
    } yield ()
  }

  override def checks(): Unit = {

    val modId           = ModificationId(uuidGen.newUuid)
    // get defaults global properties. It should be an array of zio.json.ast.Json
    val managedResource = IOManaged.make(Resource.getAsString(resource).fromJson[zio.json.ast.Json])(_ => ())

    val check = ZIO.scoped[Any](
      managedResource.flatMap(json => {
        for {
          properties <- toProperties(json)
          _          <- ZIO.foreach(properties)(p => updateOne(modId, p))
        } yield ()
      })
    )

    ZioRuntime.runNow(
      check.catchAll(err => BootstrapLogger.error(s"Error when checking for default global system properties: ${err.fullMsg}"))
    )
  }
}

final private[checks] case class GlobalPropertiesJson(
    name:        String,
    description: String,
    value:       zio.json.ast.Json,
    inheritMode: Option[String],
    provider:    Option[String],
    visibility:  Option[String]
) derives JsonDecoder {
  def toGlobalParam: GlobalParameter = {
    GlobalParameter(
      name,
      GitVersion.DEFAULT_REV,
      GenericProperty.fromZioJson(value),
      inheritMode.flatMap(InheritMode.parseString(_).toOption),
      description,
      provider.map(PropertyProvider.apply),
      visibility.flatMap(Visibility.withNameInsensitiveOption).getOrElse(Visibility.default),
      security = None // for backward compat
    )
  }
}
