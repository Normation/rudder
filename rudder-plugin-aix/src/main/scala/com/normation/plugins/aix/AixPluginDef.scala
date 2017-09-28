/*
*************************************************************************************
* Copyright 2017 Normation SAS
*************************************************************************************
*
* All rights reserved.
*
***************************************************************************************
*/

package com.normation.plugins.aix

import bootstrap.liftweb.RudderConfig
import com.normation.plugins.PluginName
import com.normation.plugins.PluginVersion
import com.normation.plugins.RudderPluginDef
import com.normation.rudder.domain.logger.PluginLogger
import com.typesafe.config.ConfigFactory
import net.liftweb.common.Loggable
import scala.xml.NodeSeq
import com.normation.plugins.PluginStatus

class AixPluginDef(info: PluginStatus) extends RudderPluginDef with Loggable {

  override val basePackage = "com.normation.plugins.aix"

  //get properties name for the plugin from "build.conf" file
  //have default string for errors (and avoid "missing prop exception"):
  val defaults = List("plugin-id", "plugin-name", "plugin-version").map(p => s"$p=missing property with name '$p' in file 'build.conf'").mkString("\n")
  // ConfigFactory does not want the "/" at begining nor the ".conf" on the end
  val buildConfPath = basePackage.replaceAll("""\.""", "/") + "/build.conf"
  val buildConf = ConfigFactory.load(this.getClass.getClassLoader, buildConfPath).withFallback(ConfigFactory.parseString(defaults))

  override val name = PluginName(buildConf.getString("plugin-id"))
  override val displayName = buildConf.getString("plugin-name")
  override val version = {
    val versionString = buildConf.getString("plugin-version")
    PluginVersion.from(versionString).getOrElse(
      //a version name that indicate an erro
      PluginVersion(0,0,1, s"ERROR-PARSING-VERSION: ${versionString}")
    )
  }

  override val description : NodeSeq  =
    <div>
     <p>
     Rudder AIX plugin allows to generate policies for Rudder Agent on AIX nodes.
     </p>
    </div>

  val status = info

  def init = {
    PluginLogger.info(s"loading '${buildConf.getString("plugin-id")}:${version.toString}' plugin")

    // add policy generation for DSC agent
    RudderConfig.writeAllAgentSpecificFiles.addAgentSpecificGeneration(new AixAgentSpecificGeneration(info))
  }

  def oneTimeInit : Unit = {}

  val configFiles = Seq()
}
