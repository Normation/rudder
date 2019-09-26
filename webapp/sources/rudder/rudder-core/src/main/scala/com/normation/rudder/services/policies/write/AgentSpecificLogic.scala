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

package com.normation.rudder.services.policies.write

import net.liftweb.common.Box
import com.normation.inventory.domain.AgentType
import com.normation.utils.Control.sequence
import net.liftweb.common.Full
import net.liftweb.common.Failure
import com.normation.inventory.domain.Linux
import com.normation.inventory.domain.Bsd
import com.normation.rudder.domain.Constants
import com.normation.rudder.services.policies.NodeRunHook

/*
 * This file contain agent-type specific logic used during the policy
 * writing process, mainly:
 * - specific files (like expected_reports.csv for CFEngine-based agent)
 * - specific format for "bundle" sequence.
 * - specific escape for strings
 */

//containser for agent specific file written during policy generation
final case class AgentSpecificFile(
    path: String
)


trait AgentSpecificStringEscape {
  def escape(value: String): String
}

//how do we write bundle sequence / input files system variable for the agent?
trait AgentFormatBundleVariables {
  import BuildBundleSequence._
  def getBundleVariables(
      systemInputs: List[InputFile]
    , sytemBundles: List[TechniqueBundles]
    , userInputs  : List[InputFile]
    , userBundles : List[TechniqueBundles]
    , runHooks    : List[NodeRunHook]
  ) : BundleSequenceVariables
}

// does that implementation knows something about the current agent type
trait AgentSpecificGenerationHandle {
  def handle(agentNodeProps: AgentNodeProperties): Boolean
}

// specific generic (i.e non bundle order linked) system variable
// todo - need to plug in systemvariablespecservice
// idem for the bundle seq

//write what must be written for the given configuration
trait WriteAgentSpecificFiles {
  def write(cfg: AgentNodeWritableConfiguration): Box[List[AgentSpecificFile]]
}

trait AgentSpecificGeneration
  extends AgentSpecificGenerationHandle
     with AgentFormatBundleVariables
     with WriteAgentSpecificFiles
     with AgentSpecificStringEscape


//the extendable pipeline able to find the correct agent given (agentype, os)

class AgentRegister {
  /**
   * Ordered list of handlers, init with the default agent (CFEngine for linux)
   */
  private[this] var pipeline: List[AgentSpecificGeneration] =  {
    CFEngineAgentSpecificGeneration :: Nil
  }

  /**
   * Add the support for a new agent generation type.
   */
  def addAgentLogic(agent: AgentSpecificGeneration): Unit = synchronized {
    //add at the end of the pipeline new generation
    pipeline = pipeline :+ agent
  }

  /**
   * Find the first agent matching the required agentType/osDetail and apply f on it.
   * If none is found, return a failure.
   */
  def findMap[T](agentNodeProps: AgentNodeProperties)(f: AgentSpecificGeneration => Box[T]) = {
    pipeline.find(handler => handler.handle(agentNodeProps)) match {
      case None    =>
        val msg = if(agentNodeProps.isPolicyServer) {
          s"""We could not generate policies for server '${agentNodeProps.nodeId.value}', therefore making updates """ +
          s"""for nodes behind it unavailable. Maybe you are missing 'scale out' plugin?"""
        } else {
          s"""We could not generate policies for node '${agentNodeProps.nodeId.value}' based on """ +
          s"""'${agentNodeProps.agentType.toString()}' agent and '${agentNodeProps.osDetails.fullName}' system. Maybe you are missing a dedicated plugin?"""
        }
        Failure(msg)

      case Some(h) => f(h)
    }
  }

  /**
   * Find the first agent matching the required agentType/osDetail.
   * If none is found, return a failure.
   */
  def findHandler(agentNodeProps: AgentNodeProperties) = {
    pipeline.find(handler => handler.handle(agentNodeProps)) match {
      case None    =>
        val msg = if(agentNodeProps.isPolicyServer) {
          s"""We could not generate policies for server '${agentNodeProps.nodeId.value}', therefore making updates """ +
            s"""for nodes behind it unavailable. Maybe you are missing 'scale out' plugin?"""
        } else {
          s"""We could not generate policies for node '${agentNodeProps.nodeId.value}' based on """ +
            s"""'${agentNodeProps.agentType.toString()}' agent and '${agentNodeProps.osDetails.fullName}' system. Maybe you are missing a dedicated plugin?"""
        }
        Failure(msg)

      case Some(h) => Full(h)
    }
  }

  /**
   * Execute a `traverse` on the registered agent, where `f` is used when the agentType/os is handled.
   * Exec default on other cases.
   *
   */
  def traverseMap[T](agentNodeProps: AgentNodeProperties)(default: () => Box[List[T]], f: AgentSpecificGeneration => Box[List[T]]): Box[List[T]] = {
    (sequence(pipeline) { handler =>
      if(handler.handle(agentNodeProps)) {
        f(handler)
      } else {
        default()
      }
    }).map( _.flatten.toList)
  }

}


// the pipeline of processing for the specific writes
class WriteAllAgentSpecificFiles(agentRegister: AgentRegister) extends WriteAgentSpecificFiles {


  override def write(cfg: AgentNodeWritableConfiguration): Box[List[AgentSpecificFile]] = {
    agentRegister.traverseMap(cfg.agentNodeProps)( () => Full(Nil), _.write(cfg))
  }

  import BuildBundleSequence.{InputFile, TechniqueBundles, BundleSequenceVariables}
  def getBundleVariables(
      agentNodeProps: AgentNodeProperties
    , systemInputs  : List[InputFile]
    , sytemBundles  : List[TechniqueBundles]
    , userInputs    : List[InputFile]
    , userBundles   : List[TechniqueBundles]
    , runHooks      : List[NodeRunHook]
  ) : Box[BundleSequenceVariables] = {
    //we only choose the first matching agent for that
    agentRegister.findMap(agentNodeProps)( a => Full(a.getBundleVariables(systemInputs, sytemBundles, userInputs, userBundles, runHooks)))
  }
}

object CFEngineAgentSpecificGeneration extends AgentSpecificGeneration {

  /* Escape string to be CFEngine compliant
   * a \ will be escaped to \\
   * a " will be escaped to \"
   */
  override def escape(value: String): String = {
    value.replaceAll("""\\""", """\\\\""").replaceAll(""""""" , """\\"""" )
  }

  /**
   * This version only handle open source unix-like (*linux, *bsd) for
   * the community version of CFEngine. Plugins are needed for other
   * flavors (anything with CFEngine enterprise, AIX, Solaris, etc).
   * The node must be a simple node or the root policy server.
   */
  override def handle(agentNodeProps: AgentNodeProperties): Boolean = {
    (!agentNodeProps.isPolicyServer || agentNodeProps.nodeId == Constants.ROOT_POLICY_SERVER_ID) && (
      (agentNodeProps.agentType, agentNodeProps.osDetails) match {
        case (AgentType.CfeCommunity, _: Linux) => true
        case (AgentType.CfeCommunity, _: Bsd  ) => true
        // for now AIX, Windows, Solaris and UnknownOS goes there.
        case _                                  => false
      }
    )
  }

  override def write(cfg: AgentNodeWritableConfiguration): Box[List[AgentSpecificFile]] = {
    Full(Nil)
  }

  import BuildBundleSequence.{InputFile, TechniqueBundles, BundleSequenceVariables}
  override def getBundleVariables(
      systemInputs: List[InputFile]
    , sytemBundles: List[TechniqueBundles]
    , userInputs  : List[InputFile]
    , userBundles : List[TechniqueBundles]
    , runHooks    : List[NodeRunHook]
  ) : BundleSequenceVariables = CfengineBundleVariables.getBundleVariables(escape, systemInputs, sytemBundles, userInputs, userBundles, runHooks)

}
