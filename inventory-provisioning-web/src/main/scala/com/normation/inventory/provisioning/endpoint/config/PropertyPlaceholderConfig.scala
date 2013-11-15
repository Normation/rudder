/*
*************************************************************************************
* Copyright 2011 Normation SAS
*************************************************************************************
*
* This program is free software: you can redistribute it and/or modify
* it under the terms of the GNU Affero General Public License as
* published by the Free Software Foundation, either version 3 of the
* License, or (at your option) any later version.
*
* In accordance with the terms of section 7 (7. Additional Terms.) of
* the GNU Affero GPL v3, the copyright holders add the following
* Additional permissions:
* Notwithstanding to the terms of section 5 (5. Conveying Modified Source
* Versions) and 6 (6. Conveying Non-Source Forms.) of the GNU Affero GPL v3
* licence, when you create a Related Module, this Related Module is
* not considered as a part of the work and may be distributed under the
* license agreement of your choice.
* A "Related Module" means a set of sources files including their
* documentation that, without modification of the Source Code, enables
* supplementary functions or services in addition to those offered by
* the Software.
*
* This program is distributed in the hope that it will be useful,
* but WITHOUT ANY WARRANTY; without even the implied warranty of
* MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
* GNU Affero General Public License for more details.
*
* You should have received a copy of the GNU Affero General Public License
* along with this program. If not, see <http://www.gnu.org/licenses/agpl.html>.
*
*************************************************************************************
*/

package com.normation.inventory.provisioning.endpoint.config

import org.springframework.context.annotation.{Bean,Configuration}
import org.springframework.beans.factory.config.PropertyPlaceholderConfigurer
import org.springframework.core.io.{ClassPathResource, FileSystemResource}

import java.io.File
import org.slf4j.LoggerFactory

/**
 * Configuration for the property file HAS TO
 * be on an other class
 */
@Configuration
class PropertyPlaceholderConfig {


  val JVM_CONFIG_FILE_KEY = "inventoryweb.configFile"
  val DEFAULT_CONFIG_FILE_NAME = "configuration.properties"

  val logger = LoggerFactory.getLogger(classOf[PropertyPlaceholderConfig])

  @Bean def propertyConfigurer : PropertyPlaceholderConfigurer = {
    val configurer = new PropertyPlaceholderConfigurer()
    System.getProperty(JVM_CONFIG_FILE_KEY) match {
      case null | "" => //use default location in classpath
        logger.info("JVM property -D{} is not defined, use configuration file in classpath",JVM_CONFIG_FILE_KEY)
        configurer.setLocation(new ClassPathResource(DEFAULT_CONFIG_FILE_NAME))
      case x => //so, it should be a full path, check it
        val config = new FileSystemResource(new File(x))
        if(config.exists && config.isReadable) {
          logger.info(s"Use configuration file defined by JVM property -D${JVM_CONFIG_FILE_KEY} : ${config.getPath}")
          configurer.setLocation(config)
        } else {
          logger.error(s"Can not find configuration file specified by JVM property ${JVM_CONFIG_FILE_KEY}: ${config.getPath} ; abort")
          throw new javax.servlet.UnavailableException("Configuration file not found: %s".format(config.getPath))
        }
    }

    configurer
  }
}
