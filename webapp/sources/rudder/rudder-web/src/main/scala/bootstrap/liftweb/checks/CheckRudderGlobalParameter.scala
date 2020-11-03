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

package bootstrap.liftweb.checks

import bootstrap.liftweb.BootstrapChecks
import com.normation.box._
import zio._
import zio.syntax._
import better.files._
import bootstrap.liftweb.BootstrapLogger
import com.normation.errors.IOResult
import com.normation.errors.Inconsistency
import com.normation.eventlog.ModificationId
import com.normation.rudder.domain.parameters._
import com.normation.rudder.repository.RoParameterRepository
import com.normation.rudder.repository.WoParameterRepository
import com.normation.utils.StringUuidGenerator
import net.liftweb.json._
import com.normation.rudder.domain.eventlog._
import com.normation.rudder.domain.nodes.GenericProperty
import com.normation.rudder.domain.nodes.InheritMode
import com.normation.rudder.domain.nodes.PropertyProvider
import com.normation.zio.ZioRuntime


/**
 *
 * When the application is first initialized, we want to set the
 * content of configuration-repository file to a consistant state,
 * especially for directives/rules/groups, where we want to have
 * all system categories and entities saved (else, we are going
 * to have some surprise on the first import).
 *
 * So, if a full export wasn't done until know, just do one.
 *
 */
class CheckRudderGlobalParameter(
    roParamRepo: RoParameterRepository
  , woParamRepo: WoParameterRepository
  , uuidGen    : StringUuidGenerator
) extends BootstrapChecks {

  val resource = "rudder-system-global-parameter.conf"

  override val description = "Check that `rudder` global parameter matches default value"

  def toParams(value: JValue): IOResult[List[GlobalParameter]] = {
    implicit val formats = DefaultFormats
    value match {
      case JArray(list) => ZIO.foreach(list)(v => IOResult.effect(v.extract[JsonParam].toGlobalParam))
      case x            => Inconsistency(s"Resources `${resource}` must contain an array of json object with keys name, description, value`").fail
    }
  }


  def updateOne(modId: ModificationId, p: GlobalParameter): IOResult[Unit] = {
    for {
      saved <- roParamRepo.getGlobalParameter(p.name)
      _     <- saved match {
                case None =>
                  BootstrapLogger.info(s"Creating missing global parameter '${p.name}' with value: '${p.valueAsString}''") *>
                  woParamRepo.saveParameter(p, modId, RudderEventActor, Some(s"Creating global system parameter '${p.name}' to its default value"))
                case Some(s) if(p.value != s.value || p.provider != s.provider) =>
                  val provider = p.provider.getOrElse(PropertyProvider.systemPropertyProvider).value
                  BootstrapLogger.info(s"Reseting global parameter '${p.name}' from ${provider} provider to value: ${p.valueAsString}") *>
                  woParamRepo.updateParameter(p, modId, RudderEventActor, Some(s"Reseting global system parameter '${p.name}' to its default value"))
                case _ => UIO.unit
              }
    } yield ()
  }

  override def checks() : Unit = {

    val modId = ModificationId(uuidGen.newUuid)
    // get defaults global parameters. It should be an array of JValues
    val managedResource = IOManaged.make(parse(Resource.getAsString(resource)))( _ =>() )

    val check = managedResource.use(json =>
      for {
        params <- toParams(json)
        cheked <- ZIO.foreach(params)(p => updateOne(modId, p))
      } yield ()
    )

    ZioRuntime.runNow(check.catchAll(err => BootstrapLogger.error(s"Error when checking for default global system parameter: ${err.fullMsg}")))
  }
}

// lift json need that to be topevel
private[checks] final case class JsonParam(name: String, description: String, value: JValue, provider: Option[String]) {
  def toGlobalParam = {
    GlobalParameter(name, GenericProperty.fromJsonValue(value), InheritMode.Default, description, provider.map(PropertyProvider.apply))
  }
}
