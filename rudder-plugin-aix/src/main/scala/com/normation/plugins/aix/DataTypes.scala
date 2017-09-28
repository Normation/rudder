/*
*************************************************************************************
* Copyright 2017 Normation SAS
*************************************************************************************
*
* All rights reserved.
*
**************************************************************************************
*/

package com.normation.plugins.aix

import com.normation.inventory.domain.AgentType
import com.normation.inventory.domain.AixOS
import com.normation.inventory.domain.OsDetails
import com.normation.plugins.PluginStatus
import com.normation.rudder.services.policies.write.AgentNodeWritableConfiguration
import com.normation.rudder.services.policies.write.AgentSpecificFile
import com.normation.rudder.services.policies.write.AgentSpecificGeneration
import com.normation.rudder.services.policies.write.CFEngineAgentSpecificGeneration
import net.liftweb.common.Box
import net.liftweb.common.Logger
import org.slf4j.LoggerFactory

/**
 * Applicative log of interest for Rudder ops.
 */
object AixLogger extends Logger {
  override protected def _logger = LoggerFactory.getLogger("aix")
}

object DscTimingLogger extends Logger {
  override protected def _logger = LoggerFactory.getLogger("aix.timing")
}



/*
 * This will go in the plugin, and will be contributed somehow at config time.
 */
class AixAgentSpecificGeneration(pluginInfo: PluginStatus) extends AgentSpecificGeneration {

  override def handle(agentType: AgentType, osDetail: OsDetails): Boolean = osDetail.os == AixOS

  override def write(cfg: AgentNodeWritableConfiguration): Box[List[AgentSpecificFile]] = {
    CFEngineAgentSpecificGeneration.write(cfg)
  }

  import com.normation.rudder.services.policies.write.BuildBundleSequence.{ BundleSequenceVariables, InputFile, TechniqueBundles }
  override def getBundleVariables(
      systemInputs: List[InputFile]
    , sytemBundles: List[TechniqueBundles]
    , userInputs  : List[InputFile]
    , userBundles : List[TechniqueBundles]
  ) : BundleSequenceVariables = CFEngineAgentSpecificGeneration.getBundleVariables(systemInputs, sytemBundles, userInputs, userBundles)
}
