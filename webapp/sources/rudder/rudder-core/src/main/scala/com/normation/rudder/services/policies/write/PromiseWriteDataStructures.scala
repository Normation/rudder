/*
*************************************************************************************
* Copyright 2011 Normation SAS
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

package com.normation.rudder.services.policies.write

import com.normation.cfclerk.domain.TechniqueFile
import com.normation.cfclerk.domain.TechniqueResourceId
import com.normation.inventory.domain.AgentType
import com.normation.inventory.domain.NodeId
import com.normation.rudder.services.policies.NodeConfiguration
import com.normation.utils.HashcodeCaching
import com.normation.templates.STVariable
import com.normation.cfclerk.domain.Variable
import com.normation.inventory.domain.OsDetails
import com.normation.inventory.domain.ServerRole
import com.normation.rudder.services.policies.PolicyId

/**
 * That file store utility case classes about information used to
 * during the promises generation phases, like source and destination path,
 * prepared template, etc.
 *
 * They are used so that API and code reading make more sense
 * (String, String, String) parameter is not really telling.
 */

/**
 * Data structure that holds all the configuration about a node/agent,
 * as Rudder model view them (i.e, before preparing them so that they
 * can be written as CFEngine rules)
 */
case class AgentNodeConfiguration(
    config   : NodeConfiguration
  , agentType: AgentType
  , paths    : NodePromisesPaths
)


/**
 * A data structure which handle the minimum information that allows to
 * qualify the agent "kind": agentType, os details, policy server and rudder roles, etc.
 */
final case class AgentNodeProperties(
    nodeId        : NodeId
  , agentType     : AgentType
  , osDetails     : OsDetails
  , isPolicyServer: Boolean
  , serverRoles   : Set[ServerRole]
)

/**
 * Data structure that hold all the information to actually write
 * configuration for a node/agent in a CFEngine compatible way:
 * - the path where to write things,
 * - it's techniques template with their variables,
 * - it's technique other resources,
 * - the expected reports csv file content
 */
final case class AgentNodeWritableConfiguration(
    agentNodeProps    : AgentNodeProperties
  , paths             : NodePromisesPaths
  , preparedTechniques: Seq[PreparedTechnique]
  , systemVariables   : Map[String, Variable]
)

/**
 * Data structure that holds information about where to copy generated promises
 * from their generation directory to their final directory.
 * A back-up folder is also provided to save a copy.
 */
case class NodePromisesPaths(
    nodeId      : NodeId
  , baseFolder  : String //directory where the file have to in the end
  , newFolder   : String //poclicies are temporarly store in a policyName.new directory
  , backupFolder: String
) extends HashcodeCaching

/**
 * A class that store a list of "prepared template", i.e templates with
 * their destination computed and all the variables to use to replace
 * parameter in them.
 */
case class PreparedTechnique(
    templatesToProcess  : Set[TechniqueTemplateCopyInfo]
  , environmentVariables: Seq[STVariable]
  , filesToCopy         : Set[TechniqueFile]
  , reportIdToReplace   : Option[PolicyId] // if the technique is multi-instance multi-policy, the id to use in replacement
) extends HashcodeCaching

/**
 * A class that store information about a template to copy somewhere.
 * It gives what template to copy where.
 */
case class TechniqueTemplateCopyInfo(
    id         : TechniqueResourceId
  , destination: String
  , content    : String //template content as a file
) extends HashcodeCaching {
  override def toString() = s"Technique template ${id.name}; destination ${destination}"
}


case class TechniqueResourceCopyInfo(
    id         : TechniqueResourceId
  , destination: String
  , content    : String //template resource as a file
) extends HashcodeCaching {
  override def toString() = s"Technique resource ${id.name}; destination ${destination}"
}
