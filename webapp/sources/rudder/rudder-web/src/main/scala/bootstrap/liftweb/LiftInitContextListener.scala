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

package bootstrap.liftweb

import bootstrap.liftweb.RudderProperties.config
import com.normation.errors.SystemError
import com.normation.rudder.domain.logger.ApplicationLogger
import com.normation.zio._
import com.typesafe.config.ConfigException
import java.io.File
import javax.servlet.ServletContextEvent
import net.liftweb.common._
import org.springframework.core.io.{ClassPathResource => CPResource}
import org.springframework.core.io.{FileSystemResource => FSResource}
import org.springframework.web.context.ContextLoaderListener
import org.springframework.web.context.support.WebApplicationContextUtils

/**
 * A context loader listener for initializing Spring webapp context
 * and logging.
 *
 * Spring application context is initialized here because:
 * - Java Annotation based web application context can ONLY be initialized thanks to a context param
 *   of filter (no comment on that...), see:
 *   http://static.springsource.org/spring/docs/3.0.x/spring-framework-reference/htmlsingle/spring-framework-reference.html#beans-java-instantiating-container-web
 * - Its one of the first thing to be done on servlet loading, and it can access ServletContext,
 *   which is necessary to be able to call WebApplicationContextUtils.getWebApplicationContext
 *
 */
class LiftInitContextListener extends ContextLoaderListener {

  // choose what Logback.xml file to use
  val JVM_CONFIG_FILE_KEY      = "logback.configurationFile"
  val DEFAULT_CONFIG_FILE_NAME = "logback.xml"

  val logbackFile = System.getProperty(JVM_CONFIG_FILE_KEY) match {
    case null | "" => // use default location in classpath
      val path = new CPResource(DEFAULT_CONFIG_FILE_NAME).getURL
      println("JVM property -D%s is not defined, use configuration file in classpath: /%s".format(JVM_CONFIG_FILE_KEY, path))
      path
    case x         => // so, it should be a full path, check it
      val config = new FSResource(new File(x))
      if (config.exists && config.isReadable) {
        println("Use configuration file defined by JVM property -D%s : %s".format(JVM_CONFIG_FILE_KEY, config.getPath))
        config.getURL
      } else {
        println(
          "ERROR: Can not find configuration file specified by JVM property %s: %s ; abort".format(
            JVM_CONFIG_FILE_KEY,
            config.getPath
          )
        )
        throw new javax.servlet.UnavailableException("Configuration file not found: %s".format(config.getPath))
      }
  }

  override def contextInitialized(sce: ServletContextEvent): Unit = {

    Logger.setup = Full(() => Logback.withFile(logbackFile)())

    val pid = new File("/proc/self").getCanonicalFile().getName()
    ApplicationLogger.info(s"Rudder starts with PID ${pid} on ${java.lang.Runtime.getRuntime().availableProcessors()} cores")

    /// init all our non-spring services ///

    val RUDDER_FATAL_EXCEPTIONS = {
      try {
        RudderProperties.splitProperty(config.getString("rudder.jvm.fatal.exceptions")).toSet
      } catch {
        case ex: ConfigException =>
          ApplicationLogger.info(
            "Property 'rudder.jvm.fatal.exceptions' is missing or empty in rudder.configFile. Only java.lang.Error will be fatal."
          )
          Set[String]()
      }
    }
    FatalException.init(RUDDER_FATAL_EXCEPTIONS)

    /*
     *
     * If any exception reaches that point in init, we want to stop
     * the application server.
     * The "normal" way to handle that would have been to raise an
     * UnavailableException, and so the web server would have then
     * unloaded Rudder and responded with "error 503" to queries.
     * But we are proxying Rudder with Apache and serving a "please
     * wait, loading" page on that case.
     * And in all case, an error in init almost always need a restart of Rudder.
     *
     * We need to try/catch everything, because things in RudderConfig can throw random
     * exception at init, and so it would be out of the ZIO effect manegement (in class
     * instantiation, before call to init() method).
     *
     * We simply "System.exit(1)", because we assume rudder is the only webapp on the
     * jetty server, and stoping jetty on exception generally does not work.
     */
    (try {
      for {
        _ <- RudderConfig.init().either.runNow
        // init Spring
        _ <- Right(super.contextInitialized(sce))
        // initializing webapp context
        _ <- Right(WebApplicationContextUtils.getWebApplicationContext(sce.getServletContext) match {
               // it's really an error here !
               case null =>
                 sys.error("Error when getting the application context from the web context. Missing ContextLoaderListener.")
               case c    => LiftSpringApplicationContext.setToNewContext(c)
             })
      } yield ()
    } catch {
      case ex: Throwable => Left(SystemError("Error during initialization of Rudder", ex))
    }) match {
      case Left(err) =>
        System.err.println(
          s"[${org.joda.time.format.ISODateTimeFormat.dateTime().print(System.currentTimeMillis())}] " +
          s"ERROR FATAL An error happen during Rudder boot. Rudder will stop now. Error: ${err.fullMsg}"
        )
        err.cause.printStackTrace()
        System.exit(1)
      case Right(_)  => // ok continue
    }

  }

  override def contextDestroyed(sce: ServletContextEvent): Unit = {
    // nothing special to do for us, only call super
    super.contextDestroyed(sce)
  }

}
