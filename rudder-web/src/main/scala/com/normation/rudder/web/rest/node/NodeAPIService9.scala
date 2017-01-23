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

package com.normation.rudder.web.rest.node

import com.normation.eventlog.ModificationId

import com.normation.inventory.domain._
import com.normation.rudder.batch.AsyncDeploymentAgent
import com.normation.rudder.batch.AutomaticStartDeployment
import com.normation.rudder.domain.nodes.JsonSerialisation._
import com.normation.rudder.domain.nodes.NodeProperty
import com.normation.rudder.repository.WoNodeRepository
import com.normation.rudder.services.nodes.NodeInfoService
import com.normation.rudder.web.model.CurrentUser
import com.normation.rudder.web.rest.RestExtractorService
import com.normation.rudder.web.rest.RestUtils._
import com.normation.rudder.web.rest.RestUtils
import com.normation.utils.StringUuidGenerator

import net.liftweb.common._
import net.liftweb.http.Req
import net.liftweb.json._
import net.liftweb.json.JsonDSL._
import com.normation.eventlog.EventActor
import com.normation.rudder.domain.nodes.Node
import java.io.InputStream
import java.io.OutputStream
import java.io.IOException
import com.zaxxer.nuprocess.NuAbstractProcessHandler
import java.nio.ByteBuffer
import java.io.PipedInputStream
import java.io.PipedOutputStream
import com.zaxxer.nuprocess.NuProcessBuilder
import java.util.Arrays
import com.normation.rudder.domain.nodes.NodeInfo
import com.normation.rudder.datasources.DataSourceUpdateCallbacks
import com.normation.rudder.datasources.DataSourceId

class NodeApiService9 (
    dataSourceUpdate: DataSourceUpdateCallbacks
) extends Loggable {

  def reloadDataAllNodes(actor: EventActor): Unit = {
    dataSourceUpdate.onUserAskUpdateAllNodes(actor)
  }

  def reloadDataAllNodesFor(actor: EventActor, datasourceId: DataSourceId): Unit = {
    dataSourceUpdate.onUserAskUpdateAllNodesFor(actor, datasourceId)
  }

  def reloadDataOneNode(actor: EventActor, nodeId: NodeId): Unit = {
    dataSourceUpdate.onUserAskUpdateNode(actor, nodeId)
  }

  def reloadDataOneNodeFor(actor: EventActor, nodeId: NodeId, datasourceId: DataSourceId): Unit = {
    dataSourceUpdate.onUserAskUpdateNodeFor(actor, nodeId, datasourceId)
  }
}
