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

package com.normation.rudder

import com.normation.appconfig.ConfigRepository
import com.normation.appconfig.GenericConfigService
import com.normation.appconfig.ModifyGlobalPropertyInfo
import com.normation.errors.IOResult
import com.normation.eventlog.EventActor
import com.normation.eventlog.ModificationId
import com.normation.inventory.domain._
import com.normation.rudder.batch.AsyncWorkflowInfo
import com.normation.rudder.domain.Constants
import com.normation.rudder.domain.appconfig.RudderWebProperty
import com.normation.rudder.domain.eventlog
import com.normation.rudder.services.servers.AllowedNetwork
import com.normation.rudder.services.servers.PolicyServer
import com.normation.rudder.services.servers.PolicyServerManagementService
import com.normation.rudder.services.servers.PolicyServers
import com.normation.rudder.services.servers.PolicyServersUpdateCommand
import com.normation.rudder.services.workflows.WorkflowLevelService
import com.normation.zio._
import com.typesafe.config.ConfigFactory
import zio._
import zio.{System => _}
import zio.{Tag => _}

/*
 * Mock services for test, especially repositories, and provides
 * test data (nodes, directives, etc)
 */

class MockSettings(wfservice: WorkflowLevelService, asyncWF: AsyncWorkflowInfo) {

  val defaultPolicyServer = PolicyServers(
    PolicyServer(Constants.ROOT_POLICY_SERVER_ID, AllowedNetwork("192.168.2.0/32", "root") :: Nil),
    Nil
  )

  object policyServerManagementService extends PolicyServerManagementService {
    val repo = Ref.make(defaultPolicyServer).runNow

    override def getPolicyServers(): IOResult[PolicyServers] = repo.get

    override def savePolicyServers(policyServers: PolicyServers): IOResult[PolicyServers] = ???

    override def updatePolicyServers(
        commands: List[PolicyServersUpdateCommand],
        modId:    ModificationId,
        actor:    EventActor
    ): IOResult[PolicyServers] = {
      for {
        servers <- repo.get
        updated <- PolicyServerManagementService.applyCommands(servers, commands)
        saved   <- repo.set(updated)
      } yield updated
    }

    override def deleteRelaySystemObjects(policyServerId: NodeId): IOResult[Unit] = {
      updatePolicyServers(
        PolicyServersUpdateCommand.Delete(policyServerId) :: Nil,
        ModificationId(s"clean-${policyServerId.value}"),
        eventlog.RudderEventActor
      ).unit
    }
  }

  // a mock service that keep information in memory only
  val configService = {

    object configRepo extends ConfigRepository {
      val configs = Ref.make(Map[String, RudderWebProperty]()).runNow
      override def getConfigParameters(): IOResult[Seq[RudderWebProperty]] = {
        configs.get.map(_.values.toList)
      }
      override def saveConfigParameter(
          parameter:                RudderWebProperty,
          modifyGlobalPropertyInfo: Option[ModifyGlobalPropertyInfo]
      ): IOResult[RudderWebProperty] = {
        configs.update(_.updated(parameter.name.value, parameter)).map(_ => parameter)
      }
    }

    new GenericConfigService(ConfigFactory.empty(), configRepo, asyncWF, wfservice)
  }

}
