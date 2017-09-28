/*
*************************************************************************************
* Copyright 2017 Normation SAS
*************************************************************************************
*
* All rights reserved.
*
*************************************************************************************
*/

package bootstrap.rudder.plugin


import com.normation.plugins.aix.AixPluginDef
import com.normation.plugins.aix.CheckRudderPluginAixEnableImpl

import net.liftweb.common.Loggable
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Bean
import org.springframework.context.ApplicationContextAware
import org.springframework.beans.factory.InitializingBean
import org.springframework.context.ApplicationContext

object AixConf {

  //nothing to configure


}

/**
 * Init module
 */
@Configuration
class AixConf extends Loggable with ApplicationContextAware with InitializingBean {
  @Bean def aixModuleDef = new AixPluginDef(new CheckRudderPluginAixEnableImpl())

  // spring thingies
  var appContext : ApplicationContext = null

  override def afterPropertiesSet() : Unit = {}

  override def setApplicationContext(applicationContext: ApplicationContext) = {
    appContext = applicationContext
  }
}

