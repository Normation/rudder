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

import cats.syntax.traverse.*
import com.normation.errors.*
import com.normation.inventory.domain.AgentType
import com.normation.inventory.domain.Linux
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
  import BuildBundleSequence.*
  def getBundleVariables(
      inputs:   List[InputFile],
      bundles:  List[TechniqueBundles],
      runHooks: List[NodeRunHook]
  ): PureResult[BundleSequenceVariables]
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
  def write(cfg: AgentNodeWritableConfiguration): PureResult[List[AgentSpecificFile]]
}

trait AgentSpecificGeneration
    extends AgentSpecificGenerationHandle with AgentFormatBundleVariables with WriteAgentSpecificFiles
    with AgentSpecificStringEscape

//the extendable pipeline able to find the correct agent given (agentype, os)

class AgentRegister {

  /**
   * Ordered list of handlers, init with the default agent (CFEngine for linux)
   */
  private var pipeline: List[AgentSpecificGeneration] = {
    CFEngineAgentSpecificGeneration :: Nil
  }

  /**
   * Add the support for a new agent generation type.
   */
  def addAgentLogic(agent: AgentSpecificGeneration): Unit = synchronized {
    // add at the end of the pipeline new generation
    pipeline = pipeline :+ agent
  }

  /**
   * Find the first agent matching the required agentType/osDetail and apply f on it.
   * If none is found, return an error message for the user.
   */
  def findMap[T](agentNodeProps: AgentNodeProperties)(f: AgentSpecificGeneration => PureResult[T]): PureResult[T] = {
    pipeline.find(handler => handler.handle(agentNodeProps)) match {
      case None =>
        val msg = if (agentNodeProps.isPolicyServer) {
          s"""We could not generate policies for server '${agentNodeProps.nodeId.value}', therefore making updates """ +
          s"""for nodes behind it unavailable. Maybe you are missing 'scale out' plugin?"""
        } else {
          s"""We could not generate policies for node '${agentNodeProps.nodeId.value}' based on """ +
          s"""'${agentNodeProps.agentType
              .toString()}' agent and '${agentNodeProps.osDetails.fullName}' system. Maybe you are missing a dedicated plugin?"""
        }
        Left(Unexpected(msg))

      case Some(h) => f(h)
    }
  }

  /**
   * Find the first agent matching the required agentType/osDetail.
   * If none is found, return an error message for the user.
   */
  def findHandler(agentNodeProps: AgentNodeProperties): PureResult[AgentSpecificGeneration] = {
    findMap[AgentSpecificGeneration](agentNodeProps)(Right(_))
  }

  /**
   * Execute a `traverse` on the registered agent, where `f` is used when the agentType/os is handled.
   * Exec default on other cases.
   *
   */
  def traverseMap[T](
      agentNodeProps: AgentNodeProperties
  )(default: () => PureResult[List[T]], f: AgentSpecificGeneration => PureResult[List[T]]): PureResult[List[T]] = {
    (pipeline.traverse { handler =>
      if (handler.handle(agentNodeProps)) {
        f(handler)
      } else {
        default()
      }
    }).map(_.flatten.toList)
  }

}

// the pipeline of processing for the specific writes
class WriteAllAgentSpecificFiles(agentRegister: AgentRegister) extends WriteAgentSpecificFiles {

  override def write(cfg: AgentNodeWritableConfiguration): PureResult[List[AgentSpecificFile]] = {
    agentRegister.traverseMap(cfg.agentNodeProps)(() => Right(Nil), _.write(cfg))
  }

  import BuildBundleSequence.BundleSequenceVariables
  import BuildBundleSequence.InputFile
  import BuildBundleSequence.TechniqueBundles
  def getBundleVariables(
      agentNodeProps: AgentNodeProperties,
      inputs:         List[InputFile],
      bundles:        List[TechniqueBundles],
      runHooks:       List[NodeRunHook]
  ): PureResult[BundleSequenceVariables] = {
    // we only choose the first matching agent for that
    agentRegister.findMap(agentNodeProps)(a => a.getBundleVariables(inputs, bundles, runHooks))
  }
}

object CFEngineAgentSpecificGeneration extends AgentSpecificGeneration {

  /* Escape string to be CFEngine compliant
   * a \ will be escaped to \\
   * a " will be escaped to \"
   */
  override def escape(value: String): String = {
    value.replaceAll("""\\""", """\\\\""").replaceAll(""""""", """\\"""")
  }

  /**
   * This version only handle open source unix-like (*linux) for
   * the community version of CFEngine.
   */
  override def handle(agentNodeProps: AgentNodeProperties): Boolean = {
    (!agentNodeProps.isPolicyServer || agentNodeProps.nodeId == Constants.ROOT_POLICY_SERVER_ID) && (
      (agentNodeProps.agentType, agentNodeProps.osDetails) match {
        case (AgentType.CfeCommunity, _: Linux) => true
        // for now Windows and UnknownOS goes there.
        case _                                  => false
      }
    )
  }

  override def write(cfg: AgentNodeWritableConfiguration): PureResult[List[AgentSpecificFile]] = {
    Right(Nil)
  }

  import BuildBundleSequence.BundleSequenceVariables
  import BuildBundleSequence.InputFile
  import BuildBundleSequence.TechniqueBundles
  override def getBundleVariables(
      inputs:   List[InputFile],
      bundles:  List[TechniqueBundles],
      runHooks: List[NodeRunHook]
  ): PureResult[BundleSequenceVariables] =
    Right(CfengineBundleVariables.getBundleVariables(escape, inputs, bundles, runHooks))

}
