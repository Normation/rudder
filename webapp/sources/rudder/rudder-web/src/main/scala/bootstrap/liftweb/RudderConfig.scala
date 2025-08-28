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

import better.files.File.root
import bootstrap.liftweb.checks.earlyconfig.db.*
import bootstrap.liftweb.checks.earlyconfig.ldap.*
import bootstrap.liftweb.checks.endconfig.action.*
import bootstrap.liftweb.checks.endconfig.consistency.*
import bootstrap.liftweb.checks.endconfig.migration.*
import bootstrap.liftweb.checks.endconfig.onetimeinit.*
import bootstrap.liftweb.metrics.SystemInfoServiceImpl
import com.normation.appconfig.*
import com.normation.box.*
import com.normation.cfclerk.services.*
import com.normation.cfclerk.services.impl.*
import com.normation.cfclerk.xmlparsers.*
import com.normation.cfclerk.xmlwriters.*
import com.normation.errors.*
import com.normation.inventory.domain.*
import com.normation.inventory.ldap.core.*
import com.normation.inventory.ldap.provisioning.*
import com.normation.inventory.provisioning.fusion.*
import com.normation.inventory.services.core.*
import com.normation.inventory.services.provisioning.*
import com.normation.ldap.sdk.*
import com.normation.plugins.*
import com.normation.plugins.cli.RudderPackageCmdService
import com.normation.plugins.settings.*
import com.normation.rudder.api.*
import com.normation.rudder.apidata.*
import com.normation.rudder.batch.*
import com.normation.rudder.campaigns.*
import com.normation.rudder.config.StatelessUserPropertyService
import com.normation.rudder.config.UserPropertyService
import com.normation.rudder.configuration.*
import com.normation.rudder.db.Doobie
import com.normation.rudder.domain.*
import com.normation.rudder.domain.logger.*
import com.normation.rudder.domain.nodes.NodeGroupId
import com.normation.rudder.domain.queries.*
import com.normation.rudder.domain.reports.NodeStatusReport
import com.normation.rudder.facts.nodes.*
import com.normation.rudder.git.GitItemRepository
import com.normation.rudder.git.GitRepositoryProvider
import com.normation.rudder.git.GitRepositoryProviderImpl
import com.normation.rudder.git.GitRevisionProvider
import com.normation.rudder.inventory.DefaultProcessInventoryService
import com.normation.rudder.inventory.InventoryFailedHook
import com.normation.rudder.inventory.InventoryFileWatcher
import com.normation.rudder.inventory.InventoryMover
import com.normation.rudder.inventory.InventoryProcessor
import com.normation.rudder.inventory.NodeFactInventorySaver
import com.normation.rudder.inventory.PostCommitInventoryHooks
import com.normation.rudder.inventory.ProcessFile
import com.normation.rudder.metrics.*
import com.normation.rudder.ncf
import com.normation.rudder.ncf.*
import com.normation.rudder.ncf.ParameterType.PlugableParameterTypeService
import com.normation.rudder.ncf.yaml.YamlTechniqueSerializer
import com.normation.rudder.properties.*
import com.normation.rudder.reports.*
import com.normation.rudder.reports.execution.*
import com.normation.rudder.repository.*
import com.normation.rudder.repository.jdbc.*
import com.normation.rudder.repository.ldap.*
import com.normation.rudder.repository.xml.*
import com.normation.rudder.rest.*
import com.normation.rudder.rest.data.ApiAccountMapping
import com.normation.rudder.rest.internal.*
import com.normation.rudder.rest.lift.*
import com.normation.rudder.rule.category.*
import com.normation.rudder.score.*
import com.normation.rudder.services.*
import com.normation.rudder.services.eventlog.*
import com.normation.rudder.services.healthcheck.*
import com.normation.rudder.services.marshalling.*
import com.normation.rudder.services.modification.*
import com.normation.rudder.services.nodes.*
import com.normation.rudder.services.nodes.history.impl.*
import com.normation.rudder.services.policies.*
import com.normation.rudder.services.policies.nodeconfig.*
import com.normation.rudder.services.policies.write.*
import com.normation.rudder.services.queries.*
import com.normation.rudder.services.quicksearch.FullQuickSearchService
import com.normation.rudder.services.reports.*
import com.normation.rudder.services.servers.*
import com.normation.rudder.services.system.*
import com.normation.rudder.services.user.*
import com.normation.rudder.services.workflows.*
import com.normation.rudder.tenants.*
import com.normation.rudder.users.*
import com.normation.rudder.web.components.administration.PolicyBackup
import com.normation.rudder.web.components.administration.RudderCompanyAccount
import com.normation.rudder.web.model.*
import com.normation.rudder.web.services.*
import com.normation.templates.FillTemplatesService
import com.normation.utils.CronParser.*
import com.normation.utils.StringUuidGenerator
import com.normation.utils.StringUuidGeneratorImpl
import com.normation.zio.*
import com.typesafe.config.Config
import com.typesafe.config.ConfigException
import com.typesafe.config.ConfigFactory
import com.unboundid.ldap.sdk.DN
import com.unboundid.ldap.sdk.RDN
import cron4s.CronExpr
import java.io.File
import java.nio.file.attribute.PosixFilePermission
import java.security.Security
import java.util.concurrent.TimeUnit
import net.liftweb.common.*
import net.liftweb.http.S
import org.apache.commons.io.FileUtils
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.joda.time.DateTimeZone
import org.joda.time.format.ISODateTimeFormat
import scala.collection.mutable.Buffer
import scala.concurrent.duration
import scala.concurrent.duration.FiniteDuration
import scala.util.Try
import zio.{Scheduler as _, System as _, *}
import zio.syntax.*

object RUDDER_CHARSET {
  import java.nio.charset.StandardCharsets
  def name  = "UTF-8"
  def value = StandardCharsets.UTF_8
}

/**
 * Define a resource for configuration.
 * For now, config properties can only be loaded from either
 * a file in the classpath, or a file in the file system.
 */
sealed trait ConfigResource                      extends Any
final case class ClassPathResource(name: String) extends AnyVal with ConfigResource
final case class FileSystemResource(file: File)  extends AnyVal with ConfigResource

/**
 * User defined configuration variable
 * (from properties file or alike)
 */
object RudderProperties {

  // extension used in overriding files
  val configFileExtensions: Set[String] = Set("properties", "prop", "config")

  // by default, used and configured to /opt/rudder/etc/rudder-web.properties
  val JVM_CONFIG_FILE_KEY = "rudder.configFile"

  // We have config overrides in a directory whose named is based on JVM_CONFIG_FILE_KEY
  // if defined, with a ".d" after it. It can be overridden with that key.
  // File in dir are sorted by name and the latter override the former.
  // Default: ${JVM_CONFIG_FILE_KEY}.d
  val JVM_CONFIG_DIR_KEY = "rudder.configDir"

  val DEFAULT_CONFIG_FILE_NAME = "configuration.properties"

  // Set security provider with bouncy castle one
  Security.addProvider(new BouncyCastleProvider())

  /**
   * Where to go to look for properties
   */
  val (configResource, overrideDir) = java.lang.System.getProperty(JVM_CONFIG_FILE_KEY) match {
    case null | "" => // use default location in classpath
      ApplicationLogger.info(s"JVM property -D${JVM_CONFIG_FILE_KEY} is not defined, use configuration file in classpath")
      (ClassPathResource(DEFAULT_CONFIG_FILE_NAME), None)

    case x => // so, it should be a full path, check it
      val config = new File(x)
      if (config.exists && config.canRead) {
        ApplicationLogger.info(
          s"Rudder application parameters are read from file defined by JVM property -D${JVM_CONFIG_FILE_KEY}: ${config.getPath}"
        )
        val configFile = FileSystemResource(config)

        val overrideDir = System.getProperty(JVM_CONFIG_DIR_KEY) match {
          case null | "" =>
            val path = configFile.file.getPath + ".d"
            ApplicationLogger.info(
              s"-> files for overriding configuration parameters are read from directory ${path} (that path can be overridden with JVM property -D${JVM_CONFIG_DIR_KEY})"
            )
            Some(path)
          case path      =>
            val d = better.files.File(path)
            if (d.exists) {
              if (d.isDirectory) {
                Some(d.pathAsString)
              } else {
                ApplicationLogger.warn(
                  s"JVM property -D${JVM_CONFIG_DIR_KEY} is defined to '${d.pathAsString}' which is not a directory: ignoring directory for overriding configurations"
                )
                None
              }
            } else {
              // we will create it
              Some(d.pathAsString)
            }
        }
        (configFile, overrideDir)
      } else {
        ApplicationLogger.error(
          s"Can not find configuration file specified by JVM property '${JVM_CONFIG_FILE_KEY}': '${config.getPath}' ; abort"
        )
        throw new jakarta.servlet.UnavailableException(s"Configuration file not found: ${config.getPath}")
      }
  }

  // Sorting is done here for meaningful debug log, but we need to reverse it
  // because in typesafe Config, we have "withDefault" (ie the opposite of overrides)
  val overrideConfigs: List[FileSystemResource] = overrideDir match {
    case None       => // no additional config to add
      Nil
    case Some(path) =>
      val d = better.files.File(path)
      try {
        d.createDirectoryIfNotExists(true)
        d.setPermissions(Set(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE))
      } catch {
        case ex: Exception =>
          ApplicationLogger.error(
            s"The configuration directory '${d.pathAsString}' for overriding file config can't be created: ${ex.getMessage}"
          )
      }
      val overrides = d.children.collect {
        case f if (configFileExtensions.contains(f.extension(includeDot = false, includeAll = false).getOrElse(""))) =>
          FileSystemResource(f.toJava)
      }.toList.sortBy(_.file.getPath)
      ApplicationLogger.debug(
        s"Overriding configuration files in '${d.pathAsString}': ${overrides.map(_.file.getName).mkString(", ")}"
      )
      overrides
  }

  // some value used as defaults for migration
  val migrationConfig: String = {
    s"""rudder.batch.reportscleaner.compliancelevels.delete.TTL=15
    """
  }

  // the Config lib does not define overriding but fallback, so we are starting with the directory, sorted last first
  // then default file, then migration things.
  val empty: Config = ConfigFactory.empty()

  val config: Config = {
    (
      (overrideConfigs.reverse :+ configResource)
        .foldLeft(ConfigFactory.empty()) {
          case (current, fallback) =>
            ApplicationLogger.debug(s"loading configuration from " + fallback)
            val conf = fallback match {
              case ClassPathResource(name)  => ConfigFactory.load(name)
              case FileSystemResource(file) => ConfigFactory.load(ConfigFactory.parseFile(file))
            }
            current.withFallback(conf)
        }
      )
      .withFallback(ConfigFactory.parseString(migrationConfig))
  }

  if (ApplicationLogger.isDebugEnabled) {
    // if override Dir is non empty, add the resolved config file with debug info in it
    overrideDir.foreach { d =>
      val dest = better.files.File(d) / "rudder-web.properties-resolved-debug"
      ApplicationLogger.debug(s"Writing resolved configuration file to ${dest.pathAsString}")
      import java.nio.file.attribute.PosixFilePermission.*
      try {
        dest.writeText(config.root().render()).setPermissions(Set(OWNER_READ))
      } catch {
        case ex: Exception =>
          ApplicationLogger.error(
            s"The debug file for configuration resolution '${dest.pathAsString}' can't be created: ${ex.getClass.getName}: ${ex.getMessage}"
          )
      }
    }
  }

  def splitProperty(s: String): List[String] = {
    s.split(",").toList.flatMap { s =>
      s.trim match {
        case "" => None
        case x  => Some(x)
      }
    }
  }

}

object RudderParsedProperties {
  import RudderConfigInit.*
  import RudderProperties.config

  val logger = ApplicationLogger.Properties

  // set the file location that contains mime info
  java.lang.System
    .setProperty(
      "content.types.user.table",
      this.getClass.getClassLoader.getResource("content-types.properties").getPath
    )

  //
  // Public properties
  // Here, we define static nouns for all theses properties
  //

  val filteredPasswords: Buffer[String] = scala.collection.mutable.Buffer[String]()

  def logRudderParsedProperties(): Unit = {
    import scala.jdk.CollectionConverters.*
    val config = RudderProperties.config
    if (ApplicationLogger.isInfoEnabled) {
      // sort properties by key name
      val properties = config.entrySet.asScala.toSeq.sortBy(_.getKey).flatMap { x =>
        // the log line: registered property: property_name=property_value
        if (hiddenRegisteredProperties.contains(x.getKey)) None
        else {
          Some(
            s"registered property: ${x.getKey}=${if (filteredPasswords.contains(x.getKey)) "**********" else x.getValue.render}"
          )
        }
      }
      ApplicationLogger.info("List of registered properties:")
      properties.foreach(p => ApplicationLogger.info(p))
      ApplicationLogger.info("Plugin's license directory: '/opt/rudder/etc/plugins/licenses/'")
    }
  }

  // the LDAP password used for authentication is not used here, but should not appear nonetheless
  filteredPasswords += "rudder.auth.ldap.connection.bind.password"
  // filter the fallback admin password
  filteredPasswords += "rudder.auth.admin.password"

  // list of configuration properties that we want to totally hide
  val hiddenRegisteredProperties: Buffer[String] = scala.collection.mutable.Buffer[String]()
  hiddenRegisteredProperties += "rudder.dir.licensesFolder"

  // auth backend is init too late to have a chance to hide its values, which is a bit sad.
  // We still need to make invisible all oauth/oidc client secret
  hiddenRegisteredProperties ++= {
    import scala.jdk.CollectionConverters.*
    config
      .entrySet()
      .asScala
      .map(_.getKey)
      .filter(s => s.startsWith("rudder.auth.oauth2.provider") && s.endsWith("client.secret"))
  }
  // other values

  val LDAP_HOST:   String = config.getString("ldap.host")
  val LDAP_PORT:   Int    = config.getInt("ldap.port")
  val LDAP_AUTHDN: String = config.getString("ldap.authdn")
  val LDAP_AUTHPW: String = config.getString("ldap.authpw");
  filteredPasswords += "ldap.authpw"

  // Define the increased minimum pool size following https://issues.rudder.io/issues/25892
  // 7 was tested in our load server (10k nodes, 7 load-test for node property change in parallel) and should
  // be enough even for big environments. More connection has a negative impact on startup time.
  val MIN_LDAP_MAX_POOL_SIZE = 7
  val LDAP_MAX_POOL_SIZE:                     Int      = {
    try {
      val poolSize = config.getInt("ldap.maxPoolSize")
      if (poolSize < MIN_LDAP_MAX_POOL_SIZE) {
        ApplicationLogger.warn(
          s"Property 'ldap.maxPoolSize' value is below ${MIN_LDAP_MAX_POOL_SIZE}, setting value to ${MIN_LDAP_MAX_POOL_SIZE} connections. see https://issues.rudder.io/issues/25892"
        )
        MIN_LDAP_MAX_POOL_SIZE
      } else {
        poolSize
      }

    } catch {
      case _: ConfigException =>
        ApplicationLogger.info(
          s"Property 'ldap.maxPoolSize' is missing or empty in rudder.configFile. Default to ${MIN_LDAP_MAX_POOL_SIZE} connections."
        )
        MIN_LDAP_MAX_POOL_SIZE
    }
  }
  val LDAP_MINIMUM_AVAILABLE_CONNECTION_GOAL: Int      = {
    try {
      val min = config.getInt("ldap.minimumAvailableConnectionGoal")
      if (min < 1) {
        ApplicationLogger.warn(
          "Property 'ldap.minimumAvailableConnectionGoal' value is below 1, setting value to 1"
        )
        1
      } else {
        min
      }
    } catch {
      case _: ConfigException =>
        ApplicationLogger.info(
          "Property 'ldap.minimumAvailableConnectionGoal' is missing or empty in rudder.configFile. Default to 2 always alive connections."
        )
        2
    }
  }
  val LDAP_CREATE_IF_NECESSARY:               Boolean  = {
    try {
      config.getBoolean("ldap.createIfNecessary")
    } catch {
      case ex: ConfigException =>
        ApplicationLogger.info(
          "Property 'ldap.createIfNecessary' is missing or empty in rudder.configFile. Default to true."
        )
        true
    }
  }
  val LDAP_MAX_WAIT_TIME:                     Duration = {
    try {
      val age = config.getString("ldap.maxWaitTime")
      Duration.fromScala(scala.concurrent.duration.Duration.apply(age))
    } catch {
      case _: Exception =>
        ApplicationLogger.info(
          "Property 'ldap.maxWaitTime' is missing or empty in rudder.configFile. Default to 30 seconds."
        )
        30.seconds
    }
  }
  val LDAP_MAX_CONNECTION_AGE:                Duration = {
    try {
      val age = config.getString("ldap.maxConnectionAge")
      Duration.fromScala(scala.concurrent.duration.Duration.apply(age))
    } catch {
      case _: ConfigException =>
        ApplicationLogger.info(
          "Property 'ldap.maxConnectionAge' is missing or empty in rudder.configFile. Default to 3O minutes."
        )
        30.minutes
    }
  }
  val LDAP_MIN_DISCONNECT_INTERVAL:           Duration = {
    try {
      val age = config.getString("ldap.minDisconnectInterval")
      Duration.fromScala(scala.concurrent.duration.Duration.apply(age))
    } catch {
      case _: ConfigException =>
        ApplicationLogger.info(
          "Property 'ldap.minDisconnectInterval' is missing or empty in rudder.configFile. Default to 5 seconds."
        )
        5.seconds
    }
  }

  val LDAP_CACHE_NODE_INFO_MIN_INTERVAL: Duration       = {
    val x = {
      try {
        config.getInt("ldap.nodeinfo.cache.min.interval")
      } catch {
        case ex: ConfigException =>
          ApplicationLogger.debug(
            "Property 'ldap.nodeinfo.cache.min.interval' is absent or empty in rudder.configFile. Default to 100 ms."
          )
          100
      }
    }
    if (x < 0) { // 0 is ok, it means "always check"
      100.millis
    } else {
      x.millis
    }
  }
  val RUDDER_DIR_BACKUP:                 Option[String] = {
    try {
      config.getString("rudder.dir.backup").trim match {
        case ""    => None
        case value => Some(value)
      }
    } catch {
      case ex: ConfigException => None
    }
  }
  val RUDDER_DIR_DEPENDENCIES:           String         = config.getString("rudder.dir.dependencies")
  val RUDDER_DIR_LOCK:                   String         = config.getString("rudder.dir.lock") // TODO no more used ?
  val RUDDER_DIR_SHARED_FILES_FOLDER:    String         = config.getString("rudder.dir.shared.files.folder")
  val RUDDER_WEBDAV_USER:                String         = config.getString("rudder.webdav.user")
  val RUDDER_WEBDAV_PASSWORD:            String         = config.getString("rudder.webdav.password");
  filteredPasswords += "rudder.webdav.password"
  val CFENGINE_POLICY_DISTRIBUTION_PORT: Int            = {
    try {
      config.getInt("rudder.policy.distribution.port.cfengine")
    } catch {
      case ex: ConfigException =>
        try {
          config.getInt("rudder.community.port") // for compat
        } catch {
          case ex: ConfigException =>
            ApplicationLogger.info(
              "Property 'rudder.policy.distribution.port.cfengine' is absent or empty in Rudder configuration file. Default to 5309"
            )
            5309
        }
    }
  }
  val HTTPS_POLICY_DISTRIBUTION_PORT:    Int            = {
    try {
      config.getInt("rudder.policy.distribution.port.https")
    } catch {
      case ex: ConfigException =>
        ApplicationLogger.info(
          "Property 'rudder.policy.distribution.port.https' is absent or empty in Rudder configuration file. Default to 443"
        )
        443
    }
  }

  val RUDDER_SERVER_CERTIFICATE_CONFIG: PolicyServerCertificateConfig = PolicyServerCertificateConfig(
    try {
      config
        .getString("rudder.server.certificate.additionalKeyHash")
        .split(";")
        .collect { case s if (s.strip().nonEmpty) => s.strip() }
        .toList
    } catch {
      case ex: ConfigException =>
        println(ex.getMessage)
        Nil
    },
    try {
      config.getString("rudder.server.certificate.ca.path")
    } catch {
      case _:  ConfigException => ""
    },
    try {
      config.getBoolean("rudder.server.certificate.validation")
    } catch {
      case _:  ConfigException => false
    },
    try {
      config.getBoolean("rudder.server.certificate.httpsOnly")
    } catch {
      case _:  ConfigException => false
    }
  )

  val POSTGRESQL_IS_LOCAL: Boolean = {
    try {
      config.getBoolean("rudder.postgresql.local")
    } catch {
      case ex: ConfigException => true
    }
  }

  val RUDDER_JDBC_DRIVER:        String = config.getString("rudder.jdbc.driver")
  val RUDDER_JDBC_URL:           String = config.getString("rudder.jdbc.url")
  val RUDDER_JDBC_USERNAME:      String = config.getString("rudder.jdbc.username")
  val RUDDER_JDBC_PASSWORD:      String = config.getString("rudder.jdbc.password");
  filteredPasswords += "rudder.jdbc.password"
  val RUDDER_JDBC_MAX_POOL_SIZE: Int    = config.getInt("rudder.jdbc.maxPoolSize")

  val RUDDER_JDBC_BATCH_MAX_SIZE: Int = {
    val x = {
      try {
        config.getInt("rudder.jdbc.batch.max.size")
      } catch {
        case ex: ConfigException =>
          ApplicationLogger.debug(
            "Property 'rudder.jdbc.batch.max.size' is absent or empty in rudder.configFile. Default to 500."
          )
          500
      }
    }
    if (x < 0) { // 0 is ok, it means "always check"
      500
    } else {
      x
    }
  }

  // `connectionTimeout` is the time hikari wait when there is no connection available or base down before telling
  // upward that there is no connection. It makes Rudder slow, and we prefer to be notified quickly that it was
  // impossible to get a connection. Must be >=250ms. Rudder knows how to handle that gracefully.
  val MIN_JDBC_GET_CONNECTION_TIMEOUT = 250.millis
  val JDBC_GET_CONNECTION_TIMEOUT: Duration = {
    try {
      val age = config.getString("rudder.jdbc.getConnectionTimeout")
      val a   = Duration.fromScala(scala.concurrent.duration.Duration.apply(age))
      if (a.toMillis < MIN_JDBC_GET_CONNECTION_TIMEOUT.toMillis) {
        ApplicationLogger.info(
          s"Property 'rudder.jdbc.getConnectionTimeout' must be greater than ${MIN_JDBC_GET_CONNECTION_TIMEOUT.toMillis} ms in rudder.configFile. Default to ${MIN_JDBC_GET_CONNECTION_TIMEOUT.toMillis} ms."
        )
        MIN_JDBC_GET_CONNECTION_TIMEOUT
      } else {
        a
      }
    } catch {
      case _: ConfigException =>
        ApplicationLogger.info(
          s"Property 'rudder.jdbc.getConnectionTimeout' is missing or empty in rudder.configFile. Default to ${MIN_JDBC_GET_CONNECTION_TIMEOUT.toMillis} ms."
        )
        MIN_JDBC_GET_CONNECTION_TIMEOUT
    }
  }

  val RUDDER_GIT_GC: Option[CronExpr] = (
    try {
      config.getString("rudder.git.gc")
    } catch {
      // missing key, perhaps due to migration, use default
      case ex: Exception => {
        val default = "0 42 3 * * ?"
        logger.info(s"`rudder.git.gc` property is absent, using default schedule: ${default}")
        default
      }
    }
  ).toOptCron match {
    case Left(err)  =>
      logger.error(s"Error when parsing cron for 'rudder.git.gc', it will be disabled: ${err.fullMsg}")
      None
    case Right(opt) => opt
  }

  val RUDDER_INVENTORIES_CLEAN_CRON: Option[CronExpr] = (
    try {
      config.getString("rudder.inventories.cleanup.old.files.cron")
    } catch {
      // missing key, perhaps due to migration, use default
      case ex: Exception => {
        val default = "0 32 3 * * ?"
        logger.info(s"`rudder.inventories.cleanup.old.files.cron` property is absent, using default schedule: ${default}")
        default
      }
    }
  ).toOptCron match {
    case Left(err)  =>
      logger.error(
        s"Error when parsing cron for 'rudder.inventories.cleanup.old.files.cron', it will be disabled: ${err.fullMsg}"
      )
      None
    case Right(opt) => opt
  }

  val RUDDER_INVENTORIES_CLEAN_AGE: Duration = {
    try {
      val age = config.getString("rudder.inventories.cleanup.old.files.age")
      Duration.fromScala(scala.concurrent.duration.Duration.apply(age))
    } catch {
      case ex: Exception => 30.days
    }
  }

  /*
   * Root directory for git config-repo et fact-repo.
   * We should homogeneize naming here, ie s/rudder.dir.gitRoot/rudder.dir.gitRootConfigRepo/
   */
  val RUDDER_GIT_ROOT_CONFIG_REPO: String = config.getString("rudder.dir.gitRoot")
  val RUDDER_GIT_ROOT_FACT_REPO:   String = {
    try {
      config.getString("rudder.dir.gitRootFactRepo")
    } catch {
      case ex: Exception => "/var/rudder/fact-repository"
    }
  }

  val RUDDER_GIT_FACT_WRITE_NODES:  Boolean = {
    try {
      config.getBoolean("rudder.facts.repo.writeNodeState")
    } catch {
      case ex: Exception => false
    }
  }
  val RUDDER_GIT_FACT_COMMIT_NODES: Boolean = {
    try {
      config.getBoolean("rudder.facts.repo.historizeNodeChange")
    } catch {
      case ex: Exception => false
    }
  }

  val RUDDER_DIR_TECHNIQUES:                        String = RUDDER_GIT_ROOT_CONFIG_REPO + "/techniques"
  val RUDDER_BATCH_DYNGROUP_UPDATEINTERVAL:         Int    = config.getInt("rudder.batch.dyngroup.updateInterval") // in minutes
  val RUDDER_BATCH_TECHNIQUELIBRARY_UPDATEINTERVAL: Int    =
    config.getInt("rudder.batch.techniqueLibrary.updateInterval") // in minutes
  val RUDDER_BATCH_REPORTSCLEANER_ARCHIVE_TTL: Int =
    config.getInt("rudder.batch.reportscleaner.archive.TTL") // AutomaticReportsCleaning.defaultArchiveTTL
  val RUDDER_BATCH_REPORTSCLEANER_DELETE_TTL: Int =
    config.getInt("rudder.batch.reportscleaner.delete.TTL") // AutomaticReportsCleaning.defaultDeleteTTL
  val RUDDER_BATCH_REPORTSCLEANER_COMPLIANCE_DELETE_TTL: Int =
    config.getInt("rudder.batch.reportscleaner.compliancelevels.delete.TTL") // AutomaticReportsCleaning.defaultDeleteTTL
  val RUDDER_BATCH_REPORTSCLEANER_LOG_DELETE_TTL: String = {
    (Try {
      config.getString("rudder.batch.reportsCleaner.deleteLogReport.TTL")
    } orElse Try {
      config.getString("rudder.batch.reportscleaner.deleteReportLog.TTL")
    }.map { res =>
      ApplicationLogger.warn(
        "Config property 'rudder.batch.reportscleaner.deleteReportLog.TTL' is deprecated, please rename it to 'rudder.batch.reportsCleaner.deleteLogReport.TTL'"
      )
      res
    }).getOrElse("2x")
  }
  val RUDDER_BATCH_REPORTSCLEANER_FREQUENCY:      String =
    config.getString("rudder.batch.reportscleaner.frequency") // AutomaticReportsCleaning.defaultDay
  val RUDDER_BATCH_DATABASECLEANER_RUNTIME_HOUR: Int =
    config.getInt("rudder.batch.databasecleaner.runtime.hour") // AutomaticReportsCleaning.defaultHour
  val RUDDER_BATCH_DATABASECLEANER_RUNTIME_MINUTE: Int =
    config.getInt("rudder.batch.databasecleaner.runtime.minute") // AutomaticReportsCleaning.defaultMinute
  val RUDDER_BATCH_DATABASECLEANER_RUNTIME_DAY: String = config.getString("rudder.batch.databasecleaner.runtime.day") // "sunday"
  val RUDDER_BATCH_REPORTS_LOGINTERVAL:         Int    = config.getInt("rudder.batch.reports.logInterval")            // 1 //one minute
  val RUDDER_TECHNIQUELIBRARY_GIT_REFS_PATH = "refs/heads/master"
  // THIS ONE IS STILL USED FOR USERS USING GIT REPLICATION
  val RUDDER_AUTOARCHIVEITEMS: Boolean = config.getBoolean("rudder.autoArchiveItems") // true

  val RUDDER_REPORTS_EXECUTION_INTERVAL: duration.Duration =
    config.getInt("rudder.batch.storeAgentRunTimes.updateInterval").seconds.asScala

  val HISTORY_INVENTORIES_ROOTDIR: String = config.getString("history.inventories.rootdir")

  val RUDDER_DEBUG_NODE_CONFIGURATION_PATH: String = config.getString("rudder.debug.nodeconfiguration.path")

  val RUDDER_BATCH_PURGE_DELETED_INVENTORIES: Int = {
    try {
      config.getInt("rudder.batch.purge.inventories.delete.TTL")
    } catch {
      case ex: ConfigException =>
        ApplicationLogger.info(
          "Property 'rudder.batch.purge.inventories.delete.TTL' is absent or empty in rudder.configFile. Default to 7 days."
        )
        7
    }
  }

  val RUDDER_BCRYPT_COST: Int = {
    val defaultValue = PasswordEncoderType.BCRYPT.defaultCost
    try {
      config.getInt("rudder.bcrypt.cost")
    } catch {
      case ex: ConfigException =>
        ApplicationLogger.debug(
          s"Property 'rudder.bcrypt.cost' is absent or empty in rudder.configFile. Default cost to $defaultValue."
        )
        defaultValue
    }
  }

  val RUDDER_ARGON2_MEMORY: Argon2Memory = {
    val defaultValue = PasswordEncoderType.ARGON2ID.defaultMemory
    val minimumValue = PasswordEncoderType.ARGON2ID.minimumMemory
    try {
      val value = config.getInt("rudder.argon2.memory")
      // There is a high likelihood of units confusion.
      if (value < minimumValue.toInt) {
        ApplicationLogger.warn(
          s"Property 'rudder.argon2.memory' has a value lower than $minimumValue KiB. This is likely a configuration mistake, using $defaultValue KiB instead"
        )
        defaultValue
      } else {
        Argon2Memory(value)
      }
    } catch {
      case ex: ConfigException =>
        ApplicationLogger.debug(
          s"Property 'rudder.argon2.memory' is absent or empty in rudder.configFile. Default memory to $defaultValue bytes."
        )
        defaultValue
    }
  }

  val RUDDER_ARGON2_ITERATIONS: Argon2Iterations = {
    val defaultValue = PasswordEncoderType.ARGON2ID.defaultIterations
    try {
      Argon2Iterations(config.getInt("rudder.argon2.iterations"))
    } catch {
      case ex: ConfigException =>
        ApplicationLogger.debug(
          s"Property 'rudder.argon2.iterations' is absent or empty in rudder.configFile. Default iterations to $defaultValue."
        )
        defaultValue
    }
  }

  val RUDDER_ARGON2_PARALLELISM: Argon2Parallelism = {
    val defaultValue = PasswordEncoderType.ARGON2ID.defaultParallelism
    try {
      Argon2Parallelism(config.getInt("rudder.argon2.parallelism"))
    } catch {
      case ex: ConfigException =>
        ApplicationLogger.debug(
          s"Property 'rudder.argon2.parallelism' is absent or empty in rudder.configFile. Default parallelism to $defaultValue."
        )
        defaultValue
    }
  }

  val RUDDER_ARGON2_PARAMS = Argon2EncoderParams(
    RUDDER_ARGON2_MEMORY,
    RUDDER_ARGON2_ITERATIONS,
    RUDDER_ARGON2_PARALLELISM
  )

  val RUDDER_BATCH_PURGE_DELETED_INVENTORIES_INTERVAL: Int = {
    try {
      config.getInt("rudder.batch.purge.inventories.delete.interval")
    } catch {
      case ex: ConfigException =>
        ApplicationLogger.info(
          "Property 'rudder.batch.purge.inventories.delete.interval' is absent or empty in rudder.configFile. Default to 24 hours."
        )
        24
    }
  }

  val RUDDER_BATCH_DELETE_SOFTWARE_INTERVAL: Int = {
    try {
      config.getInt("rudder.batch.delete.software.interval")
    } catch {
      case ex: ConfigException =>
        ApplicationLogger.info(
          "Property 'rudder.batch.delete.software.interval' is absent or empty in rudder.configFile. Default to 24 hours."
        )
        24
    }
  }

  val RUDDER_BATCH_CHECK_NODE_CACHE_INTERVAL: Duration = {
    try {
      Duration.fromScala(scala.concurrent.duration.Duration(config.getString("rudder.batch.check.node.cache.interval")))
    } catch {
      case ex: Exception =>
        ApplicationLogger.info(
          "Property 'rudder.batch.check.node.cache.interval' is absent or empty in rudder.configFile. Default to '15 s'."
        )
        Duration(15, TimeUnit.SECONDS)
    }
  }
  val RUDDER_GROUP_OWNER_CONFIG_REPO:         String   = {
    try {
      config.getString("rudder.config.repo.new.file.group.owner")
    } catch {
      case ex: Exception =>
        ApplicationLogger.info(
          "Property 'rudder.config.repo.new.file.group.owner' is absent or empty in rudder.configFile. Default to 'rudder'."
        )
        "rudder"
    }
  }
  val RUDDER_GROUP_OWNER_GENERATED_POLICIES:  String   = {
    try {
      config.getString("rudder.generated.policies.group.owner")
    } catch {
      case ex: Exception =>
        ApplicationLogger.info(
          "Property 'rudder.generated.policies.group.owner' is absent or empty in rudder.configFile. Default to 'rudder-policy-reader'."
        )
        "rudder-policy-reader"
    }
  }

  val RUDDER_RELAY_API: String = config.getString("rudder.server.relay.api")

  val RUDDER_SERVER_HSTS: Boolean = {
    try {
      config.getBoolean("rudder.server.hsts")
    } catch {
      // by default, if property is missing
      case ex: ConfigException => false
    }
  }

  val RUDDER_SERVER_HSTS_SUBDOMAINS: Boolean = {
    try {
      config.getBoolean("rudder.server.hstsIncludeSubDomains")
    } catch {
      // by default, if property is missing
      case ex: ConfigException => false
    }
  }

  val RUDDER_RELAY_RELOAD: String = {
    try {
      config.getString("rudder.relayd.reload")
    } catch {
      // by default, if property is missing
      case ex: ConfigException => "/opt/rudder/bin/rudder relay reload -p"
    }
  }

  // The base directory for hooks. I'm not sure it needs to be configurable
  // as we only use it in generation.
  val HOOKS_D                     = "/opt/rudder/etc/hooks.d"
  val UPDATED_NODE_IDS_PATH       = "/var/rudder/policy-generation-info/last-updated-nodeids"
  val GENERATION_FAILURE_MSG_PATH = "/var/rudder/policy-generation-info/last-failure-message"
  /*
   * This is a parameter for compatibility mode for Rudder 5.0.
   * It should be removed in 5.1 and up.
   */
  val UPDATED_NODE_IDS_COMPABILITY: Option[Boolean] = {
    try {
      Some(config.getBoolean("rudder.hooks.policy-generation-finished.nodeids.compability"))
    } catch {
      case ex: ConfigException => None
    }
  }

  val HOOKS_IGNORE_SUFFIXES: List[String] = RudderProperties.splitProperty(config.getString("rudder.hooks.ignore-suffixes"))

  val logentries                  = "logentries.xml"
  val prettyPrinter               = new RudderPrettyPrinter(Int.MaxValue, 2)
  val userLibraryDirectoryName    = "directives"
  val groupLibraryDirectoryName   = "groups"
  val rulesDirectoryName          = "rules"
  val ruleCategoriesDirectoryName = "ruleCategories"
  val parametersDirectoryName     = "parameters"

  // properties from version.properties file,
  val (
    rudderFullVersion,
    builtTimestamp
  ) = {
    val p = new java.util.Properties
    p.load(this.getClass.getClassLoader.getResourceAsStream("version.properties"))
    (
      p.getProperty("rudder-full-version"),
      p.getProperty("build-timestamp")
    )
  }

  // don't parse some elements in inventories: processes
  val INVENTORIES_IGNORE_PROCESSES: Boolean = {
    try {
      config.getBoolean("inventory.parse.ignore.processes")
    } catch {
      case ex: ConfigException => false
    }
  }

  val INVENTORIES_MAX_BEFORE_NOW: Duration = {
    try {
      Duration.fromScala(
        scala.concurrent.duration.Duration.apply(
          config.getString(
            "inventories.reject.maxAgeBeforeNow"
          )
        )
      )
    } catch {
      case ex: Exception =>
        ApplicationLogger.info(
          s"Error when reading key: 'inventories.reject.maxAgeBeforeNow', defaulting to 2 days: ${ex.getMessage}"
        )
        2.days
    }
  }

  val INVENTORIES_MAX_AFTER_NOW: Duration = {
    try {
      Duration.fromScala(
        scala.concurrent.duration.Duration.apply(
          config.getString(
            "inventories.reject.maxAgeAfterNow"
          )
        )
      )
    } catch {
      case ex: Exception =>
        ApplicationLogger.info(
          s"Error when reading key: 'inventories.reject.maxAgeAfterNow', defaulting to 12h: ${ex.getMessage}"
        )
        12.hours
    }
  }

  // the limit above which processes need an individual LDAP write request
  val INVENTORIES_THRESHOLD_PROCESSES_ISOLATED_WRITE: Int = {
    try {
      config.getInt("inventory.threshold.processes.isolatedWrite")
    } catch {
      case ex: ConfigException => 1
    }
  }

  // the number of inventories parsed and saved in parallel.
  // That number should be small, LDAP doesn't like lots of write
  // Minimum 1, 1x mean "0.5x number of cores"
  val MAX_PARSE_PARALLEL: String = {
    try {
      config.getString("inventory.parse.parallelization")
    } catch {
      case ex: ConfigException => "2"
    }
  }

  val INVENTORY_ROOT_DIR: String = {
    try {
      config.getString("inventories.root.directory")
    } catch {
      case ex: ConfigException => "/var/rudder/inventories"
    }
  }

  val INVENTORY_DIR_INCOMING: String = INVENTORY_ROOT_DIR + "/incoming"
  val INVENTORY_DIR_FAILED:   String = INVENTORY_ROOT_DIR + "/failed"
  val INVENTORY_DIR_RECEIVED: String = INVENTORY_ROOT_DIR + "/received"
  val INVENTORY_DIR_UPDATE:   String = INVENTORY_ROOT_DIR + "/accepted-nodes-updates"

  val WATCHER_ENABLE: Boolean = {
    try {
      config.getBoolean("inventories.watcher.enable")
    } catch {
      case ex: ConfigException => true
    }
  }

  val WATCHER_GARBAGE_OLD_INVENTORIES_PERIOD: Duration = {
    try {
      Duration.fromScala(
        scala.concurrent.duration.Duration.apply(
          config.getString(
            "inventories.watcher.period.garbage.old"
          )
        )
      )
    } catch {
      case ex: Exception => 5.minutes
    }
  }

  val WATCHER_DELETE_OLD_INVENTORIES_AGE: Duration = {
    try {
      Duration.fromScala(
        scala.concurrent.duration.Duration.apply(config.getString("inventories.watcher.max.age.before.deletion"))
      )
    } catch {
      case _: Exception => 3.days
    }
  }

  val METRICS_NODES_DIRECTORY_GIT_ROOT = "/var/rudder/metrics/nodes"

  val METRICS_NODES_MIN_PERIOD: Duration = {
    try {
      Duration.fromScala(scala.concurrent.duration.Duration(config.getString("metrics.node.scheduler.period.min")))
    } catch {
      case ex: ConfigException       => // default
        15.minutes
      case ex: NumberFormatException =>
        ApplicationLogger.error(
          s"Error when reading key: 'metrics.node.scheduler.period.min', defaulting to 15min: ${ex.getMessage}"
        )
        15.minutes
    }
  }
  val METRICS_NODES_MAX_PERIOD: Duration = {
    try {
      Duration.fromScala(scala.concurrent.duration.Duration(config.getString("metrics.node.scheduler.period.max")))
    } catch {
      case ex: ConfigException       => // default
        4.hours
      case ex: NumberFormatException =>
        ApplicationLogger.error(
          s"Error when reading key: 'metrics.node.scheduler.period.max', defaulting to 4h: ${ex.getMessage}"
        )
        4.hours
    }
  }
  if (METRICS_NODES_MAX_PERIOD <= METRICS_NODES_MIN_PERIOD) {
    throw new IllegalArgumentException(
      s"Value for 'metrics.node.scheduler.period.max' (${METRICS_NODES_MAX_PERIOD.render}) must " +
      s"be bigger than for 'metrics.node.scheduler.period.max' (${METRICS_NODES_MIN_PERIOD.render})"
    )
  }

  val RUDDER_HEALTHCHECK_PERIOD: Duration = {
    try {
      Duration.fromScala(scala.concurrent.duration.Duration(config.getString("metrics.healthcheck.scheduler.period")))
    } catch {
      case ex: ConfigException       =>
        ApplicationLogger.info(
          "Property 'metrics.healthcheck.scheduler.period' is absent or empty in rudder.configFile. Default to 6 hours."
        )
        6.hours
      case ex: NumberFormatException =>
        ApplicationLogger.error(
          s"Error when reading key: 'metrics.node.scheduler.period.max', defaulting to 6 hours: ${ex.getMessage}"
        )
        6.hours
    }
  }

  // Store it an a Box as it's only used in Lift
  val AUTH_IDLE_TIMEOUT: Box[Duration] = {
    try {
      val timeout = config.getString("rudder.auth.idle-timeout")
      if (timeout.isEmpty) {
        Empty
      } else {
        Full(Duration.fromScala(scala.concurrent.duration.Duration.apply(timeout)))
      }
    } catch {
      case ex: Exception => Full(30.minutes)
    }
  }

  val RUDDERC_CMD: String = {
    try {
      config.getString("rudder.technique.compiler.rudderc.cmd")
    } catch {
      case ex: ConfigException => "/opt/rudder/bin/rudderc"
    }
  }

  val RUDDER_PACKAGE_CMD: String = {
    try {
      config.getString("rudder.package.cmd")
    } catch {
      case ex: ConfigException => "/opt/rudder/bin/rudder package"
    }
  }

  // Comes with the rudder-server packages
  val GENERIC_METHODS_SYSTEM_LIB: String = {
    try {
      config.getString("rudder.technique.methods.systemLib")
    } catch {
      case ex: ConfigException => "/usr/share/ncf/tree/30_generic_methods"
    }
  }

  // User-defined methods + plugin methods (including windows)
  val GENERIC_METHODS_LOCAL_LIB: String = {
    try {
      config.getString("rudder.technique.methods.localLib")
    } catch {
      case ex: ConfigException => "/var/rudder/configuration-repository/ncf/30_generic_methods"
    }
  }

  /*
   * inventory accept/refuse history information clean-up.
   * These properties manage how long we keep inventory that we store when a node is accepted.
   * There is:
   * - one absolute time: duration we keep info even if the node is still present in rudder.
   * - a time after deletion: how many time we keep the inventory info after node was refused / deleted.
   */

  // how long we keep accept/refuse inventory (even if the node is still present). O means "forever if the node is node deleted"
  val KEEP_ACCEPTATION_NODE_FACT_DURATION: Duration = {
    val configKey = "rudder.inventories.pendingChoiceHistoryCleanupLatency.acceptedNode"
    try {
      Duration.fromScala(
        scala.concurrent.duration.Duration(config.getString(configKey))
      )
    } catch {
      case ex: ConfigException       => 0.days // forever
      case ex: NumberFormatException =>
        throw InitConfigValueError(
          configKey,
          "value is not formatted as a long digit and time unit (ms, s, m, h, d), example: 5 days or 5d. Leave it empty to keep forever",
          Some(ex)
        )
    }
  }

  /*
   * Duration for which a node which was refused/deleted will have its nodefact
   * available (and so viewable in the history tab of pending node).
   * 0 means "delete immediately when node is deleted or refused"
   */
  val KEEP_DELETED_NODE_FACT_DURATION: Duration = {
    val configKey = "rudder.inventories.pendingChoiceHistoryCleanupLatency.deletedNode"
    try {
      Duration.fromScala(
        scala.concurrent.duration.Duration(
          config.getString(configKey)
        )
      )
    } catch {
      case ex: ConfigException       => 30.days
      case ex: NumberFormatException =>
        throw InitConfigValueError(
          configKey,
          "value is not formatted as a long digit and time unit (ms, s, m, h, d), example: 5 days or 5d. Leave it empty to keep forever",
          Some(ex)
        )
    }
  }

  // user clean-up
  val RUDDER_USERS_CLEAN_CRON:               Option[CronExpr] = (
    try {
      config.getString("rudder.users.cleanup.cron")
    } catch {
      // missing key, perhaps due to migration, use default
      case ex: Exception => {
        val default = "0 17 1 * * ?"
        logger.info(s"`rudder.users.cleanup.cron` property is absent, using default schedule: ${default}")
        default
      }
    }
  ).toOptCron match {
    case Left(err)  =>
      logger.error(
        s"Error when parsing cron for 'rudder.users.cleanup.cron', it will be disabled: ${err.fullMsg}"
      )
      None
    case Right(opt) => opt
  }
  val RUDDER_USERS_CLEAN_LAST_LOGIN_DISABLE: Duration         =
    parseDuration("rudder.users.cleanup.account.disableAfterLastLogin", 180.days)
  val RUDDER_USERS_CLEAN_LAST_LOGIN_DELETE:  Duration         =
    parseDuration("rudder.users.cleanup.account.deleteAfterLastLogin", Duration.Infinity)
  val RUDDER_USERS_CLEAN_DELETED_PURGE:      Duration         = parseDuration("rudder.users.cleanup.purgeDeletedAfter", 30.days)
  val RUDDER_USERS_CLEAN_SESSIONS_PURGE:     Duration         = parseDuration("rudder.users.cleanup.sessions.purgeAfter", 30.days)

  def parseDuration(propName: String, default: Duration): Duration = {
    try {
      val configValue = config.getString(propName)
      configValue.toLowerCase match {
        case "never" => Duration.Infinity
        case _       => Duration.fromScala(scala.concurrent.duration.Duration(configValue))
      }
    } catch {
      case ex: ConfigException       => // default
        ApplicationLogger.info(s"Key '${propName}' is absent, defaulting to: ${default}")
        default
      case ex: NumberFormatException =>
        ApplicationLogger.error(
          s"Error when reading key: '${propName}', defaulting to ${default}: ${ex.getMessage}"
        )
        default
    }
  }

}

/**
 * Static initialization of Rudder services.
 * This is not a cake-pattern, just a plain object with load of lazy vals.
 */
object RudderConfig extends Loggable {

  ApplicationLogger.info(
    s"Starting Rudder ${RudderParsedProperties.rudderFullVersion} web application [build timestamp: ${RudderParsedProperties.builtTimestamp}]"
  )

  // For compatibility. We keep properties that we accessed by other services.
  // They should be class parameters.
  def rudderFullVersion                            = RudderParsedProperties.rudderFullVersion
  def RUDDER_SERVER_HSTS                           = RudderParsedProperties.RUDDER_SERVER_HSTS
  def RUDDER_SERVER_HSTS_SUBDOMAINS                = RudderParsedProperties.RUDDER_SERVER_HSTS_SUBDOMAINS
  def AUTH_IDLE_TIMEOUT                            = RudderParsedProperties.AUTH_IDLE_TIMEOUT
  def WATCHER_ENABLE                               = RudderParsedProperties.WATCHER_ENABLE
  def RUDDER_BATCH_DYNGROUP_UPDATEINTERVAL         = RudderParsedProperties.RUDDER_BATCH_DYNGROUP_UPDATEINTERVAL
  def RUDDER_GIT_ROOT_CONFIG_REPO                  = RudderParsedProperties.RUDDER_GIT_ROOT_CONFIG_REPO
  def RUDDER_BCRYPT_COST                           = RudderParsedProperties.RUDDER_BCRYPT_COST
  def RUDDER_ARGON2_PARAMS                         = RudderParsedProperties.RUDDER_ARGON2_PARAMS
  def RUDDER_BATCH_TECHNIQUELIBRARY_UPDATEINTERVAL = RudderParsedProperties.RUDDER_BATCH_TECHNIQUELIBRARY_UPDATEINTERVAL

  //
  // Theses services can be called from the outer world
  // They must be typed with there abstract interface, as
  // such service must not expose implementation details
  //

  // we need that to be the first thing, and take care of Exception so that the error is
  // human understandable when the directory is not up
  val rci: RudderServiceApi = RudderConfigInit.init()

  val ApiVersions:                         List[ApiVersion]                         = rci.apiVersions
  val acceptedNodeQueryProcessor:          QueryProcessor                           = rci.acceptedNodeQueryProcessor
  val acceptedNodesDit:                    InventoryDit                             = rci.acceptedNodesDit
  val agentRegister:                       AgentRegister                            = rci.agentRegister
  val aggregateReportScheduler:            FindNewReportsExecution                  = rci.aggregateReportScheduler
  val apiAuthorizationLevelService:        DefaultApiAuthorizationLevel             = rci.apiAuthorizationLevelService
  val apiDispatcher:                       RudderEndpointDispatcher                 = rci.apiDispatcher
  val asyncComplianceService:              AsyncComplianceService                   = rci.asyncComplianceService
  val asyncDeploymentAgent:                AsyncDeploymentActor                     = rci.asyncDeploymentAgent
  val asyncWorkflowInfo:                   AsyncWorkflowInfo                        = rci.asyncWorkflowInfo
  val authenticationProviders:             AuthBackendProvidersManager              = rci.authenticationProviders
  val authorizationApiMapping:             ExtensibleAuthorizationApiMapping        = rci.authorizationApiMapping
  val automaticReportLogger:               AutomaticReportLogger                    = rci.automaticReportLogger
  val automaticReportsCleaning:            AutomaticReportsCleaning                 = rci.automaticReportsCleaning
  val campaignEventRepo:                   CampaignEventRepositoryImpl              = rci.campaignEventRepo
  val campaignRepo:                        CampaignRepository                       = rci.campaignRepo
  val campaignSerializer:                  CampaignSerializer                       = rci.campaignSerializer
  val categoryHierarchyDisplayer:          CategoryHierarchyDisplayer               = rci.categoryHierarchyDisplayer
  val changeRequestChangesSerialisation:   ChangeRequestChangesSerialisation        = rci.changeRequestChangesSerialisation
  val changeRequestChangesUnserialisation: ChangeRequestChangesUnserialisation      = rci.changeRequestChangesUnserialisation
  val changeRequestEventLogService:        ChangeRequestEventLogService             = rci.changeRequestEventLogService
  val checkTechniqueLibrary:               CheckTechniqueLibrary                    = rci.checkTechniqueLibrary
  val clearCacheService:                   ClearCacheService                        = rci.clearCacheService
  val cmdbQueryParser:                     CmdbQueryParser                          = rci.cmdbQueryParser
  val commitAndDeployChangeRequest:        CommitAndDeployChangeRequestService      = rci.commitAndDeployChangeRequest
  val complianceService:                   ComplianceAPIService                     = rci.complianceService
  val configService:                       ReadConfigService & UpdateConfigService  = rci.configService
  val configurationRepository:             ConfigurationRepository                  = rci.configurationRepository
  val databaseManager:                     DatabaseManager                          = rci.databaseManager
  val debugScript:                         DebugInfoService                         = rci.debugScript
  val dependencyAndDeletionService:        DependencyAndDeletionService             = rci.dependencyAndDeletionService
  val deploymentService:                   PromiseGeneration_Hooks                  = rci.deploymentService
  val diffDisplayer:                       DiffDisplayer                            = rci.diffDisplayer
  val diffService:                         DiffService                              = rci.diffService
  val directiveEditorService:              DirectiveEditorService                   = rci.directiveEditorService
  val ditQueryData:                        DitQueryData                             = rci.ditQueryData
  val doobie:                              Doobie                                   = rci.doobie
  val dynGroupService:                     DynGroupService                          = rci.dynGroupService
  val eventListDisplayer:                  EventListDisplayer                       = rci.eventListDisplayer
  val eventLogApi:                         EventLogAPI                              = rci.eventLogApi
  val systemApiService:                    SystemApiService11                       = rci.systemApiService
  val eventLogDeploymentService:           EventLogDeploymentService                = rci.eventLogDeploymentService
  val eventLogDetailsService:              EventLogDetailsService                   = rci.eventLogDetailsService
  val eventLogRepository:                  EventLogRepository                       = rci.eventLogRepository
  val findExpectedReportRepository:        FindExpectedReportRepository             = rci.findExpectedReportRepository
  val gitModificationRepository:           GitModificationRepository                = rci.gitModificationRepository
  val gitRepo:                             GitRepositoryProvider                    = rci.gitRepo
  val gitRevisionProvider:                 GitRevisionProvider                      = rci.gitRevisionProvider
  val healthcheckNotificationService:      HealthcheckNotificationService           = rci.healthcheckNotificationService
  val historizeNodeCountBatch:             IOResult[Unit]                           = rci.historizeNodeCountBatch
  val instanceIdService:                   InstanceIdService                        = rci.instanceIdService
  val interpolationCompiler:               InterpolatedValueCompilerImpl            = rci.interpolationCompiler
  val inventoryEventLogService:            InventoryEventLogService                 = rci.inventoryEventLogService
  val inventoryHistoryJdbcRepository:      InventoryHistoryJdbcRepository           = rci.inventoryHistoryJdbcRepository
  val inventoryWatcher:                    InventoryFileWatcher                     = rci.inventoryWatcher
  val itemArchiveManager:                  ItemArchiveManager                       = rci.itemArchiveManager
  val jsTreeUtilService:                   JsTreeUtilService                        = rci.jsTreeUtilService
  val jsonPluginDefinition:                ReadPluginPackageInfo                    = rci.jsonPluginDefinition
  val jsonReportsAnalyzer:                 JSONReportsAnalyser                      = rci.jsonReportsAnalyzer
  val linkUtil:                            LinkUtil                                 = rci.linkUtil
  val logDisplayer:                        LogDisplayer                             = rci.logDisplayer
  val mainCampaignService:                 MainCampaignService                      = rci.mainCampaignService
  val ncfTechniqueReader:                  ncf.EditorTechniqueReader                = rci.ncfTechniqueReader
  val newNodeManager:                      NewNodeManager                           = rci.newNodeManager
  val nodeDit:                             NodeDit                                  = rci.nodeDit
  val nodeFactRepository:                  NodeFactRepository                       = rci.nodeFactRepository
  val nodeGrid:                            NodeGrid                                 = rci.nodeGrid
  val pendingNodeCheckGroup:               CheckPendingNodeInDynGroups              = rci.pendingNodeCheckGroup
  val pendingNodesDit:                     InventoryDit                             = rci.pendingNodesDit
  val personIdentService:                  PersonIdentService                       = rci.personIdentService
  val pluginSettingsService:               PluginSettingsService                    = rci.pluginSettingsService
  val policyGenerationBootGuard:           zio.Promise[Nothing, Unit]               = rci.policyGenerationBootGuard
  val policyServerManagementService:       PolicyServerManagementService            = rci.policyServerManagementService
  val propertyEngineService:               PropertyEngineService                    = rci.propertyEngineService
  val propertiesRepository:                PropertiesRepository                     = rci.propertiesRepository
  val propertiesService:                   NodePropertiesService                    = rci.propertiesService
  val purgeDeletedInventories:             PurgeDeletedInventories                  = rci.purgeDeletedInventories
  val purgeUnreferencedSoftwares:          PurgeUnreferencedSoftwares               = rci.purgeUnreferencedSoftwares
  val readOnlySoftwareDAO:                 ReadOnlySoftwareDAO                      = rci.readOnlySoftwareDAO
  val recentChangesService:                NodeChangesService                       = rci.recentChangesService
  val removeNodeService:                   RemoveNodeService                        = rci.removeNodeService
  val reportDisplayer:                     ReportDisplayer                          = rci.reportDisplayer
  val reportingService:                    ReportingService                         = rci.reportingService
  val reportsRepository:                   ReportsRepository                        = rci.reportsRepository
  val restCompletion:                      RestCompletion                           = rci.restCompletion
  val restQuicksearch:                     RestQuicksearch                          = rci.restQuicksearch
  val roleApiMapping:                      RoleApiMapping                           = rci.roleApiMapping
  val roAgentRunsRepository:               RoReportsExecutionRepository             = rci.roAgentRunsRepository
  val roApiAccountRepository:              RoApiAccountRepository                   = rci.roApiAccountRepository
  val roDirectiveRepository:               RoDirectiveRepository                    = rci.roDirectiveRepository
  val roLDAPConnectionProvider:            LDAPConnectionProvider[RoLDAPConnection] = rci.roLDAPConnectionProvider
  val roLDAPParameterRepository:           RoLDAPParameterRepository                = rci.roLDAPParameterRepository
  val woLDAPParameterRepository:           WoLDAPParameterRepository                = rci.woLDAPParameterRepository
  val roNodeGroupRepository:               RoNodeGroupRepository                    = rci.roNodeGroupRepository
  val roParameterService:                  RoParameterService                       = rci.roParameterService
  val roRuleCategoryRepository:            RoRuleCategoryRepository                 = rci.roRuleCategoryRepository
  val roRuleRepository:                    RoRuleRepository                         = rci.roRuleRepository
  val rudderApi:                           LiftHandler                              = rci.rudderApi
  val rudderDit:                           RudderDit                                = rci.rudderDit
  val rudderUserListProvider:              FileUserDetailListProvider               = rci.rudderUserListProvider
  val ruleApplicationStatus:               RuleApplicationStatusService             = rci.ruleApplicationStatus
  val ruleCategoryService:                 RuleCategoryService                      = rci.ruleCategoryService
  val rwLdap:                              LDAPConnectionProvider[RwLDAPConnection] = rci.rwLdap
  val secretEventLogService:               SecretEventLogService                    = rci.secretEventLogService
  val sharedFileApi:                       SharedFilesAPI                           = rci.sharedFileApi
  val snippetExtensionRegister:            SnippetExtensionRegister                 = rci.snippetExtensionRegister
  val srvGrid:                             SrvGrid                                  = rci.srvGrid
  val stringUuidGenerator:                 StringUuidGenerator                      = rci.stringUuidGenerator
  val techniqueRepository:                 TechniqueRepository                      = rci.techniqueRepository
  val techniqueArchiver:                   TechniqueArchiver & GitItemRepository    = rci.techniqueArchiver
  val techniqueCompilationStatusService:   TechniqueCompilationStatusSyncService    = rci.techniqueCompilationStatusService
  val tenantService:                       TenantService                            = rci.tenantService
  val tokenGenerator:                      TokenGeneratorImpl                       = rci.tokenGenerator
  val updateDynamicGroups:                 UpdateDynamicGroups                      = rci.updateDynamicGroups
  val updateDynamicGroupsService:          DynGroupUpdaterService                   = rci.updateDynamicGroupsService
  val updateTechniqueLibrary:              UpdateTechniqueLibrary                   = rci.updateTechniqueLibrary
  val userPropertyService:                 UserPropertyService                      = rci.userPropertyService
  val userRepository:                      UserRepository                           = rci.userRepository
  val userService:                         UserService                              = rci.userService
  val woApiAccountRepository:              WoApiAccountRepository                   = rci.woApiAccountRepository
  val woDirectiveRepository:               WoDirectiveRepository                    = rci.woDirectiveRepository
  val woNodeGroupRepository:               WoNodeGroupRepository                    = rci.woNodeGroupRepository
  val woRuleCategoryRepository:            WoRuleCategoryRepository                 = rci.woRuleCategoryRepository
  val woRuleRepository:                    WoRuleRepository                         = rci.woRuleRepository
  val workflowEventLogService:             WorkflowEventLogService                  = rci.workflowEventLogService
  val workflowLevelService:                DefaultWorkflowLevel                     = rci.workflowLevelService
  val systemInfoService:                   SystemInfoService                        = rci.systemInfoService

  /**
   * A method to call to force initialisation of all object and services.
   * This is a good place to check boottime things, and throws
   * "application broken - can not start" exception
   *
   * Important: if that method is not called, RudderConfig will be
   * lazy and will only be initialised on the first call to one
   * of its (public) methods.
   */
  def init(): IO[SystemError, Unit] = {

    IOResult.attempt {

      RudderParsedProperties.logRudderParsedProperties()
      ////////// bootstraps checks //////////
      // they must be out of Lift boot() because that method
      // is encapsulated in a try/catch ( see net.liftweb.http.provider.HTTPProvider.bootLift )
      rci.allBootstrapChecks.checks()
    }
  }

  def postPluginInitActions: Unit = {
    // todo: scheduler interval should be a property
    ZioRuntime.unsafeRun(
      ZIO.collectAllParDiscard(
        List(
          jsonReportsAnalyzer.start(5.seconds).forkDaemon.provideLayer(ZioRuntime.layers),
          MainCampaignService.start(mainCampaignService),
          rci.scoreService.clean()
        )
      )
    )
  }

}

/*
 * A case class holder used to transfer services from the init method in RudderConfigInit
 * to RudderConfig
 */
case class RudderServiceApi(
    roLDAPConnectionProvider:            LDAPConnectionProvider[RoLDAPConnection],
    pendingNodesDit:                     InventoryDit,
    acceptedNodesDit:                    InventoryDit,
    nodeDit:                             NodeDit,
    rudderDit:                           RudderDit,
    roRuleRepository:                    RoRuleRepository,
    woRuleRepository:                    WoRuleRepository,
    roNodeGroupRepository:               RoNodeGroupRepository,
    woNodeGroupRepository:               WoNodeGroupRepository,
    techniqueRepository:                 TechniqueRepository,
    techniqueArchiver:                   TechniqueArchiver & GitItemRepository,
    updateTechniqueLibrary:              UpdateTechniqueLibrary,
    roDirectiveRepository:               RoDirectiveRepository,
    woDirectiveRepository:               WoDirectiveRepository,
    readOnlySoftwareDAO:                 ReadOnlySoftwareDAO,
    eventLogRepository:                  EventLogRepository,
    eventLogDetailsService:              EventLogDetailsService,
    reportingService:                    ReportingService,
    complianceService:                   ComplianceAPIService,
    asyncComplianceService:              AsyncComplianceService,
    debugScript:                         DebugInfoService,
    cmdbQueryParser:                     CmdbQueryParser,
    inventoryHistoryJdbcRepository:      InventoryHistoryJdbcRepository,
    inventoryEventLogService:            InventoryEventLogService,
    ruleApplicationStatus:               RuleApplicationStatusService,
    propertyEngineService:               PropertyEngineService,
    newNodeManager:                      NewNodeManager,
    nodeGrid:                            NodeGrid,
    jsTreeUtilService:                   JsTreeUtilService,
    directiveEditorService:              DirectiveEditorService,
    userPropertyService:                 UserPropertyService,
    eventListDisplayer:                  EventListDisplayer,
    asyncDeploymentAgent:                AsyncDeploymentActor,
    policyServerManagementService:       PolicyServerManagementService,
    updateDynamicGroupsService:          DynGroupUpdaterService,
    updateDynamicGroups:                 UpdateDynamicGroups,
    purgeDeletedInventories:             PurgeDeletedInventories,
    purgeUnreferencedSoftwares:          PurgeUnreferencedSoftwares,
    databaseManager:                     DatabaseManager,
    automaticReportsCleaning:            AutomaticReportsCleaning,
    checkTechniqueLibrary:               CheckTechniqueLibrary,
    automaticReportLogger:               AutomaticReportLogger,
    removeNodeService:                   RemoveNodeService,
    reportDisplayer:                     ReportDisplayer,
    dependencyAndDeletionService:        DependencyAndDeletionService,
    itemArchiveManager:                  ItemArchiveManager,
    personIdentService:                  PersonIdentService,
    gitRevisionProvider:                 GitRevisionProvider,
    logDisplayer:                        LogDisplayer,
    acceptedNodeQueryProcessor:          QueryProcessor,
    categoryHierarchyDisplayer:          CategoryHierarchyDisplayer,
    dynGroupService:                     DynGroupService,
    ditQueryData:                        DitQueryData,
    reportsRepository:                   ReportsRepository,
    eventLogDeploymentService:           EventLogDeploymentService,
    srvGrid:                             SrvGrid,
    findExpectedReportRepository:        FindExpectedReportRepository,
    roApiAccountRepository:              RoApiAccountRepository,
    woApiAccountRepository:              WoApiAccountRepository,
    roAgentRunsRepository:               RoReportsExecutionRepository,
    pendingNodeCheckGroup:               CheckPendingNodeInDynGroups,
    allBootstrapChecks:                  BootstrapChecks,
    authenticationProviders:             AuthBackendProvidersManager,
    rudderUserListProvider:              FileUserDetailListProvider,
    restQuicksearch:                     RestQuicksearch,
    restCompletion:                      RestCompletion,
    sharedFileApi:                       SharedFilesAPI,
    eventLogApi:                         EventLogAPI,
    systemApiService:                    SystemApiService11,
    stringUuidGenerator:                 StringUuidGenerator,
    inventoryWatcher:                    InventoryFileWatcher,
    configService:                       ReadConfigService & UpdateConfigService,
    historizeNodeCountBatch:             IOResult[Unit],
    policyGenerationBootGuard:           zio.Promise[Nothing, Unit],
    healthcheckNotificationService:      HealthcheckNotificationService,
    jsonPluginDefinition:                ReadPluginPackageInfo,
    pluginSettingsService:               PluginSettingsService,
    rudderApi:                           LiftHandler,
    authorizationApiMapping:             ExtensibleAuthorizationApiMapping,
    roleApiMapping:                      RoleApiMapping,
    roRuleCategoryRepository:            RoRuleCategoryRepository,
    woRuleCategoryRepository:            WoRuleCategoryRepository,
    workflowLevelService:                DefaultWorkflowLevel,
    ncfTechniqueReader:                  EditorTechniqueReader,
    recentChangesService:                NodeChangesService,
    ruleCategoryService:                 RuleCategoryService,
    snippetExtensionRegister:            SnippetExtensionRegister,
    clearCacheService:                   ClearCacheService,
    linkUtil:                            LinkUtil,
    userRepository:                      UserRepository,
    userService:                         UserService,
    apiVersions:                         List[ApiVersion],
    apiDispatcher:                       RudderEndpointDispatcher,
    configurationRepository:             ConfigurationRepository,
    roParameterService:                  RoParameterService,
    agentRegister:                       AgentRegister,
    asyncWorkflowInfo:                   AsyncWorkflowInfo,
    commitAndDeployChangeRequest:        CommitAndDeployChangeRequestService,
    doobie:                              Doobie,
    workflowEventLogService:             WorkflowEventLogService,
    changeRequestEventLogService:        ChangeRequestEventLogService,
    changeRequestChangesUnserialisation: ChangeRequestChangesUnserialisation,
    diffService:                         DiffService,
    diffDisplayer:                       DiffDisplayer,
    rwLdap:                              LDAPConnectionProvider[RwLDAPConnection],
    apiAuthorizationLevelService:        DefaultApiAuthorizationLevel,
    tokenGenerator:                      TokenGeneratorImpl,
    roLDAPParameterRepository:           RoLDAPParameterRepository,
    woLDAPParameterRepository:           WoLDAPParameterRepository,
    interpolationCompiler:               InterpolatedValueCompilerImpl,
    deploymentService:                   PromiseGeneration_Hooks,
    campaignEventRepo:                   CampaignEventRepositoryImpl,
    campaignRepo:                        CampaignRepository,
    mainCampaignService:                 MainCampaignService,
    campaignSerializer:                  CampaignSerializer,
    jsonReportsAnalyzer:                 JSONReportsAnalyser,
    aggregateReportScheduler:            FindNewReportsExecution,
    secretEventLogService:               SecretEventLogService,
    changeRequestChangesSerialisation:   ChangeRequestChangesSerialisation,
    gitRepo:                             GitRepositoryProvider,
    gitModificationRepository:           GitModificationRepository,
    inventorySaver:                      NodeFactInventorySaver,
    inventoryDitService:                 InventoryDitService,
    nodeFactRepository:                  NodeFactRepository,
    scoreServiceManager:                 ScoreServiceManager,
    scoreService:                        ScoreService,
    tenantService:                       TenantService,
    computeNodeStatusReportService:      ComputeNodeStatusReportService & HasNodeStatusReportUpdateHook,
    scoreRepository:                     ScoreRepository,
    propertiesRepository:                PropertiesRepository,
    propertiesService:                   NodePropertiesService,
    techniqueCompilationStatusService:   TechniqueCompilationStatusSyncService,
    ruleValGeneratedHookService:         RuleValGeneratedHookService,
    instanceIdService:                   InstanceIdService,
    systemInfoService:                   SystemInfoService
)

/*
 * This object is in charge of class instantiation in a method to avoid dead lock.
 * See: https://issues.rudder.io/issues/22645
 */
object RudderConfigInit {
  import RudderParsedProperties.*

  /**
   * Catch all exception during initialization that would prevent initialization.
   * All exception are catched and will stop the application on boot.
   *
   * Throwing this is more transparent, otherwise the raw error could be unclear
   */
  sealed abstract class InitError(val msg: String, val cause: Option[Throwable])
      extends Throwable(msg, cause.orNull, false, false)

  final case class InitConfigValueError(configKey: String, errMsg: String, override val cause: Option[Throwable])
      extends InitError(
        s"Config value error for '$configKey': $errMsg",
        cause
      )

  def init(): RudderServiceApi = {

    // a buffer to store (init) effects that need to be run before end of init, and we want to
    // have only one "runNow" for them
    val deferredEffects: scala.collection.mutable.Buffer[IOResult[?]] = scala.collection.mutable.Buffer()

    // test connection is up and try to make an human understandable error message.
    ApplicationLogger.debug(s"Test if LDAP connection is active")

    lazy val writeAllAgentSpecificFiles = new WriteAllAgentSpecificFiles(agentRegister)

    // all cache that need to be cleared are stored here
    lazy val clearableCache: Seq[CachedRepository] = Seq(
      cachedAgentRunRepository,
      recentChangesService
    )

    lazy val pluginSettingsService = new FilePluginSettingsService(
      root / "opt" / "rudder" / "etc" / "rudder-pkg" / "rudder-pkg.conf",
      configService.rudder_setup_done().chainError("Could not get 'setup done' property"),
      (done: Boolean) => configService.set_rudder_setup_done(value = done).chainError("Could not get 'setup done' property")
    )

    lazy val rudderPackageService = new RudderPackageCmdService(RUDDER_PACKAGE_CMD)
    lazy val pluginSystemService  = new PluginsServiceImpl(
      rudderPackageService,
      PluginsInfo.plugins,
      rudderFullVersion
    )

    /////////////////////////////////////////////////
    ////////// pluggable service providers //////////
    /////////////////////////////////////////////////

    /*
     * Pluggable service:
     * - Rudder Agent (agent type, agent os)
     * - API ACL
     * - Change Validation workflow
     * - User authentication backends
     * - User authorization capabilities
     */
    // Pluggable agent register
    lazy val agentRegister = new AgentRegister()

    // Plugin input interface to
    lazy val apiAuthorizationLevelService = new DefaultApiAuthorizationLevel(LiftApiProcessingLogger)

    // Plugin input interface for alternative workflow
    lazy val workflowLevelService = new DefaultWorkflowLevel(
      new NoWorkflowServiceImpl(
        commitAndDeployChangeRequest
      )
    )

    // Plugin input interface for alternative authentication providers
    lazy val authenticationProviders = new AuthBackendProvidersManager()

    // Plugin input interface for Authorization for API
    lazy val authorizationApiMapping = new ExtensibleAuthorizationApiMapping(Nil)

    ////////// end pluggable service providers //////////

    lazy val roleApiMapping = new RoleApiMapping(authorizationApiMapping)

    lazy val passwordEncoderDispatcher = {
      new PasswordEncoderDispatcher(
        bcryptCost = RUDDER_BCRYPT_COST,
        argon2Params = RUDDER_ARGON2_PARAMS
      )
    }

    // rudder user list
    lazy val rudderUserListProvider: FileUserDetailListProvider = {
      UserFileProcessing.getUserResourceFile().either.runNow match {
        case Right(resource) =>
          new FileUserDetailListProvider(roleApiMapping, resource, passwordEncoderDispatcher)
        case Left(err)       =>
          ApplicationLogger.error(err.fullMsg)
          // make the application not available
          throw new jakarta.servlet.UnavailableException(s"Error when trying to parse Rudder users file, aborting.")
      }
    }

    lazy val roRuleCategoryRepository: RoRuleCategoryRepository = roLDAPRuleCategoryRepository
    lazy val ruleCategoryService:      RuleCategoryService      = new RuleCategoryService()
    lazy val woRuleCategoryRepository: WoRuleCategoryRepository = woLDAPRuleCategoryRepository

    lazy val changeRequestEventLogService: ChangeRequestEventLogService = new ChangeRequestEventLogServiceImpl(eventLogRepository)
    lazy val secretEventLogService:        SecretEventLogService        = new SecretEventLogServiceImpl(eventLogRepository)

    lazy val xmlSerializer = XmlSerializerImpl(
      ruleSerialisation,
      directiveSerialisation,
      nodeGroupSerialisation,
      globalParameterSerialisation,
      ruleCategorySerialisation
    )

    lazy val xmlUnserializer         = XmlUnserializerImpl(
      ruleUnserialisation,
      directiveUnserialisation,
      nodeGroupUnserialisation,
      globalParameterUnserialisation,
      ruleCategoryUnserialisation
    )
    lazy val workflowEventLogService = new WorkflowEventLogServiceImpl(eventLogRepository, stringUuidGenerator)
    lazy val diffService: DiffService = new DiffServiceImpl()
    lazy val diffDisplayer = new DiffDisplayer(linkUtil)
    lazy val commitAndDeployChangeRequest: CommitAndDeployChangeRequestService = {
      new CommitAndDeployChangeRequestServiceImpl(
        stringUuidGenerator,
        roDirectiveRepository,
        woDirectiveRepository,
        roNodeGroupRepository,
        woNodeGroupRepository,
        roRuleRepository,
        woRuleRepository,
        roLDAPParameterRepository,
        woLDAPParameterRepository,
        asyncDeploymentAgent,
        dependencyAndDeletionService,
        configService.rudder_workflow_enabled,
        xmlSerializer,
        xmlUnserializer,
        sectionSpecParser,
        dynGroupUpdaterService
      )
    }

    lazy val roParameterService: RoParameterService = roParameterServiceImpl

    //////////////////////////////////////////////////////////////////////////////////////////
    ///////////////////////////////////////// REST ///////////////////////////////////////////
    //////////////////////////////////////////////////////////////////////////////////////////

    lazy val zioJsonExtractor = new ZioJsonExtractor(queryParser)

    lazy val tokenGenerator = new TokenGeneratorImpl(32)

    implicit lazy val userService = new UserService {
      def getCurrentUser: AuthenticatedUser = CurrentUser
    }

    lazy val userRepository:   UserRepository = new JdbcUserRepository(doobie)
    // batch for cleaning users
    lazy val userCleanupBatch: CleanupUsers   = new CleanupUsers(
      userRepository,
      IOResult.attempt(rudderUserListProvider.authConfig.users.filter(!_._2.isAdmin).keys.toList),
      RUDDER_USERS_CLEAN_CRON,
      RUDDER_USERS_CLEAN_LAST_LOGIN_DISABLE,
      RUDDER_USERS_CLEAN_LAST_LOGIN_DELETE,
      RUDDER_USERS_CLEAN_DELETED_PURGE,
      RUDDER_USERS_CLEAN_SESSIONS_PURGE,
      List(DefaultAuthBackendProvider.FILE, DefaultAuthBackendProvider.ROOT_ADMIN)
    )

    lazy val ncfTechniqueReader = new EditorTechniqueReaderImpl(
      stringUuidGenerator,
      personIdentService,
      gitConfigRepo,
      prettyPrinter,
      gitModificationRepository,
      RUDDER_CHARSET.name,
      RUDDER_GROUP_OWNER_CONFIG_REPO,
      yamlTechniqueSerializer,
      typeParameterService,
      RUDDERC_CMD,
      GENERIC_METHODS_SYSTEM_LIB,
      GENERIC_METHODS_LOCAL_LIB
    )

    lazy val techniqueSerializer = new TechniqueSerializer(typeParameterService)

    lazy val yamlTechniqueSerializer = new YamlTechniqueSerializer(resourceFileService)

    lazy val linkUtil = new LinkUtil(roRuleRepository, roNodeGroupRepository, roDirectiveRepository, nodeFactRepository)

    // REST API - old
    lazy val restQuicksearch = new RestQuicksearch(
      new FullQuickSearchService()(using
        roLDAPConnectionProvider,
        nodeDit,
        acceptedNodesDit,
        rudderDit,
        roDirectiveRepository,
        nodeFactRepository,
        ncfTechniqueReader
      ),
      userService,
      linkUtil
    )
    lazy val restCompletion  = new RestCompletion(new RestCompletionService(roDirectiveRepository, roRuleRepository))

    lazy val clearCacheService = new ClearCacheServiceImpl(
      nodeConfigurationHashRepo,
      asyncDeploymentAgent,
      eventLogRepository,
      stringUuidGenerator,
      clearableCache
    )
    lazy val systemInfoService: SystemInfoService = new SystemInfoServiceImpl(
      getNodeMetrics,
      nodeFactRepository,
      instanceIdService,
      policyServerManagementService
    )

    lazy val techniqueArchiver = new TechniqueArchiverImpl(
      gitConfigRepo,
      prettyPrinter,
      gitModificationRepository,
      personIdentService,
      techniqueParser,
      techniqueCompiler,
      RUDDER_GROUP_OWNER_CONFIG_REPO
    )

    lazy val techniqueCompiler: TechniqueCompiler = new RuddercTechniqueCompiler(
      new RuddercServiceImpl(RUDDERC_CMD, 5.seconds),
      _.path,
      RUDDER_GIT_ROOT_CONFIG_REPO
    )

    lazy val techniqueCompilationStatusService: ReadEditorTechniqueCompilationResult = new TechniqueCompilationStatusService(
      ncfTechniqueReader,
      techniqueCompiler
    )

    lazy val techniqueStatusReaderService: ReadEditorTechniqueActiveStatus = new TechniqueActiveStatusService(
      roDirectiveRepository
    )

    lazy val techniqueCompilationCache: TechniqueCompilationStatusSyncService = {
      val sync = TechniqueCompilationErrorsActorSync
        .make(asyncDeploymentAgent, techniqueCompilationStatusService, techniqueStatusReaderService)
        .runNow
      techniqueRepositoryImpl.registerCallback(new SyncCompilationStatusOnTechniqueCallback("SyncCompilationStatus", 10000, sync))
      sync
    }

    lazy val ncfTechniqueWriter: TechniqueWriter = new TechniqueWriterImpl(
      techniqueArchiver,
      updateTechniqueLibrary,
      new DeleteEditorTechniqueImpl(
        techniqueArchiver,
        updateTechniqueLibrary,
        roDirectiveRepository,
        woDirectiveRepository,
        techniqueRepository,
        workflowLevelService,
        techniqueCompilationCache,
        RUDDER_GIT_ROOT_CONFIG_REPO
      ),
      techniqueCompiler,
      techniqueCompilationCache,
      RUDDER_GIT_ROOT_CONFIG_REPO
    )

    lazy val pipelinedInventoryParser: InventoryParser = {
      val fusionReportParser = {
        new FusionInventoryParser(
          stringUuidGenerator,
          rootParsingExtensions = Nil,
          contentParsingExtensions = Nil,
          ignoreProcesses = INVENTORIES_IGNORE_PROCESSES
        )
      }

      new DefaultInventoryParser(
        fusionReportParser,
        Seq(
          new PreInventoryParserCheckConsistency,
          new PreInventoryParserCheckInventoryAge(INVENTORIES_MAX_BEFORE_NOW, INVENTORIES_MAX_AFTER_NOW)
        )
      )
    }

    lazy val gitFactRepoProvider = {
      // execute pre connection checks before anything else. Both here and in the other connection to ensure it's
      // executed before everything.
      earlyChecks.initialize()

      GitRepositoryProviderImpl
        .make(RUDDER_GIT_ROOT_FACT_REPO)
        .runOrDie(err => new RuntimeException(s"Error when initializing git configuration repository: " + err.fullMsg))
    }
    lazy val gitFactRepoGC       = new GitGC(gitFactRepoProvider, RUDDER_GIT_GC)
    gitFactRepoGC.start()
    lazy val gitFactStorage      = if (RUDDER_GIT_FACT_WRITE_NODES) {
      val r = new GitNodeFactStorageImpl(gitFactRepoProvider, Some(RUDDER_GROUP_OWNER_CONFIG_REPO), RUDDER_GIT_FACT_COMMIT_NODES)
      r.checkInit().runOrDie(err => new RuntimeException(s"Error when checking fact repository init: " + err.fullMsg))
      r
    } else NoopFactStorage

    lazy val ldapNodeFactStorage = new LdapNodeFactStorage(
      rwLdap,
      nodeDit,
      inventoryDitService,
      ldapEntityMapper,
      inventoryMapper,
      nodeReadWriteMutex,
      deprecated.ldapFullInventoryRepository,
      deprecated.softwareInventoryDAO,
      deprecated.ldapSoftwareSave,
      stringUuidGenerator
    )

    lazy val getNodeBySoftwareName = new SoftDaoGetNodesBySoftwareName(deprecated.softwareInventoryDAO)

    lazy val tenantService = DefaultTenantService.make(Nil).runNow

    lazy val nodeFactRepository = {

      val callbacks = Chunk[NodeFactChangeEventCallback](
        new AppLogNodeFactChangeEventCallback(),
        new EventLogsNodeFactChangeEventCallback(eventLogRepository),
        new HistorizeNodeState(
          inventoryHistoryJdbcRepository,
          ldapNodeFactStorage,
          gitFactStorage,
          (KEEP_DELETED_NODE_FACT_DURATION.getSeconds == 0)
        )
      )

      val repo = CoreNodeFactRepository.make(ldapNodeFactStorage, getNodeBySoftwareName, tenantService, callbacks).runNow
      repo
    }

    // need to be done here to avoid cyclic dependencies
    deferredEffects.append(
      nodeFactRepository.registerChangeCallbackAction(
        new GenerationOnChange(updateDynamicGroups, asyncDeploymentAgent, stringUuidGenerator)
      )
    )
    deferredEffects.append(
      nodeFactRepository.registerChangeCallbackAction(
        new CacheInvalidateNodeFactEventCallback(cachedNodeConfigurationService, computeNodeStatusReportService, Nil)
      )
    )
    deferredEffects.append(
      nodeFactRepository.registerChangeCallbackAction(
        new ScoreUpdateOnNodeFactChange(scoreServiceManager, scoreService)
      )
    )

    lazy val propertiesRepository: PropertiesRepository = InMemoryPropertiesRepository.make(nodeFactRepository).runNow

    lazy val propertiesService: NodePropertiesService =
      new NodePropertiesServiceImpl(roLDAPParameterRepository, roNodeGroupRepository, nodeFactRepository, propertiesRepository)

    lazy val propertiesSyncService: NodePropertiesSyncService =
      new NodePropertiesSyncServiceImpl(propertiesService, propertiesRepository, asyncDeploymentAgent)

    lazy val inventorySaver = new NodeFactInventorySaver(
      nodeFactRepository,
      (
        CheckOsType
        // removed: noe and machine go together, and we have UUIDs for nodes
        // :: automaticMerger
        // :: CheckMachineName
        :: new LastInventoryDate()
        :: AddIpValues
        // removed: we only store the files, not LDIF of changes
        // :: new LogInventoryPreCommit(inventoryMapper, ldifInventoryLogger)
        :: Nil
      ),
      (
        // removed: nodes are fully deleted now
//      new PendingNodeIfNodeWasRemoved(fullInventoryRepository)
        // already commited in fact repos
//      new FactRepositoryPostCommit[Unit](factRepo, nodeFactInfoService)
        // deprecated: we use fact repo now
//      :: new PostCommitLogger(ldifInventoryLogger)
        new PostCommitInventoryHooks[Unit](HOOKS_D, HOOKS_IGNORE_SUFFIXES, nodeFactRepository)
        // removed: this is done as a callback of CoreNodeFactRepos
        // :: new TriggerPolicyGenerationPostCommit[Unit](asyncDeploymentAgent, stringUuidGenerator)
        :: Nil
      )
    )

    lazy val inventoryProcessorInternal = {
      val checkLdapAlive: () => IOResult[Unit] = { () =>
        for {
          con <- rwLdap
          res <- con.get(pendingNodesDit.NODES.dn, "1.1")
        } yield {
          ()
        }
      }
      val maxParallel = {
        try {
          val user = if (MAX_PARSE_PARALLEL.endsWith("x")) {
            val xx = MAX_PARSE_PARALLEL.substring(0, MAX_PARSE_PARALLEL.size - 1)
            java.lang.Double.parseDouble(xx) * java.lang.Runtime.getRuntime.availableProcessors()
          } else {
            java.lang.Double.parseDouble(MAX_PARSE_PARALLEL)
          }
          Math.max(1, user).toLong
        } catch {
          case ex: Exception =>
            // logs are not available here
            println(
              s"ERROR Error when parsing configuration properties for the parallelization of inventory processing. " +
              s"Expecting a positive integer or number of time the available processors. Default to '0.5x': " +
              s"inventory.parse.parallelization=${MAX_PARSE_PARALLEL}"
            )
            Math.max(1, Math.ceil(java.lang.Runtime.getRuntime.availableProcessors().toDouble / 2).toLong)
        }
      }
      new InventoryProcessor(
        pipelinedInventoryParser,
        inventorySaver,
        maxParallel,
        // it's always rudder doing these checking queries
        new InventoryDigestServiceV1((id: NodeId) =>
          nodeFactRepository.get(id)(using QueryContext.systemQC, SelectNodeStatus.Accepted)
        ),
        checkLdapAlive
      )
    }

    lazy val inventoryProcessor = {
      val mover = new InventoryMover(
        INVENTORY_DIR_RECEIVED,
        INVENTORY_DIR_FAILED,
        new InventoryFailedHook(
          HOOKS_D,
          HOOKS_IGNORE_SUFFIXES
        )
      )
      new DefaultProcessInventoryService(inventoryProcessorInternal, mover)
    }

    lazy val inventoryWatcher = {
      val fileProcessor = ProcessFile.start(inventoryProcessor, INVENTORY_DIR_INCOMING).runNow

      new InventoryFileWatcher(
        fileProcessor,
        INVENTORY_DIR_INCOMING,
        INVENTORY_DIR_UPDATE,
        WATCHER_DELETE_OLD_INVENTORIES_AGE,
        WATCHER_GARBAGE_OLD_INVENTORIES_PERIOD
      )
    }

    lazy val cleanOldInventoryBatch = {
      new PurgeOldInventoryData(
        RUDDER_INVENTORIES_CLEAN_CRON,
        RUDDER_INVENTORIES_CLEAN_AGE,
        List(better.files.File(INVENTORY_DIR_FAILED), better.files.File(INVENTORY_DIR_RECEIVED)),
        inventoryHistoryJdbcRepository,
        KEEP_ACCEPTATION_NODE_FACT_DURATION,
        KEEP_DELETED_NODE_FACT_DURATION
      )
    }

    lazy val pluginsIndexFile     = root / "var" / "rudder" / "packages" / "index.json"
    lazy val jsonPluginDefinition = new ReadPluginPackageInfo(pluginsIndexFile)

    lazy val resourceFileService = new GitResourceFileService(gitConfigRepo)

    /* **********************
     *         API
     * **********************
     */

    /*
     * Some API need to be exposed
     */

    // RudderConfig.complianceService used in NodeGroupForm
    lazy val complianceAPIService = new ComplianceAPIService(
      roRuleRepository,
      nodeFactRepository,
      roNodeGroupRepository,
      reportingService,
      roDirectiveRepository,
      globalComplianceModeService.getGlobalComplianceMode,
      configService.rudder_global_policy_mode()
    )

    // need to be exposed for Snapshot page
    lazy val systemApiService11 = new SystemApiService11(
      updateTechniqueLibrary,
      debugScript,
      clearCacheService,
      asyncDeploymentAgent,
      stringUuidGenerator,
      updateDynamicGroups,
      itemArchiveManager,
      personIdentService,
      gitConfigRepo
    )

    lazy val apiDispatcher = new RudderEndpointDispatcher(LiftApiProcessingLogger)

    lazy val systemTokenSecret = ApiTokenSecret.generate(tokenGenerator, suffix = "system")

    // we need to init API internal services into the {} block to avoid bug https://issues.rudder.io/issues/26416
    lazy val rudderApi = {
      import com.normation.rudder.rest.lift.*
      // need to be out of RudderApi else "Platform restriction: a parameter list's length cannot exceed 254."

      lazy val ruleApiService13 = {
        new RuleApiService14(
          roRuleRepository,
          woRuleRepository,
          configurationRepository,
          stringUuidGenerator,
          asyncDeploymentAgent,
          workflowLevelService,
          roRuleCategoryRepository,
          woRuleCategoryRepository,
          roDirectiveRepository,
          roNodeGroupRepository,
          nodeFactRepository,
          configService.rudder_global_policy_mode,
          ruleApplicationStatus
        )
      }

      lazy val parameterApiService14 = {
        new ParameterApiService14(
          roLDAPParameterRepository,
          stringUuidGenerator,
          workflowLevelService
        )
      }

      lazy val hookApiService = new HookApiService(HOOKS_D, HOOKS_IGNORE_SUFFIXES)

      lazy val ruleInternalApiService =
        new RuleInternalApiService(roRuleRepository, roNodeGroupRepository, roRuleCategoryRepository, nodeFactRepository)

      lazy val groupInternalApiService = new GroupInternalApiService(roNodeGroupRepository)

      lazy val systemApiService13 = new SystemApiService13(
        healthcheckService,
        healthcheckNotificationService,
        deprecated.softwareService
      )

      lazy val archiveApi = {
        val archiveBuilderService = {
          new ZipArchiveBuilderService(
            new FileArchiveNameService(),
            configurationRepository,
            gitParseTechniqueLibrary,
            roLdapNodeGroupRepository,
            roRuleRepository,
            roDirectiveRepository,
            techniqueRepository
          )
        }
        // fixe archive name to make it simple to test
        val rootDirName           = "archive".succeed
        new com.normation.rudder.rest.lift.ArchiveApi(
          archiveBuilderService,
          rootDirName,
          new ZipArchiveReaderImpl(queryParser, techniqueParser),
          new SaveArchiveServicebyRepo(
            techniqueArchiver,
            techniqueReader,
            roDirectiveRepository,
            woDirectiveRepository,
            roNodeGroupRepository,
            woNodeGroupRepository,
            roRuleRepository,
            woRuleRepository,
            updateTechniqueLibrary,
            asyncDeploymentAgent,
            stringUuidGenerator
          ),
          new CheckArchiveServiceImpl(techniqueRepository)
        )
      }

      lazy val apiModules = {
        import com.normation.rudder.rest.lift.*
        List(
          new ComplianceApi(complianceAPIService, roDirectiveRepository),
          new GroupsApi(
            propertiesService,
            zioJsonExtractor,
            stringUuidGenerator,
            userPropertyService,
            new GroupApiService14(
              nodeFactRepository,
              roNodeGroupRepository,
              woNodeGroupRepository,
              propertiesRepository,
              propertiesService,
              stringUuidGenerator,
              asyncDeploymentAgent,
              workflowLevelService,
              queryParser,
              queryProcessor
            )
          ),
          new DirectiveApi(
            zioJsonExtractor,
            stringUuidGenerator,
            new DirectiveApiService14(
              roDirectiveRepository,
              configurationRepository,
              woDirectiveRepository,
              stringUuidGenerator,
              asyncDeploymentAgent,
              workflowLevelService,
              directiveEditorService,
              techniqueRepositoryImpl
            )
          ),
          new NodeApi(
            zioJsonExtractor,
            propertiesService,
            new NodeApiService(
              rwLdap,
              nodeFactRepository,
              propertiesRepository,
              roAgentRunsRepository,
              ldapEntityMapper,
              stringUuidGenerator,
              nodeDit,
              pendingNodesDit,
              acceptedNodesDit,
              newNodeManagerImpl,
              removeNodeServiceImpl,
              zioJsonExtractor,
              reportingService,
              queryProcessor,
              inventoryQueryChecker,
              () => configService.rudder_global_policy_mode().toBox,
              RUDDER_RELAY_API,
              scoreService
            ),
            userPropertyService,
            new NodeApiInheritedProperties(propertiesRepository),
            stringUuidGenerator,
            DeleteMode.Erase // only supported mode for Rudder 8.0
          ),
          new ParameterApi(zioJsonExtractor, parameterApiService14),
          new SettingsApi(
            configService,
            asyncDeploymentAgent,
            stringUuidGenerator,
            policyServerManagementService,
            nodeFactRepository
          ),
          new TechniqueApi(
            new TechniqueAPIService14(
              roDirectiveRepository,
              gitParseTechniqueLibrary,
              ncfTechniqueReader,
              techniqueSerializer,
              techniqueCompiler
            ),
            ncfTechniqueWriter,
            ncfTechniqueReader,
            techniqueRepository,
            techniqueSerializer,
            stringUuidGenerator,
            userPropertyService,
            resourceFileService,
            RUDDER_GIT_ROOT_CONFIG_REPO
          ),
          new RuleApi(
            zioJsonExtractor,
            ruleApiService13,
            stringUuidGenerator
          ),
          new SystemApi(
            systemApiService11,
            systemApiService13,
            systemInfoService
          ),
          new UserManagementApiImpl(
            userRepository,
            rudderUserListProvider,
            new UserManagementService(
              userRepository,
              rudderUserListProvider,
              passwordEncoderDispatcher,
              UserFileProcessing.getUserResourceFile()
            ),
            tenantService,
            () => authenticationProviders.getProviderProperties().view.mapValues(_.providerRoleExtension).toMap,
            () => authenticationProviders.getConfiguredProviders().map(_.name).toSet
          ),
          new ApiAccountApi(
            new ApiAccountApiServiceV1(
              roApiAccountRepository,
              woApiAccountRepository,
              ApiAccountMapping.build(stringUuidGenerator, tokenGenerator),
              stringUuidGenerator,
              userService
            )
          ),
          new InventoryApi(inventoryWatcher, better.files.File(INVENTORY_DIR_INCOMING)),
          new PluginApi(pluginSettingsService, pluginSystemService, PluginsInfo.pluginJsonInfos.succeed),
          new PluginInternalApi(pluginSystemService),
          new RecentChangesAPI(recentChangesService),
          new RulesInternalApi(ruleInternalApiService, ruleApiService13),
          new GroupsInternalApi(groupInternalApiService),
          new CampaignApi(
            campaignRepo,
            campaignSerializer,
            campaignEventRepo,
            mainCampaignService,
            stringUuidGenerator
          ),
          new HookApi(hookApiService),
          archiveApi,
          new ScoreApiImpl(scoreService),
          eventLogApi
          // info api must be resolved latter, because else it misses plugin apis !
        )
      }

      val api = new LiftHandler(
        apiDispatcher,
        SupportedApiVersion.apiVersions,
        new AclApiAuthorization(LiftApiProcessingLogger, userService, () => apiAuthorizationLevelService.aclEnabled),
        None
      )
      apiModules.foreach(module => api.addModules(module.getLiftEndpoints()))
      api
    }

    // Internal APIs
    lazy val sharedFileApi     =
      new SharedFilesAPI(userService, RUDDER_DIR_SHARED_FILES_FOLDER, RUDDER_GIT_ROOT_CONFIG_REPO)
    lazy val eventLogApi       = {
      new EventLogAPI(
        new EventLogService(eventLogRepository, eventLogDetailsGenerator, personIdentService),
        eventLogDetailsGenerator,
        eventType => S ? ("rudder.log.eventType.names." + eventType.serialize)
      )
    }
    lazy val asyncWorkflowInfo = new AsyncWorkflowInfo
    lazy val configService: ReadConfigService & UpdateConfigService = {
      new GenericConfigService(
        RudderProperties.config,
        new LdapConfigRepository(rudderDit, rwLdap, ldapEntityMapper, eventLogRepository, stringUuidGenerator),
        asyncWorkflowInfo,
        workflowLevelService
      )
    }

    lazy val recentChangesService = new CachedNodeChangesServiceImpl(
      new NodeChangesServiceImpl(reportsRepository),
      () => configService.rudder_compute_changes().toBox
    )

    //////////////////////////////////////////////////////////////////////////////////////////
    //////////////////////////////////////////////////////////////////////////////////////////

    //
    // Concrete implementation.
    // They are private to that object, and they can refer to other
    // private implementation as long as they conform to interface.
    //

    lazy val gitParseTechniqueLibrary = new GitParseTechniqueLibrary(
      techniqueParser,
      gitConfigRepo,
      gitRevisionProvider,
      "techniques",
      "metadata.xml"
    )
    lazy val configurationRepository  = new ConfigurationRepositoryImpl(
      roLdapDirectiveRepository,
      techniqueRepository,
      roLdapRuleRepository,
      roNodeGroupRepository,
      parseActiveTechniqueLibrary,
      gitParseTechniqueLibrary,
      parseRules,
      parseGroupLibrary
    )

    /////////////////////////////////////////////////////////////////////
    //// everything was private
    ////////////////////////////////////////////////////////////////////

    lazy val roLDAPApiAccountRepository = new RoLDAPApiAccountRepository(
      rudderDitImpl,
      roLdap,
      ldapEntityMapper,
      ApiAuthorization.allAuthz.acl, // for system token
      systemTokenSecret.toHash()
    )
    lazy val roApiAccountRepository: RoApiAccountRepository = roLDAPApiAccountRepository

    lazy val woLDAPApiAccountRepository = new WoLDAPApiAccountRepository(
      rudderDitImpl,
      rwLdap,
      ldapEntityMapper,
      ldapDiffMapper,
      logRepository,
      personIdentServiceImpl
    )
    lazy val woApiAccountRepository: WoApiAccountRepository = woLDAPApiAccountRepository

    lazy val ruleApplicationStatusImpl: RuleApplicationStatusService = new RuleApplicationStatusServiceImpl()
    lazy val ruleApplicationStatus = ruleApplicationStatusImpl
    lazy val propertyEngineServiceImpl: PropertyEngineService = new PropertyEngineServiceImpl(
      List.empty
    )
    lazy val propertyEngineService = propertyEngineServiceImpl

    def DN(rdn: String, parent: DN) = new DN(new RDN(rdn), parent)
    lazy val LDAP_BASEDN                      = new DN("cn=rudder-configuration")
    lazy val LDAP_INVENTORIES_BASEDN          = DN("ou=Inventories", LDAP_BASEDN)
    lazy val LDAP_INVENTORIES_SOFTWARE_BASEDN = LDAP_INVENTORIES_BASEDN

    lazy val acceptedNodesDitImpl: InventoryDit = new InventoryDit(
      DN("ou=Accepted Inventories", LDAP_INVENTORIES_BASEDN),
      LDAP_INVENTORIES_SOFTWARE_BASEDN,
      "Accepted inventories"
    )
    lazy val acceptedNodesDit = acceptedNodesDitImpl
    lazy val pendingNodesDitImpl: InventoryDit = new InventoryDit(
      DN("ou=Pending Inventories", LDAP_INVENTORIES_BASEDN),
      LDAP_INVENTORIES_SOFTWARE_BASEDN,
      "Pending inventories"
    )
    lazy val pendingNodesDit     = pendingNodesDitImpl
    lazy val removedNodesDitImpl =
      new InventoryDit(DN("ou=Removed Inventories", LDAP_INVENTORIES_BASEDN), LDAP_INVENTORIES_SOFTWARE_BASEDN, "Removed Servers")
    lazy val rudderDitImpl: RudderDit = new RudderDit(DN("ou=Rudder", LDAP_BASEDN))
    lazy val rudderDit = rudderDitImpl
    lazy val nodeDitImpl: NodeDit = new NodeDit(LDAP_BASEDN)
    lazy val nodeDit = nodeDitImpl
    lazy val inventoryDitService: InventoryDitService =
      new InventoryDitServiceImpl(pendingNodesDitImpl, acceptedNodesDitImpl, removedNodesDitImpl)
    lazy val stringUuidGenerator: StringUuidGenerator = new StringUuidGeneratorImpl
    lazy val systemVariableSpecService = new SystemVariableSpecServiceImpl()
    lazy val ldapEntityMapper: LDAPEntityMapper =
      new LDAPEntityMapper(rudderDitImpl, nodeDitImpl, queryRawParser, inventoryMapper)

    ///// items serializer - service that transforms items to XML /////
    lazy val ruleSerialisation:                    RuleSerialisation                    = new RuleSerialisationImpl(
      Constants.XML_CURRENT_FILE_FORMAT.toString
    )
    lazy val ruleCategorySerialisation:            RuleCategorySerialisation            = new RuleCategorySerialisationImpl(
      Constants.XML_CURRENT_FILE_FORMAT.toString
    )
    lazy val rootSectionSerialisation:             SectionSpecWriter                    = new SectionSpecWriterImpl()
    lazy val activeTechniqueCategorySerialisation: ActiveTechniqueCategorySerialisation =
      new ActiveTechniqueCategorySerialisationImpl(Constants.XML_CURRENT_FILE_FORMAT.toString)
    lazy val activeTechniqueSerialisation:         ActiveTechniqueSerialisation         =
      new ActiveTechniqueSerialisationImpl(Constants.XML_CURRENT_FILE_FORMAT.toString)
    lazy val directiveSerialisation:               DirectiveSerialisation               =
      new DirectiveSerialisationImpl(Constants.XML_CURRENT_FILE_FORMAT.toString)
    lazy val nodeGroupCategorySerialisation:       NodeGroupCategorySerialisation       =
      new NodeGroupCategorySerialisationImpl(Constants.XML_CURRENT_FILE_FORMAT.toString)
    lazy val nodeGroupSerialisation:               NodeGroupSerialisation               =
      new NodeGroupSerialisationImpl(Constants.XML_CURRENT_FILE_FORMAT.toString)
    lazy val deploymentStatusSerialisation:        DeploymentStatusSerialisation        =
      new DeploymentStatusSerialisationImpl(Constants.XML_CURRENT_FILE_FORMAT.toString)
    lazy val globalParameterSerialisation:         GlobalParameterSerialisation         =
      new GlobalParameterSerialisationImpl(Constants.XML_CURRENT_FILE_FORMAT.toString)
    lazy val apiAccountSerialisation:              APIAccountSerialisation              =
      new APIAccountSerialisationImpl(Constants.XML_CURRENT_FILE_FORMAT.toString)
    lazy val propertySerialization:                GlobalPropertySerialisation          =
      new GlobalPropertySerialisationImpl(Constants.XML_CURRENT_FILE_FORMAT.toString)
    lazy val changeRequestChangesSerialisation:    ChangeRequestChangesSerialisation    = {
      new ChangeRequestChangesSerialisationImpl(
        Constants.XML_CURRENT_FILE_FORMAT.toString,
        nodeGroupSerialisation,
        directiveSerialisation,
        ruleSerialisation,
        globalParameterSerialisation,
        techniqueRepositoryImpl,
        rootSectionSerialisation
      )
    }

    lazy val eventLogFactory = new EventLogFactoryImpl(
      ruleSerialisation,
      directiveSerialisation,
      nodeGroupSerialisation,
      activeTechniqueSerialisation,
      globalParameterSerialisation,
      apiAccountSerialisation,
      propertySerialization,
      new SecretSerialisationImpl(Constants.XML_CURRENT_FILE_FORMAT.toString)
    )
    lazy val pathComputer    = new PathComputerImpl(
      Constants.NODE_PROMISES_PARENT_DIR_BASE,
      Constants.NODE_PROMISES_PARENT_DIR,
      RUDDER_DIR_BACKUP,
      Constants.CFENGINE_COMMUNITY_PROMISES_PATH
    )

    /*
     * For now, we don't want to query server other
     * than the accepted ones.
     */
    lazy val getSubGroupChoices = new DefaultSubGroupComparatorRepository(roLdapNodeGroupRepository)
    lazy val nodeQueryData      = new NodeQueryCriteriaData(() => getSubGroupChoices, instanceIdService)
    lazy val ditQueryDataImpl   = new DitQueryData(acceptedNodesDitImpl, nodeDit, rudderDit, nodeQueryData)
    lazy val queryParser        = CmdbQueryParser.jsonStrictParser(Map.empty[String, ObjectCriterion] ++ ditQueryDataImpl.criteriaMap)
    lazy val queryRawParser     = CmdbQueryParser.jsonRawParser(Map.empty[String, ObjectCriterion] ++ ditQueryDataImpl.criteriaMap)
    lazy val inventoryMapper: InventoryMapper =
      new InventoryMapper(inventoryDitService, pendingNodesDitImpl, acceptedNodesDitImpl, removedNodesDitImpl)

    lazy val ldapDiffMapper = new LDAPDiffMapper(ldapEntityMapper, queryRawParser)

    lazy val activeTechniqueCategoryUnserialisation = new ActiveTechniqueCategoryUnserialisationImpl
    lazy val activeTechniqueUnserialisation         = new ActiveTechniqueUnserialisationImpl
    lazy val directiveUnserialisation               = new DirectiveUnserialisationImpl
    lazy val nodeGroupCategoryUnserialisation       = new NodeGroupCategoryUnserialisationImpl
    lazy val nodeGroupUnserialisation               = new NodeGroupUnserialisationImpl(queryParser)
    lazy val ruleUnserialisation                    = new RuleUnserialisationImpl
    lazy val ruleCategoryUnserialisation            = new RuleCategoryUnserialisationImpl
    lazy val globalParameterUnserialisation         = new GlobalParameterUnserialisationImpl
    lazy val changeRequestChangesUnserialisation    = new ChangeRequestChangesUnserialisationImpl(
      nodeGroupUnserialisation,
      directiveUnserialisation,
      ruleUnserialisation,
      globalParameterUnserialisation,
      techniqueRepository,
      sectionSpecParser
    )

    lazy val eventLogDetailsServiceImpl = new EventLogDetailsServiceImpl(
      queryParser,
      new DirectiveUnserialisationImpl,
      new NodeGroupUnserialisationImpl(queryParser),
      new RuleUnserialisationImpl,
      new ActiveTechniqueUnserialisationImpl,
      new DeploymentStatusUnserialisationImpl,
      new GlobalParameterUnserialisationImpl,
      new ApiAccountUnserialisationImpl,
      new SecretUnserialisationImpl
    )

    //////////////////////////////////////////////////////////
    //  non success services that could perhaps be
    //////////////////////////////////////////////////////////

    // => rwLdap is only used to repair an error, that could be repaired elsewhere.

    // => because of systemVariableSpecService
    // metadata.xml parser

    lazy val variableSpecParser = new VariableSpecParser
    lazy val sectionSpecParser  = new SectionSpecParser(variableSpecParser)
    lazy val techniqueParser    = {
      new TechniqueParser(variableSpecParser, sectionSpecParser, systemVariableSpecService)
    }

    lazy val userPropertyServiceImpl = new StatelessUserPropertyService(
      configService.rudder_ui_changeMessage_enabled,
      configService.rudder_ui_changeMessage_mandatory,
      configService.rudder_ui_changeMessage_explanation
    )
    lazy val userPropertyService: UserPropertyService = userPropertyServiceImpl

    ////////////////////////////////////
    //  non success services
    ////////////////////////////////////

    ///// end /////

    lazy val logRepository                = {
      val eventLogRepo = new EventLogJdbcRepository(doobie, eventLogFactory)
      techniqueRepositoryImpl.registerCallback(
        new LogEventOnTechniqueReloadCallback(
          "LogEventTechnique",
          100, // must be before most of other

          eventLogRepo
        )
      )
      eventLogRepo
    }
    lazy val eventLogRepository           = logRepository
    lazy val inventoryLogEventServiceImpl = new InventoryEventLogServiceImpl(logRepository)
    lazy val gitConfigRepo                = GitRepositoryProviderImpl
      .make(RUDDER_GIT_ROOT_CONFIG_REPO)
      .runOrDie(err => new RuntimeException(s"Error when creating git configuration repository: " + err.fullMsg))
    lazy val gitConfigRepoGC              = new GitGC(gitConfigRepo, RUDDER_GIT_GC)
    lazy val gitRevisionProviderImpl      = {
      new LDAPGitRevisionProvider(rwLdap, rudderDitImpl, gitConfigRepo, RUDDER_TECHNIQUELIBRARY_GIT_REFS_PATH)
    }
    lazy val gitRevisionProvider: GitRevisionProvider = gitRevisionProviderImpl

    lazy val techniqueReader: TechniqueReader = {
      // find the relative path from gitConfigRepo to the ptlib root
      val gitSlash = new File(RUDDER_GIT_ROOT_CONFIG_REPO).getPath + "/"
      if (!RUDDER_DIR_TECHNIQUES.startsWith(gitSlash)) {
        ApplicationLogger.error(
          "The Technique library root directory must be a sub-directory of '%s', but it is configured to be: '%s'".format(
            RUDDER_GIT_ROOT_CONFIG_REPO,
            RUDDER_DIR_TECHNIQUES
          )
        )
        throw new RuntimeException(
          "The Technique library root directory must be a sub-directory of '%s', but it is configured to be: '%s'".format(
            RUDDER_GIT_ROOT_CONFIG_REPO,
            RUDDER_DIR_TECHNIQUES
          )
        )
      }

      // create a demo default-directive-names.conf if none exists
      val defaultDirectiveNames = new File(RUDDER_DIR_TECHNIQUES, "default-directive-names.conf")
      if (!defaultDirectiveNames.exists) {
        FileUtils.writeStringToFile(
          defaultDirectiveNames,
          """
            |#
            |# This file contains the default name that a directive gets in Rudder UI creation pop-up.
            |# The file format is a simple key=value file, with key being the techniqueName
            |# or techniqueName/version and the value being the name to use.
            |# An empty value will lead to an empty default name.
            |# For a new Directive, we will try to lookup "TechniqueName/version" and if not
            |# available "TechniqueName" from this file. If neither key is available, the
            |# pop-up will use the actual Technique name as default.
            |# Don't forget to commit the file to have modifications seen by Rudder.
            |#
            |
            |# Default pattern for new directive from "userManagement" technique:
            |userManagement=User: <name> Login: <login>
            |# For userManagement version 2.0, prefer that pattern in new Directives:
            |userManagement/2.0: User 2.0 [LOGIN]
            |""".stripMargin,
          RUDDER_CHARSET.value
        )
      }

      val relativePath = RUDDER_DIR_TECHNIQUES.substring(gitSlash.size, RUDDER_DIR_TECHNIQUES.size)
      new GitTechniqueReader(
        techniqueParser,
        gitRevisionProviderImpl,
        gitConfigRepo,
        "metadata.xml",
        "category.xml",
        Some(relativePath),
        "default-directive-names.conf"
      )
    }

    lazy val roLdap                   = {
      new ROPooledSimpleAuthConnectionProvider(
        host = LDAP_HOST,
        port = LDAP_PORT,
        authDn = LDAP_AUTHDN,
        authPw = LDAP_AUTHPW,
        poolSize = LDAP_MAX_POOL_SIZE,
        createIfNecessary = LDAP_CREATE_IF_NECESSARY,
        minimumAvailableConnectionGoal = LDAP_MINIMUM_AVAILABLE_CONNECTION_GOAL,
        maxWaitTime = LDAP_MAX_WAIT_TIME,
        maxConnectionAge = LDAP_MAX_CONNECTION_AGE,
        minDisconnectInterval = LDAP_MIN_DISCONNECT_INTERVAL
      )
    }
    lazy val roLDAPConnectionProvider = roLdap
    lazy val rwLdap                   = {

      // execute pre connection checks before anything else. Both here and in the other connection to ensure it's
      // executed before everything.
      earlyChecks.initialize()

      val rwLdap = new RWPooledSimpleAuthConnectionProvider(
        host = LDAP_HOST,
        port = LDAP_PORT,
        authDn = LDAP_AUTHDN,
        authPw = LDAP_AUTHPW,
        poolSize = LDAP_MAX_POOL_SIZE,
        createIfNecessary = LDAP_CREATE_IF_NECESSARY,
        minimumAvailableConnectionGoal = LDAP_MINIMUM_AVAILABLE_CONNECTION_GOAL,
        maxWaitTime = LDAP_MAX_WAIT_TIME,
        maxConnectionAge = LDAP_MAX_CONNECTION_AGE,
        minDisconnectInterval = LDAP_MIN_DISCONNECT_INTERVAL
      )

      // a sequence and migration that should be executed as soon as we have a connection, and
      // before other services are init, because perhaps they will need the changes here.
      val earlyLdapChecks = new SequentialImmediateBootStrapChecks(
        "early LDAP checks",
        BootstrapLogger.Early.LDAP,
        new CheckLdapConnection(rwLdap),
        new CheckAddSpecialNodeGroupsDescription(rwLdap),
        new CheckRemoveRuddercSetting(rwLdap)
      )

      earlyLdapChecks.checks()

      rwLdap
    }

    // query processor for accepted nodes
    lazy val queryProcessor = new NodeFactQueryProcessor(
      nodeFactRepository,
      new DefaultSubGroupComparatorRepository(roNodeGroupRepository),
      deprecated.internalAcceptedQueryProcessor,
      AcceptedInventory
    )

    // we need a roLdap query checker for nodes in pending
    lazy val inventoryQueryChecker = new NodeFactQueryProcessor(
      nodeFactRepository,
      new DefaultSubGroupComparatorRepository(roNodeGroupRepository),
      deprecated.internalPendingQueryProcessor,
      PendingInventory
    )

    lazy val dynGroupServiceImpl = new DynGroupServiceImpl(rudderDitImpl, roLdap, ldapEntityMapper)

    lazy val pendingNodeCheckGroup = new CheckPendingNodeInDynGroups(inventoryQueryChecker)

    lazy val unitRefuseGroup: UnitRefuseInventory =
      new RefuseGroups("refuse_node:delete_id_in_groups", roLdapNodeGroupRepository, woLdapNodeGroupRepository)

    lazy val acceptHostnameAndIp: UnitCheckAcceptInventory = new AcceptHostnameAndIp(
      "accept_new_server:check_hostname_unicity",
      queryProcessor,
      ditQueryDataImpl,
      configService.node_accept_duplicated_hostname()
    )

    lazy val defaultStateAndPolicyMode = new DefaultStateAndPolicyMode(
      "accept_new_node:setup_default_settings",
      nodeFactRepository,
      configService.rudder_node_onaccept_default_policy_mode(),
      configService.rudder_node_onaccept_default_state()
    )

    // used in accept node to see & store inventories on acceptation
    lazy val inventoryHistoryLogRepository: InventoryHistoryLogRepository = {
      val fullInventoryFromLdapEntries: FullInventoryFromLdapEntries =
        new FullInventoryFromLdapEntriesImpl(inventoryDitService, inventoryMapper)

      new InventoryHistoryLogRepository(
        HISTORY_INVENTORIES_ROOTDIR,
        new FullInventoryFileParser(fullInventoryFromLdapEntries, inventoryMapper),
        new JodaDateTimeConverter(ISODateTimeFormat.dateTime().withZoneUTC())
      )
    }

    lazy val nodeGridImpl = new NodeGrid(nodeFactRepository, configService)

    lazy val modificationService      =
      new ModificationService(gitModificationRepository, itemArchiveManagerImpl, stringUuidGenerator)
    lazy val eventListDisplayerImpl   = new EventListDisplayer(logRepository)
    lazy val eventLogDetailsGenerator = new EventLogDetailsGenerator(
      eventLogDetailsServiceImpl,
      roLdapNodeGroupRepository,
      roLDAPRuleCategoryRepository,
      modificationService,
      linkUtil,
      diffDisplayer
    )

    lazy val databaseManagerImpl = new DatabaseManagerImpl(reportsRepositoryImpl, updateExpectedRepo)

    lazy val inventoryHistoryJdbcRepository = new InventoryHistoryJdbcRepository(doobie)

    lazy val personIdentServiceImpl: PersonIdentService = new TrivialPersonIdentService
    lazy val personIdentService = personIdentServiceImpl

    lazy val roParameterServiceImpl = new RoParameterServiceImpl(roLDAPParameterRepository)

    ///// items archivers - services that allows to transform items to XML and save then on a Git FS /////
    lazy val gitModificationRepository = new GitModificationRepositoryImpl(doobie)
    lazy val gitRuleArchiver:                    GitRuleArchiver                    = new GitRuleArchiverImpl(
      gitConfigRepo,
      ruleSerialisation,
      rulesDirectoryName,
      prettyPrinter,
      gitModificationRepository,
      RUDDER_CHARSET.name,
      RUDDER_GROUP_OWNER_CONFIG_REPO
    )
    lazy val gitRuleCategoryArchiver:            GitRuleCategoryArchiver            = new GitRuleCategoryArchiverImpl(
      gitConfigRepo,
      ruleCategorySerialisation,
      ruleCategoriesDirectoryName,
      prettyPrinter,
      gitModificationRepository,
      RUDDER_CHARSET.name,
      "category.xml",
      RUDDER_GROUP_OWNER_CONFIG_REPO
    )
    lazy val gitActiveTechniqueCategoryArchiver: GitActiveTechniqueCategoryArchiver = {
      new GitActiveTechniqueCategoryArchiverImpl(
        gitConfigRepo,
        activeTechniqueCategorySerialisation,
        userLibraryDirectoryName,
        prettyPrinter,
        gitModificationRepository,
        RUDDER_CHARSET.name,
        "category.xml",
        RUDDER_GROUP_OWNER_CONFIG_REPO
      )
    }
    lazy val gitActiveTechniqueArchiver:         GitActiveTechniqueArchiverImpl     = new GitActiveTechniqueArchiverImpl(
      gitConfigRepo,
      activeTechniqueSerialisation,
      userLibraryDirectoryName,
      prettyPrinter,
      gitModificationRepository,
      Buffer(),
      RUDDER_CHARSET.name,
      "activeTechniqueSettings.xml",
      RUDDER_GROUP_OWNER_CONFIG_REPO
    )
    lazy val gitDirectiveArchiver:               GitDirectiveArchiver               = new GitDirectiveArchiverImpl(
      gitConfigRepo,
      directiveSerialisation,
      userLibraryDirectoryName,
      prettyPrinter,
      gitModificationRepository,
      RUDDER_CHARSET.name,
      RUDDER_GROUP_OWNER_CONFIG_REPO
    )
    lazy val gitNodeGroupArchiver:               GitNodeGroupArchiver               = new GitNodeGroupArchiverImpl(
      gitConfigRepo,
      nodeGroupSerialisation,
      nodeGroupCategorySerialisation,
      groupLibraryDirectoryName,
      prettyPrinter,
      gitModificationRepository,
      RUDDER_CHARSET.name,
      "category.xml",
      RUDDER_GROUP_OWNER_CONFIG_REPO
    )
    lazy val gitParameterArchiver:               GitParameterArchiver               = new GitParameterArchiverImpl(
      gitConfigRepo,
      globalParameterSerialisation,
      parametersDirectoryName,
      prettyPrinter,
      gitModificationRepository,
      RUDDER_CHARSET.name,
      RUDDER_GROUP_OWNER_CONFIG_REPO
    )
    ////////////// MUTEX FOR rwLdap REPOS //////////////

    lazy val uptLibReadWriteMutex    = new ZioTReentrantLock("directive-lock")
    lazy val groupLibReadWriteMutex  = new ZioTReentrantLock("group-lock")
    lazy val nodeReadWriteMutex      = new ZioTReentrantLock("node-lock")
    lazy val parameterReadWriteMutex = new ZioTReentrantLock("parameter-lock")
    lazy val ruleReadWriteMutex      = new ZioTReentrantLock("rule-lock")
    lazy val ruleCatReadWriteMutex   = new ZioTReentrantLock("rule-cat-lock")

    lazy val roLdapDirectiveRepository =
      new RoLDAPDirectiveRepository(rudderDitImpl, roLdap, ldapEntityMapper, techniqueRepositoryImpl, uptLibReadWriteMutex)
    lazy val roDirectiveRepository: RoDirectiveRepository = roLdapDirectiveRepository
    lazy val woLdapDirectiveRepository = {
      val repo = new WoLDAPDirectiveRepository(
        roLdapDirectiveRepository,
        rwLdap,
        ldapDiffMapper,
        logRepository,
        stringUuidGenerator,
        gitDirectiveArchiver,
        gitActiveTechniqueArchiver,
        gitActiveTechniqueCategoryArchiver,
        personIdentServiceImpl,
        RUDDER_AUTOARCHIVEITEMS
      )

      gitActiveTechniqueArchiver.uptModificationCallback += new UpdatePiOnActiveTechniqueEvent(
        gitDirectiveArchiver,
        techniqueRepositoryImpl,
        roLdapDirectiveRepository
      )

      techniqueRepositoryImpl.registerCallback(
        new SaveDirectivesOnTechniqueCallback(
          "SaveDirectivesOnTechniqueCallback",
          100,
          directiveEditorServiceImpl,
          roLdapDirectiveRepository,
          repo
        )
      )

      repo
    }
    lazy val woDirectiveRepository: WoDirectiveRepository = woLdapDirectiveRepository

    lazy val roLdapRuleRepository =
      new RoLDAPRuleRepository(rudderDitImpl, roLdap, ldapEntityMapper, ruleReadWriteMutex)
    lazy val roRuleRepository: RoRuleRepository = roLdapRuleRepository

    lazy val woLdapRuleRepository: WoRuleRepository = new WoLDAPRuleRepository(
      roLdapRuleRepository,
      rwLdap,
      ldapDiffMapper,
      roLdapNodeGroupRepository,
      logRepository,
      gitRuleArchiver,
      personIdentServiceImpl,
      RUDDER_AUTOARCHIVEITEMS
    )
    lazy val woRuleRepository = woLdapRuleRepository

    lazy val roLdapNodeGroupRepository = new RoLDAPNodeGroupRepository(
      rudderDitImpl,
      roLdap,
      ldapEntityMapper,
      nodeFactRepository,
      groupLibReadWriteMutex
    )
    lazy val roNodeGroupRepository: RoNodeGroupRepository = roLdapNodeGroupRepository

    lazy val woLdapNodeGroupRepository = new WoLDAPNodeGroupRepository(
      roLdapNodeGroupRepository,
      rwLdap,
      ldapDiffMapper,
      stringUuidGenerator,
      logRepository,
      gitNodeGroupArchiver,
      personIdentServiceImpl,
      RUDDER_AUTOARCHIVEITEMS
    )
    lazy val woNodeGroupRepository: WoNodeGroupRepository = woLdapNodeGroupRepository

    lazy val roLDAPRuleCategoryRepository = {
      new RoLDAPRuleCategoryRepository(
        rudderDitImpl,
        roLdap,
        ldapEntityMapper,
        ruleCatReadWriteMutex
      )
    }

    lazy val woLDAPRuleCategoryRepository = {
      new WoLDAPRuleCategoryRepository(
        roLDAPRuleCategoryRepository,
        rwLdap,
        stringUuidGenerator,
        gitRuleCategoryArchiver,
        personIdentServiceImpl,
        RUDDER_AUTOARCHIVEITEMS
      )
    }

    lazy val roLDAPParameterRepository = new RoLDAPParameterRepository(
      rudderDitImpl,
      roLdap,
      ldapEntityMapper,
      parameterReadWriteMutex
    )

    lazy val woLDAPParameterRepository = new WoLDAPParameterRepository(
      roLDAPParameterRepository,
      rwLdap,
      ldapDiffMapper,
      logRepository,
      gitParameterArchiver,
      personIdentServiceImpl,
      RUDDER_AUTOARCHIVEITEMS
    )

    lazy val itemArchiveManagerImpl = new ItemArchiveManagerImpl(
      roLdapRuleRepository,
      woLdapRuleRepository,
      roLDAPRuleCategoryRepository,
      roLdapDirectiveRepository,
      roLdapNodeGroupRepository,
      roLDAPParameterRepository,
      woLDAPParameterRepository,
      gitConfigRepo,
      gitRuleArchiver,
      gitRuleCategoryArchiver,
      gitActiveTechniqueCategoryArchiver,
      gitActiveTechniqueArchiver,
      gitNodeGroupArchiver,
      gitParameterArchiver,
      parseRules,
      parseActiveTechniqueLibrary,
      parseGlobalParameter,
      parseRuleCategories,
      importTechniqueLibrary,
      parseGroupLibrary,
      importGroupLibrary,
      importRuleCategoryLibrary,
      logRepository,
      asyncDeploymentAgentImpl,
      gitModificationRepository,
      dynGroupUpdaterService
    )
    lazy val itemArchiveManager: ItemArchiveManager = itemArchiveManagerImpl

    lazy val globalComplianceModeService: ComplianceModeService   = {
      new ComplianceModeServiceImpl(
        () => configService.rudder_compliance_mode_name(),
        () => configService.rudder_compliance_heartbeatPeriod()
      )
    }
    lazy val globalAgentRunService:       AgentRunIntervalService = {
      new AgentRunIntervalServiceImpl(
        () => configService.agent_run_interval().toBox,
        () => configService.agent_run_start_hour().toBox,
        () => configService.agent_run_start_minute().toBox,
        () => configService.agent_run_splaytime().toBox,
        () => configService.rudder_compliance_heartbeatPeriod().toBox
      )
    }

    lazy val systemVariableService: SystemVariableService = new SystemVariableServiceImpl(
      systemVariableSpecService,
      psMngtService,
      instanceIdService,
      RUDDER_DIR_DEPENDENCIES,
      CFENGINE_POLICY_DISTRIBUTION_PORT,
      HTTPS_POLICY_DISTRIBUTION_PORT,
      RUDDER_DIR_SHARED_FILES_FOLDER,
      RUDDER_WEBDAV_USER,
      RUDDER_WEBDAV_PASSWORD,
      RUDDER_JDBC_URL,
      RUDDER_JDBC_USERNAME,
      RUDDER_JDBC_PASSWORD,
      RUDDER_GIT_ROOT_CONFIG_REPO,
      rudderFullVersion,
      RUDDER_SERVER_CERTIFICATE_CONFIG,
      () => configService.cfengine_server_denybadclocks().toBox,
      () => configService.relay_server_sync_method().toBox,
      () => configService.relay_server_syncpromises().toBox,
      () => configService.relay_server_syncsharedfiles().toBox,
      () => configService.cfengine_modified_files_ttl().toBox,
      () => configService.cfengine_outputs_ttl().toBox,
      () => configService.rudder_report_protocol_default().toBox
    )
    lazy val rudderCf3PromisesFileWriterService = new PolicyWriterServiceImpl(
      techniqueRepositoryImpl,
      pathComputer,
      new NodeConfigurationLoggerImpl(RUDDER_DEBUG_NODE_CONFIGURATION_PATH),
      new PrepareTemplateVariablesImpl(
        techniqueRepositoryImpl,
        systemVariableSpecService,
        new BuildBundleSequence(systemVariableSpecService, writeAllAgentSpecificFiles),
        agentRegister
      ),
      new FillTemplatesService(),
      writeAllAgentSpecificFiles,
      HOOKS_D,
      HOOKS_IGNORE_SUFFIXES,
      RUDDER_CHARSET.value,
      Some(RUDDER_GROUP_OWNER_GENERATED_POLICIES)
    )

    // must be here because of circular dependency if in techniqueRepository
    techniqueRepositoryImpl.registerCallback(
      new TechniqueAcceptationUpdater(
        "UpdatePTAcceptationDatetime",
        50,
        roLdapDirectiveRepository,
        woLdapDirectiveRepository,
        techniqueRepository,
        stringUuidGenerator
      )
    )

    lazy val techniqueRepositoryImpl = {
      val service = new TechniqueRepositoryImpl(
        techniqueReader,
        Seq(),
        stringUuidGenerator
      )
      service
    }
    lazy val techniqueRepository: TechniqueRepository = techniqueRepositoryImpl
    lazy val updateTechniqueLibrary: UpdateTechniqueLibrary = techniqueRepositoryImpl
    lazy val interpolationCompiler = new InterpolatedValueCompilerImpl(propertyEngineService)
    lazy val typeParameterService: PlugableParameterTypeService = new PlugableParameterTypeService()
    lazy val ruleValService:       RuleValService               = new RuleValServiceImpl(interpolationCompiler)

    lazy val psMngtService: PolicyServerManagementService = new PolicyServerManagementServiceImpl(
      rwLdap,
      rudderDit,
      eventLogRepository
    )
    lazy val policyServerManagementService = psMngtService
    lazy val ruleValGeneratedHookService   = new RuleValGeneratedHookService()
    lazy val deploymentService             = {
      new PromiseGenerationServiceImpl(
        roLdapRuleRepository,
        woLdapRuleRepository,
        ruleValService,
        systemVariableService,
        nodeConfigurationHashRepo,
        nodeFactRepository,
        propertiesRepository,
        updateExpectedRepo,
        roNodeGroupRepository,
        roDirectiveRepository,
        configurationRepository,
        ruleApplicationStatusImpl,
        roParameterServiceImpl,
        interpolationCompiler,
        globalComplianceModeService,
        globalAgentRunService,
        findNewNodeStatusReports,
        rudderCf3PromisesFileWriterService,
        new WriteNodeCertificatesPemImpl(Some(RUDDER_RELAY_RELOAD)),
        cachedNodeConfigurationService,
        () => configService.rudder_featureSwitch_directiveScriptEngine().toBox,
        () => configService.rudder_global_policy_mode().toBox,
        () => configService.rudder_generation_compute_dyngroups().toBox,
        () => configService.rudder_generation_max_parallelism().toBox,
        () => configService.rudder_generation_js_timeout().toBox,
        () => configService.rudder_generation_continue_on_error().toBox,
        HOOKS_D,
        HOOKS_IGNORE_SUFFIXES,
        UPDATED_NODE_IDS_PATH,
        UPDATED_NODE_IDS_COMPABILITY,
        GENERATION_FAILURE_MSG_PATH,
        allNodeCertificatesPemFile = better.files.File("/var/rudder/lib/ssl/allnodescerts.pem"),
        POSTGRESQL_IS_LOCAL,
        ruleValGeneratedHookService
      )
    }

    lazy val policyGenerationBootGuard = zio.Promise.make[Nothing, Unit].runNow

    lazy val asyncDeploymentAgentImpl: AsyncDeploymentActor = {
      val agent = new AsyncDeploymentActor(
        deploymentService,
        eventLogDeploymentServiceImpl,
        deploymentStatusSerialisation,
        () => configService.rudder_generation_delay(),
        () => configService.rudder_generation_trigger(),
        policyGenerationBootGuard
      )
      techniqueRepositoryImpl.registerCallback(
        new DeployOnTechniqueCallback("DeployOnPTLibUpdate", 1000, agent)
      )
      agent
    }
    lazy val asyncDeploymentAgent = asyncDeploymentAgentImpl

    lazy val newNodeManagerImpl = {

      // the sequence of unit process to accept a new inventory
      val unitAcceptors = {
        acceptHostnameAndIp :: defaultStateAndPolicyMode ::
        Nil
      }

      // the sequence of unit process to refuse a new inventory
      val unitRefusors = {
        unitRefuseGroup ::
        Nil
      }
      val hooksRunner  = new NewNodeManagerHooksImpl(nodeFactRepository, HOOKS_D, HOOKS_IGNORE_SUFFIXES)

      val composedManager = new ComposedNewNodeManager[Unit](
        nodeFactRepository,
        unitAcceptors,
        unitRefusors,
        hooksRunner
      )
      val listNodes       = new FactListNewNodes(nodeFactRepository)

      new NewNodeManagerImpl[Unit](
        composedManager,
        listNodes
      )
    }

    /// score ///

    lazy val globalScoreRepository = new GlobalScoreRepositoryImpl(doobie)
    lazy val scoreRepository       = new ScoreRepositoryImpl(doobie)
    lazy val scoreService          = new ScoreServiceImpl(globalScoreRepository, scoreRepository, nodeFactRepository)
    lazy val scoreServiceManager: ScoreServiceManager = new ScoreServiceManager(scoreService)

    deferredEffects.append(scoreService.init())
    deferredEffects.append(scoreServiceManager.registerHandler(new SystemUpdateScoreHandler(nodeFactRepository)))

    /////// reporting ///////

    lazy val nodeConfigurationHashRepo: NodeConfigurationHashRepository = {
      val x = new FileBasedNodeConfigurationHashRepository(FileBasedNodeConfigurationHashRepository.defaultHashesPath)
      x.init
      x
    }

    lazy val findNewNodeStatusReports: FindNewNodeStatusReports = new FindNewNodeStatusReportsImpl(
      findExpectedRepo,
      cachedNodeConfigurationService,
      reportsRepository,
      roAgentRunsRepository,
      () => globalComplianceModeService.getGlobalComplianceMode,
      RUDDER_JDBC_BATCH_MAX_SIZE
    )

    // we need to expose impl at that level to call `.init()` in bootstrap checks
    lazy val nodeStatusReportRepository: NodeStatusReportRepositoryImpl = {
      /*
       * We added the table in Rudder 8.2, and so for migration, we need a bit of
       * logic to check if we init compliance from table or from runs, see: LoadNodeComplianceCache.
       * And so, we are for initing it to empty here.
       * In Rudder 9.0, we will be able to remove the bootstrap check and just use
       * `NodeStatusReportRepositoryImpl.make`.
       */
      (for {
        x <- Ref.make(Map[NodeId, NodeStatusReport]())
        s  = new JdbcNodeStatusReportStorage(doobie, RUDDER_JDBC_BATCH_MAX_SIZE)
      } yield new NodeStatusReportRepositoryImpl(s, x)).runNow
    }

    lazy val computeNodeStatusReportService: ComputeNodeStatusReportService & HasNodeStatusReportUpdateHook = {
      new ComputeNodeStatusReportServiceImpl(
        nodeFactRepository,
        nodeStatusReportRepository,
        findNewNodeStatusReports,
        new NodePropertyBasedComplianceExpirationService(
          propertiesRepository,
          NodePropertyBasedComplianceExpirationService.PROP_NAME,
          NodePropertyBasedComplianceExpirationService.PROP_SUB_NAME
        ),
        Ref.make(Chunk[NodeStatusReportUpdateHook](new ScoreNodeStatusReportUpdateHook(scoreServiceManager))).runNow,
        RUDDER_JDBC_BATCH_MAX_SIZE
      )
    }

    // to avoid a StackOverflowError, we set the compliance cache once it's ready,
    // and can construct the nodeConfigurationService without the compliance cache
    cachedNodeConfigurationService.addHook(computeNodeStatusReportService)

    lazy val reportingService: ReportingService = new ReportingServiceImpl2(nodeStatusReportRepository)

    lazy val pgIn                  = new PostgresqlInClause(70)
    lazy val findExpectedRepo      = new FindExpectedReportsJdbcRepository(doobie, pgIn, RUDDER_JDBC_BATCH_MAX_SIZE)
    lazy val updateExpectedRepo    = new UpdateExpectedReportsJdbcRepository(doobie, pgIn, RUDDER_JDBC_BATCH_MAX_SIZE)
    lazy val reportsRepositoryImpl = new ReportsJdbcRepository(doobie)
    lazy val reportsRepository     = reportsRepositoryImpl
    lazy val dataSourceProvider    = new RudderDatasourceProvider(
      RUDDER_JDBC_DRIVER,
      RUDDER_JDBC_URL,
      RUDDER_JDBC_USERNAME,
      RUDDER_JDBC_PASSWORD,
      RUDDER_JDBC_MAX_POOL_SIZE,
      JDBC_GET_CONNECTION_TIMEOUT.asScala
    )
    lazy val doobie                = {
      // execute pre connection checks before anything else. Both here and in the other connection to ensure it's
      // executed before everything.
      earlyChecks.initialize()

      val doobie = new Doobie(dataSourceProvider.datasource)

      // a sequence and migration that should be executed as soon as we have a connection, and
      // before other services are init, because perhaps they will need the changes here.
      val earlyDbChecks = new SequentialImmediateBootStrapChecks(
        "early PostgreSQL checks",
        BootstrapLogger.Early.DB,
        new CheckPostgreConnection(dataSourceProvider),
        new CreateTableNodeFacts(doobie),
        new CheckTableScore(doobie),
        new CheckTableUsers(doobie),
        new CheckTableNodeLastCompliance(doobie),
        new MigrateEventLogEnforceSchema(doobie),
        new MigrateChangeValidationEnforceSchema(doobie),
        new CheckTableReportsExecutionTz(doobie),
        new DeleteArchiveTables(doobie),
        new MigrateTableCampaignEvents(doobie)
      )

      earlyDbChecks.checks()
      doobie
    }

    lazy val parseRules:                  ParseRules & RuleRevisionRepository         = new GitParseRules(
      ruleUnserialisation,
      gitConfigRepo,
      rulesDirectoryName
    )
    lazy val parseActiveTechniqueLibrary: GitParseActiveTechniqueLibrary              = new GitParseActiveTechniqueLibrary(
      activeTechniqueCategoryUnserialisation,
      activeTechniqueUnserialisation,
      directiveUnserialisation,
      gitConfigRepo,
      gitRevisionProvider,
      userLibraryDirectoryName
    )
    lazy val importTechniqueLibrary:      ImportTechniqueLibrary                      = new ImportTechniqueLibraryImpl(
      rudderDitImpl,
      rwLdap,
      ldapEntityMapper,
      uptLibReadWriteMutex
    )
    lazy val parseGroupLibrary:           ParseGroupLibrary & GroupRevisionRepository = new GitParseGroupLibrary(
      nodeGroupCategoryUnserialisation,
      nodeGroupUnserialisation,
      gitConfigRepo,
      groupLibraryDirectoryName
    )
    lazy val parseGlobalParameter:        ParseGlobalParameters                       = new GitParseGlobalParameters(
      globalParameterUnserialisation,
      gitConfigRepo,
      parametersDirectoryName
    )
    lazy val parseRuleCategories:         ParseRuleCategories                         = new GitParseRuleCategories(
      ruleCategoryUnserialisation,
      gitConfigRepo,
      ruleCategoriesDirectoryName
    )
    lazy val importGroupLibrary:          ImportGroupLibrary                          = new ImportGroupLibraryImpl(
      rudderDitImpl,
      rwLdap,
      ldapEntityMapper,
      groupLibReadWriteMutex
    )
    lazy val importRuleCategoryLibrary:   ImportRuleCategoryLibrary                   = new ImportRuleCategoryLibraryImpl(
      rudderDitImpl,
      rwLdap,
      ldapEntityMapper,
      ruleCatReadWriteMutex
    )
    lazy val eventLogDeploymentServiceImpl = new EventLogDeploymentService(logRepository, eventLogDetailsServiceImpl)

    lazy val dependencyAndDeletionService: DependencyAndDeletionService = new DependencyAndDeletionServiceImpl(
      new FindDependenciesImpl(roLdap, rudderDitImpl, ldapEntityMapper),
      roLdapDirectiveRepository,
      woLdapDirectiveRepository,
      woLdapRuleRepository,
      woLdapNodeGroupRepository
    )

    lazy val logDisplayerImpl:               LogDisplayer               =
      new LogDisplayer(reportsRepositoryImpl, configurationRepository, roLdapRuleRepository)
    lazy val categoryHierarchyDisplayerImpl: CategoryHierarchyDisplayer = new CategoryHierarchyDisplayer()
    lazy val dyngroupUpdaterBatch:           UpdateDynamicGroups        = new UpdateDynamicGroups(
      dynGroupServiceImpl,
      dynGroupUpdaterService,
      propertiesSyncService,
      asyncDeploymentAgentImpl,
      stringUuidGenerator,
      RUDDER_BATCH_DYNGROUP_UPDATEINTERVAL,
      () => configService.rudder_compute_dyngroups_max_parallelism().toBox
    )
    lazy val updateDynamicGroups = dyngroupUpdaterBatch

    lazy val dynGroupUpdaterService =
      new DynGroupUpdaterServiceImpl(roLdapNodeGroupRepository, woLdapNodeGroupRepository, queryProcessor)

    lazy val dbCleaner: AutomaticReportsCleaning = {
      val cleanFrequency = AutomaticReportsCleaning.buildFrequency(
        RUDDER_BATCH_REPORTSCLEANER_FREQUENCY,
        RUDDER_BATCH_DATABASECLEANER_RUNTIME_MINUTE,
        RUDDER_BATCH_DATABASECLEANER_RUNTIME_HOUR,
        RUDDER_BATCH_DATABASECLEANER_RUNTIME_DAY
      ) match {
        case Full(freq) => freq
        case eb: EmptyBox =>
          val fail         = eb ?~! "automatic reports cleaner is not correct"
          val exceptionMsg =
            "configuration file (/opt/rudder/etc/rudder-webapp.conf) is not correctly set, cause is %s".format(fail.msg)
          throw new RuntimeException(exceptionMsg)
      }

      new AutomaticReportsCleaning(
        databaseManagerImpl,
        roLDAPConnectionProvider,
        RUDDER_BATCH_REPORTSCLEANER_DELETE_TTL,
        RUDDER_BATCH_REPORTSCLEANER_ARCHIVE_TTL,
        RUDDER_BATCH_REPORTSCLEANER_COMPLIANCE_DELETE_TTL,
        RUDDER_BATCH_REPORTSCLEANER_LOG_DELETE_TTL,
        cleanFrequency
      )
    }

    lazy val techniqueLibraryUpdater = new CheckTechniqueLibrary(
      techniqueRepositoryImpl,
      techniqueCompilationStatusService,
      stringUuidGenerator,
      RUDDER_BATCH_TECHNIQUELIBRARY_UPDATEINTERVAL
    )

    lazy val jsTreeUtilServiceImpl = new JsTreeUtilService(roLdapDirectiveRepository, techniqueRepositoryImpl)

    /*
     * Cleaning actions are run in the case where the node was accepted, deleted, and unknown
     * (ie: we want to be able to run cleaning actions even on a node that was deleted in the past, but
     * for some reason the user discovers that there are remaining things, and they want to get rid of them
     * without knowing rudder internal place to look for all possible garbage)
     */

    /*
     * The list of post deletion action to execute in a shared reference, created at class instanciation.
     * External services can update it from removeNodeServiceImpl.
     */
    lazy val postNodeDeleteActions = Ref
      .make(
        //      new RemoveNodeInfoFromCache(ldapNodeInfoServiceImpl)
        new RemoveNodeFromGroups(roNodeGroupRepository, woNodeGroupRepository, stringUuidGenerator)
        :: new CloseNodeConfiguration(updateExpectedRepo)
        :: new DeletePolicyServerPolicies(policyServerManagementService)
        :: new ResetKeyStatus(rwLdap, removedNodesDitImpl)
        :: new CleanUpCFKeys()
        :: new CleanUpNodePolicyFiles("/var/rudder/share")
        :: Nil
      )
      .runNow

    lazy val factRemoveNodeBackend = new FactRemoveNodeBackend(nodeFactRepository)

    lazy val removeNodeServiceImpl = new RemoveNodeServiceImpl(
      //    deprecated.ldapRemoveNodeBackend,
      factRemoveNodeBackend,
      nodeFactRepository,
      pathComputer,
      newNodeManagerImpl,
      postNodeDeleteActions,
      HOOKS_D,
      HOOKS_IGNORE_SUFFIXES
    )

    lazy val healthcheckService = new HealthcheckService(
      List(
        CheckCoreNumber,
        CheckFreeSpace,
        new CheckFileDescriptorLimit(nodeFactRepository)
      )
    )

    lazy val healthcheckNotificationService = new HealthcheckNotificationService(healthcheckService, RUDDER_HEALTHCHECK_PERIOD)
    lazy val campaignSerializer             = new CampaignSerializer()
    lazy val campaignEventRepo              = new CampaignEventRepositoryImpl(doobie, campaignSerializer)
    lazy val campaignHooksRepository        = new FsCampaignHooksRepository(HOOKS_D)
    lazy val campaignHooksService           = new FsCampaignHooksService(HOOKS_D, HOOKS_IGNORE_SUFFIXES)
    lazy val campaignArchiver               = new CampaignArchiverImpl(gitConfigRepo, "campaigns", personIdentService)

    lazy val campaignRepo = CampaignRepositoryImpl
      .make(campaignArchiver.campaignPath, campaignArchiver, campaignSerializer, campaignHooksRepository)
      .runOrDie(err => new RuntimeException(s"Error during initialization of campaign repository: " + err.fullMsg))

    lazy val mainCampaignService =
      MainCampaignService.make(campaignEventRepo, campaignRepo, campaignHooksService, stringUuidGenerator, 1, 1).runNow
    lazy val jsonReportsAnalyzer = JSONReportsAnalyser(reportsRepository, propertyRepository)

    lazy val instanceUuidPath    = root / "opt" / "rudder" / "etc" / "instance-id"
    lazy val instanceIdGenerator = new InstanceIdGeneratorImpl()
    lazy val instanceIdService   = InstanceIdService.make(instanceUuidPath, instanceIdGenerator).runNow

    /*
     * *************************************************
     * Bootstrap check actions
     * **************************************************
     */

    // These checks are done very early, before even looking for LDAP connection.
    // They should not use any dependencies that should not be part of the root of dependency tree
    // for initialisation of services.
    lazy val earlyChecks = new OnceBootstrapChecks(
      "pre-LDAP/DB-connection checks",
      BootstrapLogger.Early,
      new CreateInstanceUuid(instanceUuidPath, instanceIdService)
    )

    // These checks are done at the end of service instantiation
    lazy val allBootstrapChecks = new SequentialImmediateBootStrapChecks(
      "post-service instantiation checks",
      BootstrapLogger,
      new MigrateNodeAcceptationInventories(
        nodeFactRepository,
        inventoryHistoryLogRepository,
        inventoryHistoryJdbcRepository,
        KEEP_DELETED_NODE_FACT_DURATION
      ),
      new CheckTechniqueLibraryReload(
        techniqueRepositoryImpl,
        stringUuidGenerator
      ),
      new CheckTechniqueCompilationStatus(techniqueCompilationCache),
      new CheckDIT(pendingNodesDitImpl, acceptedNodesDitImpl, removedNodesDitImpl, rudderDitImpl, rwLdap),
      new CheckUsersFile(rudderUserListProvider),
      new CheckInitUserTemplateLibrary(
        rudderDitImpl,
        rwLdap,
        techniqueRepositoryImpl,
        roLdapDirectiveRepository,
        woLdapDirectiveRepository,
        stringUuidGenerator,
        asyncDeploymentAgentImpl
      ), // new CheckDirectiveBusinessRules()

      new CheckRudderGlobalParameter(roLDAPParameterRepository, woLDAPParameterRepository, stringUuidGenerator),
      new CheckInitXmlExport(itemArchiveManagerImpl, personIdentServiceImpl, stringUuidGenerator),
      new CheckNcfTechniqueUpdate(
        ncfTechniqueWriter,
        roLDAPApiAccountRepository.systemAPIAccount,
        stringUuidGenerator,
        updateTechniqueLibrary,
        ncfTechniqueReader,
        resourceFileService
      ),
      new MigrateJsonTechniquesToYaml(
        ncfTechniqueWriter,
        stringUuidGenerator,
        updateTechniqueLibrary,
        techniqueCompilationStatusService,
        gitConfigRepo.rootDirectory.pathAsString
      ),
      new FixedPathLoggerMigration(),
      new DropNodeComplianceTables(doobie),
      new TriggerPolicyUpdate(
        asyncDeploymentAgent,
        stringUuidGenerator
      ),
      new RemoveFaultyLdapEntries(
        woDirectiveRepository,
        stringUuidGenerator
      ),
      new RemoveDefaultRootDescription(nodeFactRepository),
      new CreateSystemToken(
        systemTokenSecret,
        root / "var" / "rudder" / "run",
        RestAuthenticationFilter.API_TOKEN_HEADER
      ),
      new LoadNodeComplianceCache(nodeStatusReportRepository, nodeFactRepository, computeNodeStatusReportService, doobie),
      new CloseOpenUserSessions(userRepository)
    )

    //////////////////////////////////////////////////////////////////////////////////////////
    ////////////////////////////// Directive Editor and web fields //////////////////////////////
    //////////////////////////////////////////////////////////////////////////////////////////

    import com.normation.cfclerk.domain.*

    object FieldFactoryImpl extends DirectiveFieldFactory {
      // only one field

      override def forType(v: VariableSpec, id: String): DirectiveField = {
        val prefixSize = "size-"
        v match {
          case selectOne:       SelectOneVariableSpec        => new SelectOneField(id, selectOne.valueslabels)
          case select:          SelectVariableSpec           => new SelectField(id, select.valueslabels)
          case input:           InputVariableSpec            =>
            v.constraint.typeName match {
              case str: SizeVType =>
                new InputSizeField(
                  id,
                  () => configService.rudder_featureSwitch_directiveScriptEngine().toBox,
                  str.name.substring(prefixSize.size)
                )
              case SharedFileVType            => new FileField(id)
              case DestinationPathVType       => default(id)
              case DateVType(_)               => new DateField(Translator.isoDateFormatter)(id)
              case TimeVType(_)               => new TimeField(Translator.isoTimeFormatter)(id)
              case PermVType                  => new FilePermsField(id)
              case BooleanVType               => new CheckboxField(id)
              case TextareaVType(_)           =>
                new TextareaField(id, () => configService.rudder_featureSwitch_directiveScriptEngine().toBox)
              // Same field type for password and MasterPassword, difference is that master will have slave/used derived passwords, and password will not have any slave/used field
              case PasswordVType(algos)       =>
                new PasswordField(
                  id,
                  algos,
                  input.constraint.mayBeEmpty,
                  () => configService.rudder_featureSwitch_directiveScriptEngine().toBox
                )
              case MasterPasswordVType(algos) =>
                new PasswordField(
                  id,
                  algos,
                  input.constraint.mayBeEmpty,
                  () => configService.rudder_featureSwitch_directiveScriptEngine().toBox
                )
              case LinuxDerivedPasswordVType  => new DerivedPasswordField(id, HashAlgoConstraint.DerivedPasswordType.Linux)
              case _                          => default(id)
            }
          case predefinedField: PredefinedValuesVariableSpec => new ReadOnlyTextField(id)

          case _ =>
            logger.error(
              "Unexpected case : variable %s should not be displayed. Only select1, select or input can be displayed.".format(
                v.name
              )
            )
            default(id)
        }
      }

      override def default(id: String): DirectiveField =
        new TextField(id, () => configService.rudder_featureSwitch_directiveScriptEngine().toBox)
    }

    lazy val section2FieldService:       Section2FieldService   = {
      new Section2FieldService(FieldFactoryImpl, Translator.defaultTranslators)
    }
    lazy val directiveEditorServiceImpl: DirectiveEditorService =
      new DirectiveEditorServiceImpl(configurationRepository, section2FieldService)
    lazy val directiveEditorService = directiveEditorServiceImpl

    lazy val reportDisplayerImpl = new ReportDisplayer(
      roLdapRuleRepository,
      roLdapDirectiveRepository,
      nodeFactRepository,
      configService,
      logDisplayerImpl
    )
    lazy val propertyRepository  = new RudderPropertiesRepositoryImpl(doobie)
    lazy val autoReportLogger    = new AutomaticReportLogger(
      propertyRepository,
      reportsRepositoryImpl,
      roLdapRuleRepository,
      roLdapDirectiveRepository,
      nodeFactRepository,
      RUDDER_BATCH_REPORTS_LOGINTERVAL
    )

    lazy val scriptLauncher = new DebugInfoServiceImpl
    lazy val debugScript    = scriptLauncher

    ////////////////////// Snippet plugins & extension register //////////////////////
    lazy val snippetExtensionRegister: SnippetExtensionRegister = new SnippetExtensionRegisterImpl()
    snippetExtensionRegister.register(new RudderCompanyAccount())
    snippetExtensionRegister.register(new PolicyBackup())

    lazy val cachedNodeConfigurationService: CachedNodeConfigurationService = {
      val cached = new CachedNodeConfigurationService(findExpectedRepo, nodeFactRepository)
      cached.init().runOrDie(err => new RuntimeException(s"Error when initializing node configuration cache: " + err))
      cached
    }

    /*
     * Agent runs: we use a cache for them.
     */
    lazy val cachedAgentRunRepository = {
      val roRepo = new RoReportsExecutionRepositoryImpl(
        doobie,
        new WoReportsExecutionRepositoryImpl(doobie),
        cachedNodeConfigurationService,
        pgIn,
        RUDDER_JDBC_BATCH_MAX_SIZE
      )
      new CachedReportsExecutionRepository(
        roRepo
      )
    }
    lazy val roAgentRunsRepository: RoReportsExecutionRepository = cachedAgentRunRepository

    lazy val updatesEntryJdbcRepository = new LastProcessedReportRepositoryImpl(doobie)

    lazy val executionService = {
      new ReportsExecutionService(
        reportsRepository,
        updatesEntryJdbcRepository,
        recentChangesService,
        computeNodeStatusReportService,
        findNewNodeStatusReports
      )
    }

    lazy val aggregateReportScheduler = new FindNewReportsExecution(executionService, RUDDER_REPORTS_EXECUTION_INTERVAL)

    // aggregate information about node count
    // don't forget to start-it once out of the zone which lead to dead-lock (ie: in Lift boot)
    lazy val getNodeMetrics: FetchDataService =
      new FetchDataServiceImpl(nodeFactRepository, reportingService)

    lazy val historizeNodeCountBatch = for {
      gitLogger <- CommitLogServiceImpl.make(METRICS_NODES_DIRECTORY_GIT_ROOT)
      writer    <- WriteNodeCSV.make(METRICS_NODES_DIRECTORY_GIT_ROOT, ';', "yyyy-MM")
      service    = new HistorizeNodeCountService(
                     getNodeMetrics,
                     writer,
                     gitLogger,
                     DateTimeZone.UTC // never change log line
                   )
      cron      <- Scheduler.make(
                     METRICS_NODES_MIN_PERIOD,
                     METRICS_NODES_MAX_PERIOD,
                     s => service.scheduledLog(s),
                     "Automatic recording of active nodes".succeed
                   )
      _         <-
        ScheduledJobLoggerPure.metrics.info(
          s"Starting node count historization batch (min:${METRICS_NODES_MIN_PERIOD.render}; max:${METRICS_NODES_MAX_PERIOD.render})"
        )
      _         <- cron.start
    } yield ()

// provided as a callback on node fact repo
//    lazy val checkInventoryUpdate = new CheckInventoryUpdate(
//      nodeFactInfoService,
//      asyncDeploymentAgent,
//      stringUuidGenerator,
//      RUDDER_BATCH_CHECK_NODE_CACHE_INTERVAL
//    )

    lazy val asynComplianceService = new AsyncComplianceService(reportingService)

    /*
     * here goes deprecated services that we can't remove yet, for example because they are used for migration
     */
    object deprecated {
      lazy val ldapFullInventoryRepository = {
        new FullInventoryRepositoryImpl(
          inventoryDitService,
          inventoryMapper,
          rwLdap,
          INVENTORIES_THRESHOLD_PROCESSES_ISOLATED_WRITE
        )
      }

      lazy val softwareInventoryDAO: ReadOnlySoftwareDAO =
        new ReadOnlySoftwareDAOImpl(inventoryDitService, roLdap, inventoryMapper)

      lazy val softwareInventoryRWDAO: WriteOnlySoftwareDAO = new WriteOnlySoftwareDAOImpl(
        acceptedNodesDitImpl,
        rwLdap
      )

      lazy val softwareService: SoftwareService =
        new SoftwareServiceImpl(softwareInventoryDAO, softwareInventoryRWDAO, acceptedNodesDit)

      lazy val purgeUnreferencedSoftwares = {
        new PurgeUnreferencedSoftwares(
          softwareService,
          FiniteDuration(RUDDER_BATCH_DELETE_SOFTWARE_INTERVAL.toLong, "hours")
        )
      }

      lazy val purgeDeletedInventories = new PurgeDeletedInventories(
        new PurgeDeletedNodesImpl(rwLdap, removedNodesDitImpl, ldapFullInventoryRepository),
        FiniteDuration(RUDDER_BATCH_PURGE_DELETED_INVENTORIES_INTERVAL.toLong, "hours"),
        RUDDER_BATCH_PURGE_DELETED_INVENTORIES
      )

      lazy val ldapRemoveNodeBackend = new LdapRemoveNodeBackend(
        nodeDitImpl,
        pendingNodesDitImpl,
        acceptedNodesDitImpl,
        removedNodesDitImpl,
        rwLdap,
        ldapFullInventoryRepository,
        nodeReadWriteMutex
      )

      lazy val ldapSoftwareSave = new NameAndVersionIdFinder("check_name_and_version", roLdap, inventoryMapper, acceptedNodesDit)

      lazy val internalAcceptedQueryProcessor =
        new InternalLDAPQueryProcessor(roLdap, acceptedNodesDitImpl, nodeDit, ditQueryDataImpl, ldapEntityMapper)

      lazy val internalPendingQueryProcessor = {
        val subGroup = new SubGroupComparatorRepository {
          override def getNodeIds(groupId: NodeGroupId)(implicit qc: QueryContext): IOResult[Chunk[NodeId]] = Chunk.empty.succeed

          override def getGroups: IOResult[Chunk[SubGroupChoice]] = Chunk.empty.succeed
        }
        new InternalLDAPQueryProcessor(
          roLdap,
          pendingNodesDitImpl,
          nodeDit,
          // here, we don't want to look for subgroups to show them in the form => always return an empty list
          new DitQueryData(
            pendingNodesDitImpl,
            nodeDit,
            rudderDit,
            new NodeQueryCriteriaData(() => subGroup, instanceIdService)
          ),
          ldapEntityMapper
        )
      }

      lazy val woLdapNodeRepository: WoNodeRepository = new WoLDAPNodeRepository(
        nodeDitImpl,
        acceptedNodesDit,
        ldapEntityMapper,
        rwLdap,
        logRepository,
        nodeReadWriteMutex,
        cachedNodeConfigurationService,
        computeNodeStatusReportService
      )
    }

    // reference services part of the API
    val rci = RudderServiceApi(
      roLdap,
      pendingNodesDitImpl,
      acceptedNodesDitImpl,
      nodeDitImpl,
      rudderDit,
      roLdapRuleRepository,
      woRuleRepository,
      roLdapNodeGroupRepository,
      woLdapNodeGroupRepository,
      techniqueRepositoryImpl,
      techniqueArchiver,
      techniqueRepositoryImpl,
      roLdapDirectiveRepository,
      woLdapDirectiveRepository,
      deprecated.softwareInventoryDAO,
      eventLogRepository,
      eventLogDetailsServiceImpl,
      reportingService,
      complianceAPIService,
      asynComplianceService,
      scriptLauncher,
      queryParser,
      inventoryHistoryJdbcRepository,
      inventoryLogEventServiceImpl,
      ruleApplicationStatusImpl,
      propertyEngineService,
      newNodeManagerImpl,
      nodeGridImpl,
      jsTreeUtilServiceImpl,
      directiveEditorService,
      userPropertyService,
      eventListDisplayerImpl,
      asyncDeploymentAgent,
      policyServerManagementService,
      dynGroupUpdaterService,
      dyngroupUpdaterBatch,
      deprecated.purgeDeletedInventories,
      deprecated.purgeUnreferencedSoftwares,
      databaseManagerImpl,
      dbCleaner,
      techniqueLibraryUpdater,
      autoReportLogger,
      removeNodeServiceImpl,
      reportDisplayerImpl,
      dependencyAndDeletionService,
      itemArchiveManagerImpl,
      personIdentServiceImpl,
      gitRevisionProviderImpl,
      logDisplayerImpl,
      queryProcessor,
      categoryHierarchyDisplayerImpl,
      dynGroupServiceImpl,
      ditQueryDataImpl,
      reportsRepository,
      eventLogDeploymentServiceImpl,
      new SrvGrid(roAgentRunsRepository, configService, scoreService),
      findExpectedRepo,
      roLDAPApiAccountRepository,
      woLDAPApiAccountRepository,
      cachedAgentRunRepository,
      pendingNodeCheckGroup,
      allBootstrapChecks,
      authenticationProviders,
      rudderUserListProvider,
      restQuicksearch,
      restCompletion,
      sharedFileApi,
      eventLogApi,
      systemApiService11,
      stringUuidGenerator,
      inventoryWatcher,
      configService,
      historizeNodeCountBatch,
      policyGenerationBootGuard,
      healthcheckNotificationService,
      jsonPluginDefinition,
      pluginSettingsService,
      rudderApi,
      authorizationApiMapping,
      roleApiMapping,
      roRuleCategoryRepository,
      woRuleCategoryRepository,
      workflowLevelService,
      ncfTechniqueReader,
      recentChangesService,
      ruleCategoryService,
      snippetExtensionRegister,
      clearCacheService,
      linkUtil,
      userRepository,
      userService,
      SupportedApiVersion.apiVersions,
      apiDispatcher,
      configurationRepository,
      roParameterService,
      agentRegister,
      asyncWorkflowInfo,
      commitAndDeployChangeRequest,
      doobie,
      workflowEventLogService,
      changeRequestEventLogService,
      changeRequestChangesUnserialisation,
      diffService,
      diffDisplayer,
      rwLdap,
      apiAuthorizationLevelService,
      tokenGenerator,
      roLDAPParameterRepository,
      woLDAPParameterRepository,
      interpolationCompiler,
      deploymentService,
      campaignEventRepo,
      campaignRepo,
      mainCampaignService,
      campaignSerializer,
      jsonReportsAnalyzer,
      aggregateReportScheduler,
      secretEventLogService,
      changeRequestChangesSerialisation,
      gitConfigRepo,
      gitModificationRepository,
      inventorySaver,
      inventoryDitService,
      nodeFactRepository,
      scoreServiceManager,
      scoreService,
      tenantService,
      computeNodeStatusReportService,
      scoreRepository,
      propertiesRepository,
      propertiesService,
      techniqueCompilationCache,
      ruleValGeneratedHookService,
      instanceIdService,
      systemInfoService
    )

    // start init effects
    ZIO.collectAllParDiscard(deferredEffects).runNow

    // This needs to be done at the end, to be sure that all is initialized
    deploymentService.setDynamicsGroupsService(dyngroupUpdaterBatch)
    // we need to reference batches not part of the API to start them since
    // they are lazy val
    cleanOldInventoryBatch.start()
    gitFactRepoGC.start()
    gitConfigRepoGC.start()
    rudderUserListProvider.registerCallback(UserRepositoryUpdateOnFileReload.createCallback(userRepository))
    userCleanupBatch.start()

    // init node properties - don't fail on error, just log
    propertiesService.updateAll().catchAll(err => ApplicationLoggerPure.warn(err.fullMsg)).runNow

    // UpdateDynamicGroups is part of rci
    // reportingService part of rci
    // checkInventoryUpdate part of rci
    // purgeDeletedInventories part of rci
    // purgeUnreferencedSoftwares part of rci
    // AutomaticReportLogger part of rci
    // AutomaticReportsCleaning part of rci
    // CheckTechniqueLibrary part of rci
    // AutomaticReportsCleaning part of rci

    // return services part of the API
    rci
  }
}
