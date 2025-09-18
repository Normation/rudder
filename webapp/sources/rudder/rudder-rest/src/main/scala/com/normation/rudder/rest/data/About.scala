/*
 *************************************************************************************
 * Copyright 2025 Normation SAS
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

package com.normation.rudder.rest.data

import com.normation.inventory.domain.OsDetails
import com.normation.plugins.Plugin
import com.normation.plugins.PluginLicense
import com.normation.rudder.metrics.*
import com.normation.utils.Version
import io.scalaland.chimney.*
import io.scalaland.chimney.syntax.*
import java.time.ZonedDateTime
import zio.json.*
import zio.json.internal.Write

/*
 * Translation from system info to the Json About structure
 */
case class RelayJson(uuid: String, hostname: String, managedNodes: Int)
object RelayJson {
  implicit val encoderRelayJson: JsonEncoder[RelayJson] = DeriveJsonEncoder.gen
}

case class OsJson(name: String, version: String)

object OsJson {
  implicit val transformOsDetails: Transformer[OsDetails, OsJson] = {
    Transformer
      .define[OsDetails, OsJson]
      .withFieldComputed(_.name, _.fullName)
      .withFieldComputed(_.version, _.version.value)
      .buildTransformer
  }

  implicit val encoderOsJson: JsonEncoder[OsJson] = DeriveJsonEncoder.gen
}

case class JvmJson(version: String, cmd: String)
object JvmJson     {
  implicit val transformJvmInfo: Transformer[JvmInfo, JvmJson] =
    Transformer.derive[JvmInfo, JvmJson]

  implicit val encoderJvmJson: JsonEncoder[JvmJson] = DeriveJsonEncoder.gen
}
case class NodesJson(total: Int, audit: Int, enforce: Int, mixed: Int, enabled: Int, disabled: Int)
object NodesJson   {
  implicit val transformNodesInfo: Transformer[FrequentNodeMetrics, NodesJson] = {
    Transformer
      .define[FrequentNodeMetrics, NodesJson]
      .withFieldRenamed(_.accepted, _.total)
      .withFieldRenamed(_.modeAudit, _.audit)
      .withFieldRenamed(_.modeEnforce, _.enforce)
      .withFieldRenamed(_.modeMixed, _.mixed)
      .withFieldComputed(_.enabled, x => x.accepted - x.disabled)
      .buildTransformer
  }

  implicit val encoderNodesJson: JsonEncoder[NodesJson] = DeriveJsonEncoder.gen
}
case class LicenseJson(
    licensee:           String,
    startDate:          ZonedDateTime,
    endDate:            ZonedDateTime,
    allowedNodesNumber: Option[Int],
    supportedVersions:  String
)
object LicenseJson {
  implicit val transformPluginLicense: Transformer[PluginLicense, LicenseJson] = {
    Transformer
      .define[PluginLicense, LicenseJson]
      .withFieldRenamed(_.maxNodes, _.allowedNodesNumber)
      .withFieldComputed(_.supportedVersions, x => s"[${x.minVersion.value},${x.maxVersion.value}]")
      .buildTransformer
  }

  implicit val encoderLicenseJson: JsonEncoder[LicenseJson] = DeriveJsonEncoder.gen
}

case class PluginJson(id: String, name: String, version: String, abiVersion: String, license: Option[LicenseJson])
object PluginJson {
  // Used in both abiVersion and version string
  private object privImplicits {
    implicit val transformVersion: Transformer[Version, String] = _.toVersionString
  }
  import privImplicits.*

  implicit val transformPlugin: Transformer[Plugin, PluginJson] = {
    Transformer
      .define[Plugin, PluginJson]
      .withFieldRenamed(_.abiVersion, _.abiVersion)
      .withFieldRenamed(_.pluginVersion, _.version)
      .buildTransformer
  }

  implicit val encoderPluginJson: JsonEncoder[PluginJson] = DeriveJsonEncoder.gen
}

case class SystemJson(os: OsJson, jvm: JvmJson)

object SystemJson             {
  implicit val transformSystemInfo: Transformer[SystemInfo, SystemJson] = {
    Transformer
      .define[SystemInfo, SystemJson]
      .withFieldComputed(_.os, _.public.os.transformInto[OsJson])
      .withFieldComputed(_.jvm, _.public.jvm.transformInto[JvmJson])
      .buildTransformer
  }

  implicit val encoderSystemJson: JsonEncoder[SystemJson] = DeriveJsonEncoder.gen
}

case class AboutRudderInfoJsonV20(
    @jsonField("major-version") majorVersion: String,
    @jsonField("full-version") version:       String,
    @jsonField("build-time") buildTime:       String
)
object AboutRudderInfoJsonV20 {
  implicit val transformSystemInfo: Transformer[SystemInfo, AboutRudderInfoJsonV20] = {
    Transformer
      .define[SystemInfo, AboutRudderInfoJsonV20]
      .withFieldComputed(_.majorVersion, _.public.rudderMajorVersion)
      .withFieldComputed(_.version, _.public.rudderVersion)
      .withFieldComputed(_.buildTime, _.public.buildTime)
      .buildTransformer
  }

  implicit val encoderAboutRudderInfoJsonV20: JsonEncoder[AboutRudderInfoJsonV20] = DeriveJsonEncoder.gen
}

case class RudderJson(
    version:    String,
    buildTime:  String,
    instanceId: String,
    relays:     List[RelayJson]
)

object RudderJson {
  implicit val transformSystemInfo: Transformer[SystemInfo, RudderJson] = {
    Transformer
      .define[SystemInfo, RudderJson]
      .withFieldComputed(_.version, _.public.rudderVersion)
      .withFieldComputed(_.buildTime, _.public.buildTime)
      .withFieldComputed(_.instanceId, _.priv.instanceId.value)
      .withFieldComputed(_.relays, _.priv.relays.map(r => RelayJson(r.id.value, r.hostname, r.managedNodes)))
      .buildTransformer
  }

  implicit val encoderRudderJson: JsonEncoder[RudderJson] = DeriveJsonEncoder.gen
}

sealed trait SystemInfoJson

object SystemInfoJson {

  case class AboutInfoJsonV20(rudder: AboutRudderInfoJsonV20) extends SystemInfoJson
  // version for Rudder 8.3
  case class AboutInfoJsonV21(
      rudder:  RudderJson,
      system:  SystemJson,
      nodes:   NodesJson,
      plugins: List[PluginJson]
  ) extends SystemInfoJson

  implicit val transformSystemInfo: Transformer[SystemInfo, AboutInfoJsonV21] = {
    Transformer
      .define[SystemInfo, AboutInfoJsonV21]
      .withFieldComputed(_.rudder, _.transformInto[RudderJson])
      .withFieldComputed(_.system, _.transformInto[SystemJson])
      .withFieldComputed(_.nodes, _.public.nodes.transformInto[NodesJson])
      .withFieldComputed(_.plugins, _.priv.plugins.toList.map(_.transformInto[PluginJson]))
      .buildTransformer
  }

  implicit val encoderAboutInfoJsonV20: JsonEncoder[AboutInfoJsonV20] = DeriveJsonEncoder.gen
  implicit val encoderAboutInfoJsonV21: JsonEncoder[AboutInfoJsonV21] = DeriveJsonEncoder.gen

  implicit val encoderSystemInfoJson: JsonEncoder[SystemInfoJson] = new JsonEncoder[SystemInfoJson] {
    override def unsafeEncode(a: SystemInfoJson, indent: Option[Int], out: Write): Unit = {
      a match {
        case x: AboutInfoJsonV20 => encoderAboutInfoJsonV20.unsafeEncode(x, indent, out)
        case x: AboutInfoJsonV21 => encoderAboutInfoJsonV21.unsafeEncode(x, indent, out)
      }
    }
  }
}
