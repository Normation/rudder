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

package bootstrap.liftweb.metrics

import bootstrap.liftweb.PluginsInfo
import com.normation.errors.Inconsistency
import com.normation.errors.IOResult
import com.normation.errors.PureResult
import com.normation.inventory.domain.NodeId
import com.normation.plugins.GlobalPluginsLicenseLimits
import com.normation.rudder.domain.Constants
import com.normation.rudder.facts.nodes.CoreNodeFact
import com.normation.rudder.facts.nodes.NodeFactRepository
import com.normation.rudder.facts.nodes.QueryContext
import com.normation.rudder.metrics.FetchDataService
import com.normation.rudder.metrics.JvmInfo
import com.normation.rudder.metrics.PrivateSystemInfo
import com.normation.rudder.metrics.PublicSystemInfo
import com.normation.rudder.metrics.RelayInfo
import com.normation.rudder.metrics.SystemInfo
import com.normation.rudder.metrics.SystemInfoService
import com.normation.rudder.services.servers.InstanceIdService
import com.normation.rudder.services.servers.PolicyServerManagementService
import scala.collection.MapView

class SystemInfoServiceImpl(
    getNodeMetrics:      FetchDataService,
    nodeFactRepository:  NodeFactRepository,
    instanceIdService:   InstanceIdService,
    policyServerService: PolicyServerManagementService
) extends SystemInfoService {
  // properties from version.properties file,
  val (
    rudderMajorVersion,
    rudderFullVersion,
    builtTimestamp
  ) = {
    val p = new java.util.Properties
    p.load(this.getClass.getClassLoader.getResourceAsStream("version.properties"))
    def checkProp(p: java.util.Properties, x: String): PureResult[String] = p.getProperty(x) match {
      case null => Left(Inconsistency(s"The system property '${x}' is missing from 'version.properties'"))
      case s    => Right(s)
    }
    (
      checkProp(p, "rudder-major-version"),
      checkProp(p, "rudder-full-version"),
      checkProp(p, "build-timestamp")
    )
  }

  override def getPublicInfo(): IOResult[PublicSystemInfo] = {
    for {
      mv  <- rudderMajorVersion.toIO
      fv  <- rudderFullVersion.toIO
      bt  <- builtTimestamp.toIO
      r   <-
        nodeFactRepository
          .get(Constants.ROOT_POLICY_SERVER_ID)(using QueryContext.systemQC)
          .notOptional(s"The root server is missing!")
      jvmN = System.getProperty("java.vm.name")
      jvmV = System.getProperty("java.vm.version")
      cmd <- IOResult
               .attempt(ProcessHandle.current().info().commandLine())
               .map(_.orElseGet(() => "rudder process command line not available"))
      m   <- getNodeMetrics.getFrequentNodeMetric()
    } yield PublicSystemInfo(mv, fv, bt, r.os, JvmInfo(jvmN, jvmV, cmd), m)
  }

  private def relayInfo(relays: List[NodeId], nodes: MapView[NodeId, CoreNodeFact]): List[RelayInfo] = {
    val sums = nodes.groupMapReduce(p => p._2.rudderSettings.policyServerId)(_ => 1)(_ + _)

    relays.map(id => RelayInfo(id, nodes.get(id).map(_.fqdn).getOrElse("no hostname information"), sums.getOrElse(id, 0)))
  }

  override def getPrivateInfo(): IOResult[PrivateSystemInfo] = {
    for {
      servers <- policyServerService.getPolicyServers()
      nodes   <- nodeFactRepository.getAll()(using QueryContext.systemQC)
    } yield {
      val pi = PluginsInfo.pluginInfos
      PrivateSystemInfo(
        instanceIdService.instanceId,
        relayInfo(servers.relays.map(_.id), nodes),
        pi.globalLicense.map(GlobalPluginsLicenseLimits.from(_)),
        pi.plugins
      )
    }
  }

  override def getAll(): IOResult[SystemInfo] = {
    for {
      priv <- getPrivateInfo()
      pub  <- getPublicInfo()
    } yield SystemInfo(priv, pub)
  }
}
