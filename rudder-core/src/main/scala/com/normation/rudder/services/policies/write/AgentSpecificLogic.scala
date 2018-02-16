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

import org.apache.commons.io.FileUtils
import scala.io.Codec
import java.io.File
import net.liftweb.common.Box
import net.liftweb.util.Helpers.tryo
import com.normation.inventory.domain.AgentType
import com.normation.utils.Control.sequence
import net.liftweb.common.Full
import net.liftweb.common.Failure
import com.normation.inventory.domain.OsDetails
import com.normation.inventory.domain.Linux
import com.normation.inventory.domain.Bsd

/*
 * This file contain agent-type specific logic used during the policy
 * writing process, mainly:
 * - specific files (like expected_reports.csv for CFEngine-based agent)
 * - specific format for "bundle" sequence.
 */

//containser for agent specific file written during policy generation
final case class AgentSpecificFile(
    path: String
)

//how do we write bundle sequence / input files system variable for the agent?
trait AgentFormatBundleVariables {
  import BuildBundleSequence._
  def getBundleVariables(
      systemInputs: List[InputFile]
    , sytemBundles: List[TechniqueBundles]
    , userInputs  : List[InputFile]
    , userBundles : List[TechniqueBundles]
  ) : BundleSequenceVariables
}


// does that implementation knows something about the current agent type
trait AgentSpecificGenerationHandle {
  def handle(agentType: AgentType, os: OsDetails): Boolean
}


// specific generic (i.e non bundle order linked) system variable
// todo - need to plug in systemvariablespecservice
// idem for the bundle seq

//write what must be written for the given configuration
trait WriteAgentSpecificFiles {
  def write(cfg: AgentNodeWritableConfiguration): Box[List[AgentSpecificFile]]
}

trait AgentSpecificGeneration extends AgentSpecificGenerationHandle with AgentFormatBundleVariables with WriteAgentSpecificFiles

// the pipeline of processing for the specific writes
class WriteAllAgentSpecificFiles extends WriteAgentSpecificFiles {

  /**
   * Ordered list of handlers, init with the default agent (cfenfine for linux)
   */
  private[this] var pipeline: List[AgentSpecificGeneration] =  {
    CFEngineAgentSpecificGeneration :: Nil
  }

  /**
   * Add the support for a new agent generation type.
   */
  def addAgentSpecificGeneration(agent: AgentSpecificGeneration): Unit = synchronized {
    //add at the end of the pipeline new generation
    pipeline = pipeline :+ agent
  }

  override def write(cfg: AgentNodeWritableConfiguration): Box[List[AgentSpecificFile]] = {
    (sequence(pipeline) { handler =>
      if(handler.handle(cfg.agentType, cfg.os)) {
        handler.write(cfg)
      } else {
        Full(Nil)
      }
    }).map( _.flatten.toList)
  }

  import BuildBundleSequence.{InputFile, TechniqueBundles, BundleSequenceVariables}
  def getBundleVariables(
      agentType   : AgentType
    , osDetails   : OsDetails
    , systemInputs: List[InputFile]
    , sytemBundles: List[TechniqueBundles]
    , userInputs  : List[InputFile]
    , userBundles : List[TechniqueBundles]
  ) : Box[BundleSequenceVariables] = {
    //we only choose the first matching agent for that
    pipeline.find(handler => handler.handle(agentType, osDetails)) match {
      case None    => Failure(s"We were unable to find how to create directive sequences for Agent type ${agentType.toString()} on '${osDetails.fullName}'. " +
                            "Perhaps you are missing the corresponding plugin. If not, please report a bug")
      case Some(h) => Full(h.getBundleVariables(systemInputs, sytemBundles, userInputs, userBundles))
    }
  }
}



object CFEngineAgentSpecificGeneration extends AgentSpecificGeneration {
  val GENEREATED_CSV_FILENAME = "rudder_expected_reports.csv"


  /**
   * This version only handle open source unix-like (*linux, *bsd) for
   * the community version of CFEngine. Plugins are needed for other
   * flavors (anything with CFEngine enterprise, AIX, Solaris, etc)
   */
  override def handle(agentType: AgentType, os: OsDetails): Boolean = {
    (agentType, os) match {
      case (AgentType.CfeCommunity, _: Linux) => true
      case (AgentType.CfeCommunity, _: Bsd  ) => true
      // for now AIX, Windows, Solaris and UnknownOS goes there.
      case _                                  => false
    }
  }

  override def write(cfg: AgentNodeWritableConfiguration): Box[List[AgentSpecificFile]] = {
    writeExpectedReportsCsv(cfg.paths, cfg.expectedReportsCsv, GENEREATED_CSV_FILENAME)
  }

  import BuildBundleSequence.{InputFile, TechniqueBundles, BundleSequenceVariables}
  override def getBundleVariables(
      systemInputs: List[InputFile]
    , sytemBundles: List[TechniqueBundles]
    , userInputs  : List[InputFile]
    , userBundles : List[TechniqueBundles]
  ) : BundleSequenceVariables = CfengineBundleVariables.getBundleVariables(systemInputs, sytemBundles, userInputs, userBundles)


  private[this] def writeExpectedReportsCsv(paths: NodePromisesPaths, csv: ExpectedReportsCsv, csvFilename: String): Box[List[AgentSpecificFile]] = {
    val path = new File(paths.newFolder, csvFilename)
    for {
        _ <- tryo { FileUtils.writeStringToFile(path, csv.lines.mkString("\n"), Codec.UTF8.charSet) } ?~!
               s"Can not write the expected reports CSV file at path '${path.getAbsolutePath}'"
    } yield {
      AgentSpecificFile(path.getAbsolutePath) :: Nil
    }
  }
}
