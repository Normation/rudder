package bootstrap.rudder.plugin

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import bootstrap.liftweb.RudderConfig
import com.normation.plugins.radius.RadiusPluginDef

/**
 * A simple module which add a new API URL and allows to
 * serve data describing the compliance of rules/nodes/directives.
 */
@Configuration
class ItopConf {

  @Bean def radiusPlugin = new RadiusPluginDef()

}

