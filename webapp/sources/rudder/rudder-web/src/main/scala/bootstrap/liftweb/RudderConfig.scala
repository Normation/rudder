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
import bootstrap.liftweb.checks.action.CheckNcfTechniqueUpdate
import bootstrap.liftweb.checks.action.CheckTechniqueLibraryReload
import bootstrap.liftweb.checks.action.CreateSystemToken
import bootstrap.liftweb.checks.action.LoadNodeComplianceCache
import bootstrap.liftweb.checks.action.RemoveFaultyLdapEntries
import bootstrap.liftweb.checks.action.TriggerPolicyUpdate
import bootstrap.liftweb.checks.consistency.CheckConnections
import bootstrap.liftweb.checks.consistency.CheckDIT
import bootstrap.liftweb.checks.consistency.CheckRudderGlobalParameter
import bootstrap.liftweb.checks.consistency.CloseOpenUserSessions
import bootstrap.liftweb.checks.migration.CheckAddSpecialNodeGroupsDescription
import bootstrap.liftweb.checks.migration.CheckRemoveRuddercSetting
import bootstrap.liftweb.checks.migration.CheckTableScore
import bootstrap.liftweb.checks.migration.CheckTableUsers
import bootstrap.liftweb.checks.migration.MigrateChangeValidationEnforceSchema
import bootstrap.liftweb.checks.migration.MigrateEventLogEnforceSchema
import bootstrap.liftweb.checks.migration.MigrateJsonTechniquesToYaml
import bootstrap.liftweb.checks.migration.MigrateNodeAcceptationInventories
import bootstrap.liftweb.checks.onetimeinit.CheckInitUserTemplateLibrary
import bootstrap.liftweb.checks.onetimeinit.CheckInitXmlExport
import com.normation.appconfig._
import com.normation.box._
import com.normation.cfclerk.services._
import com.normation.cfclerk.services.impl._
import com.normation.cfclerk.xmlparsers._
import com.normation.cfclerk.xmlwriters.SectionSpecWriter
import com.normation.cfclerk.xmlwriters.SectionSpecWriterImpl
import com.normation.errors._
import com.normation.errors.IOResult
import com.normation.errors.SystemError
import com.normation.inventory.domain._
import com.normation.inventory.ldap.core._
import com.normation.inventory.ldap.provisioning.AddIpValues
import com.normation.inventory.ldap.provisioning.CheckOsType
import com.normation.inventory.ldap.provisioning.LastInventoryDate
import com.normation.inventory.ldap.provisioning.NameAndVersionIdFinder
import com.normation.inventory.provisioning.fusion.FusionInventoryParser
import com.normation.inventory.provisioning.fusion.PreInventoryParserCheckConsistency
import com.normation.inventory.services.core._
import com.normation.inventory.services.provisioning.DefaultInventoryParser
import com.normation.inventory.services.provisioning.InventoryDigestServiceV1
import com.normation.inventory.services.provisioning.InventoryParser
import com.normation.ldap.sdk._
import com.normation.plugins.FilePluginSettingsService
import com.normation.plugins.ReadPluginPackageInfo
import com.normation.plugins.SnippetExtensionRegister
import com.normation.plugins.SnippetExtensionRegisterImpl
import com.normation.rudder.api._
import com.normation.rudder.apidata.RestDataSerializer
import com.normation.rudder.apidata.RestDataSerializerImpl
import com.normation.rudder.apidata.ZioJsonExtractor
import com.normation.rudder.batch._
import com.normation.rudder.campaigns.CampaignEventRepositoryImpl
import com.normation.rudder.campaigns.CampaignRepositoryImpl
import com.normation.rudder.campaigns.CampaignSerializer
import com.normation.rudder.campaigns.JSONReportsAnalyser
import com.normation.rudder.campaigns.MainCampaignService
import com.normation.rudder.configuration.ConfigurationRepository
import com.normation.rudder.configuration.ConfigurationRepositoryImpl
import com.normation.rudder.configuration.GroupRevisionRepository
import com.normation.rudder.configuration.RuleRevisionRepository
import com.normation.rudder.db.Doobie
import com.normation.rudder.domain._
import com.normation.rudder.domain.logger.ApplicationLogger
import com.normation.rudder.domain.logger.NodeConfigurationLoggerImpl
import com.normation.rudder.domain.logger.ScheduledJobLoggerPure
import com.normation.rudder.domain.nodes.NodeGroupId
import com.normation.rudder.domain.queries._
import com.normation.rudder.facts.nodes.AppLogNodeFactChangeEventCallback
import com.normation.rudder.facts.nodes.CacheInvalidateNodeFactEventCallback
import com.normation.rudder.facts.nodes.CoreNodeFactRepository
import com.normation.rudder.facts.nodes.EventLogsNodeFactChangeEventCallback
import com.normation.rudder.facts.nodes.GenerationOnChange
import com.normation.rudder.facts.nodes.GitNodeFactStorageImpl
import com.normation.rudder.facts.nodes.HistorizeNodeState
import com.normation.rudder.facts.nodes.LdapNodeFactStorage
import com.normation.rudder.facts.nodes.NodeFactChangeEventCallback
import com.normation.rudder.facts.nodes.NodeFactInventorySaver
import com.normation.rudder.facts.nodes.NodeFactRepository
import com.normation.rudder.facts.nodes.NodeInfoServiceProxy
import com.normation.rudder.facts.nodes.NoopFactStorage
import com.normation.rudder.facts.nodes.QueryContext
import com.normation.rudder.facts.nodes.SoftDaoGetNodesbySofwareName
import com.normation.rudder.facts.nodes.WoFactNodeRepositoryProxy
import com.normation.rudder.git.GitRepositoryProvider
import com.normation.rudder.git.GitRepositoryProviderImpl
import com.normation.rudder.git.GitRevisionProvider
import com.normation.rudder.inventory.DefaultProcessInventoryService
import com.normation.rudder.inventory.InventoryFailedHook
import com.normation.rudder.inventory.InventoryFileWatcher
import com.normation.rudder.inventory.InventoryMover
import com.normation.rudder.inventory.InventoryProcessor
import com.normation.rudder.inventory.PostCommitInventoryHooks
import com.normation.rudder.inventory.ProcessFile
import com.normation.rudder.inventory.TriggerInventoryScorePostCommit
import com.normation.rudder.metrics._
import com.normation.rudder.migration.DefaultXmlEventLogMigration
import com.normation.rudder.ncf
import com.normation.rudder.ncf.DeleteEditorTechniqueImpl
import com.normation.rudder.ncf.EditorTechniqueReader
import com.normation.rudder.ncf.EditorTechniqueReaderImpl
import com.normation.rudder.ncf.GitResourceFileService
import com.normation.rudder.ncf.ParameterType.PlugableParameterTypeService
import com.normation.rudder.ncf.RuddercServiceImpl
import com.normation.rudder.ncf.TechniqueCompiler
import com.normation.rudder.ncf.TechniqueCompilerApp
import com.normation.rudder.ncf.TechniqueCompilerWithFallback
import com.normation.rudder.ncf.TechniqueSerializer
import com.normation.rudder.ncf.TechniqueWriter
import com.normation.rudder.ncf.TechniqueWriterImpl
import com.normation.rudder.ncf.WebappTechniqueCompiler
import com.normation.rudder.ncf.yaml.YamlTechniqueSerializer
import com.normation.rudder.reports.AgentRunIntervalService
import com.normation.rudder.reports.AgentRunIntervalServiceImpl
import com.normation.rudder.reports.ComplianceModeService
import com.normation.rudder.reports.ComplianceModeServiceImpl
import com.normation.rudder.reports.execution._
import com.normation.rudder.repository._
import com.normation.rudder.repository.jdbc._
import com.normation.rudder.repository.ldap._
import com.normation.rudder.repository.xml._
import com.normation.rudder.repository.xml.GitParseTechniqueLibrary
import com.normation.rudder.rest._
import com.normation.rudder.rest.internal._
import com.normation.rudder.rest.lift
import com.normation.rudder.rest.lift._
import com.normation.rudder.rule.category._
import com.normation.rudder.rule.category.GitRuleCategoryArchiverImpl
import com.normation.rudder.score.GlobalScoreRepositoryImpl
import com.normation.rudder.score.ScoreRepositoryImpl
import com.normation.rudder.score.ScoreService
import com.normation.rudder.score.ScoreServiceImpl
import com.normation.rudder.score.ScoreServiceManager
import com.normation.rudder.score.SystemUpdateScoreHandler
import com.normation.rudder.services._
import com.normation.rudder.services.eventlog._
import com.normation.rudder.services.eventlog.EventLogFactoryImpl
import com.normation.rudder.services.healthcheck._
import com.normation.rudder.services.marshalling._
import com.normation.rudder.services.modification.DiffService
import com.normation.rudder.services.modification.DiffServiceImpl
import com.normation.rudder.services.modification.ModificationService
import com.normation.rudder.services.nodes._
import com.normation.rudder.services.nodes.history.impl.FullInventoryFileParser
import com.normation.rudder.services.nodes.history.impl.InventoryHistoryJdbcRepository
import com.normation.rudder.services.nodes.history.impl.InventoryHistoryLogRepository
import com.normation.rudder.services.policies._
import com.normation.rudder.services.policies.nodeconfig._
import com.normation.rudder.services.policies.write._
import com.normation.rudder.services.queries._
import com.normation.rudder.services.quicksearch.FullQuickSearchService
import com.normation.rudder.services.reports._
import com.normation.rudder.services.servers._
import com.normation.rudder.services.system._
import com.normation.rudder.services.user._
import com.normation.rudder.services.workflows._
import com.normation.rudder.tenants.DefaultTenantService
import com.normation.rudder.tenants.TenantService
import com.normation.rudder.users._
import com.normation.rudder.web.model._
import com.normation.rudder.web.services._
import com.normation.templates.FillTemplatesService
import com.normation.utils.CronParser._
import com.normation.utils.StringUuidGenerator
import com.normation.utils.StringUuidGeneratorImpl
import com.normation.zio._
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
import net.liftweb.common._
import net.liftweb.common.Loggable
import org.apache.commons.io.FileUtils
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.joda.time.DateTimeZone
import scala.collection.mutable.Buffer
import scala.concurrent.duration
import scala.concurrent.duration.FiniteDuration
import scala.util.Try
import zio.{Scheduler => _, System => _, _}
import zio.syntax._

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
          case x         =>
            val d = better.files.File(x)
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
        throw new javax.servlet.UnavailableException(s"Configuration file not found: ${config.getPath}")
      }
  }

  // Sorting is done here for meaningful debug log, but we need to reverse it
  // because in typesafe Config, we have "withDefault" (ie the opposite of overrides)
  val overrideConfigs: List[FileSystemResource] = overrideDir match {
    case None    => // no additional config to add
      Nil
    case Some(x) =>
      val d = better.files.File(x)
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
        case f if (configFileExtensions.contains(f.extension(false, false).getOrElse(""))) => FileSystemResource(f.toJava)
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
      import java.nio.file.attribute.PosixFilePermission._
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
  import RudderConfigInit._
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
    import scala.jdk.CollectionConverters._
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
    import scala.jdk.CollectionConverters._
    config
      .entrySet()
      .asScala
      .map(_.getKey)
      .filter(s => s.startsWith("rudder.auth.oauth2.provider") && s.endsWith("client.secret"))
  }
  // other values

  val LDAP_HOST:                         String         = config.getString("ldap.host")
  val LDAP_PORT:                         Int            = config.getInt("ldap.port")
  val LDAP_AUTHDN:                       String         = config.getString("ldap.authdn")
  val LDAP_AUTHPW:                       String         = config.getString("ldap.authpw");
  filteredPasswords += "ldap.authpw"
  val LDAP_MAX_POOL_SIZE:                Int            = {
    try {
      config.getInt("ldap.maxPoolSize")
    } catch {
      case ex: ConfigException =>
        ApplicationLogger.info(
          "Property 'ldap.maxPoolSize' is missing or empty in rudder.configFile. Default to 2 connections."
        )
        2
    }
  }
  val LDAP_CACHE_NODE_INFO_MIN_INTERVAL: Duration       = {
    val x = {
      try {
        config.getInt("ldap.nodeinfo.cache.min.interval")
      } catch {
        case ex: ConfigException =>
          ApplicationLogger.debug(
            "Property 'ldap.nodeinfo.cache.min.interval' is missing or empty in rudder.configFile. Default to 100 ms."
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
        case "" => None
        case x  => Some(x)
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
              "Property 'rudder.policy.distribution.port.cfengine' is missing or empty in Rudder configuration file. Default to 5309"
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
          "Property 'rudder.policy.distribution.port.https' is missing or empty in Rudder configuration file. Default to 443"
        )
        443
    }
  }

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
            "Property 'rudder.jdbc.batch.max.size' is missing or empty in rudder.configFile. Default to 500."
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

  val RUDDER_GIT_GC: Option[CronExpr] = (
    try {
      config.getString("rudder.git.gc")
    } catch {
      // missing key, perhaps due to migration, use default
      case ex: Exception => {
        val default = "0 42 3 * * ?"
        logger.info(s"`rudder.git.gc` property is missing, using default schedule: ${default}")
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
        logger.info(s"`rudder.inventories.cleanup.old.files.cron` property is missing, using default schedule: ${default}")
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
  val RUDDER_BATCH_DYNGROUP_UPDATEINTERVAL:         Int    = config.getInt("rudder.batch.dyngroup.updateInterval") // 60 //one hour
  val RUDDER_BATCH_TECHNIQUELIBRARY_UPDATEINTERVAL: Int    =
    config.getInt("rudder.batch.techniqueLibrary.updateInterval") // 60 * 5 //five minutes
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
  val RUDDER_AUTOARCHIVEITEMS:              Boolean = config.getBoolean("rudder.autoArchiveItems")             // true
  val RUDDER_REPORTS_EXECUTION_MAX_DAYS:    Int     = config.getInt("rudder.batch.storeAgentRunTimes.maxDays") // In days : 0
  val RUDDER_REPORTS_EXECUTION_MAX_MINUTES: Int     = {                                                        // Tis is handled at the object creation, days and minutes = 0 => 30 minutes
    try {
      config.getInt("rudder.batch.storeAgentRunTimes.maxMinutes")
    } catch {
      case ex: ConfigException =>
        ApplicationLogger.info(
          "Property 'rudder.batch.storeAgentRunTimes.maxMinutes' is missing or empty in rudder.configFile. Default to 0 minutes."
        )
        0
    }
  }
  val RUDDER_REPORTS_EXECUTION_MAX_SIZE:    Int     = {                                                        // In minutes: 5
    try {
      config.getInt("rudder.batch.storeAgentRunTimes.maxBatchSize")
    } catch {
      case ex: ConfigException =>
        ApplicationLogger.info(
          "Property 'rudder.batch.storeAgentRunTimes.maxBatchSize' is missing or empty in rudder.configFile. Default to 5 minutes."
        )
        5
    }
  }

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
          "Property 'rudder.batch.purge.inventories.delete.TTL' is missing or empty in rudder.configFile. Default to 7 days."
        )
        7
    }
  }

  val RUDDER_BCRYPT_COST: Int = {
    try {
      config.getInt("rudder.bcrypt.cost")
    } catch {
      case ex: ConfigException =>
        ApplicationLogger.debug(
          "Property 'rudder.bcrypt.cost' is missing or empty in rudder.configFile. Default cost to 12."
        )
        12
    }
  }

  val RUDDER_BATCH_PURGE_DELETED_INVENTORIES_INTERVAL: Int = {
    try {
      config.getInt("rudder.batch.purge.inventories.delete.interval")
    } catch {
      case ex: ConfigException =>
        ApplicationLogger.info(
          "Property 'rudder.batch.purge.inventories.delete.interval' is missing or empty in rudder.configFile. Default to 24 hours."
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
          "Property 'rudder.batch.delete.software.interval' is missing or empty in rudder.configFile. Default to 24 hours."
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
          "Property 'rudder.batch.check.node.cache.interval' is missing or empty in rudder.configFile. Default to '15 s'."
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
          "Property 'rudder.config.repo.new.file.group.owner' is missing or empty in rudder.configFile. Default to 'rudder'."
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
          "Property 'rudder.generated.policies.group.owner' is missing or empty in rudder.configFile. Default to 'rudder-policy-reader'."
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
    rudderMajorVersion,
    rudderFullVersion,
    currentYear,
    builtTimestamp
  ) = {
    val p = new java.util.Properties
    p.load(this.getClass.getClassLoader.getResourceAsStream("version.properties"))
    (
      p.getProperty("rudder-major-version"),
      p.getProperty("rudder-full-version"),
      p.getProperty("current-year"),
      p.getProperty("build-timestamp")
    )
  }

  val LDIF_TRACELOG_ROOT_DIR: String = {
    try {
      config.getString("ldif.tracelog.rootdir")
    } catch {
      case ex: ConfigException => "/var/rudder/inventories/debug"
    }
  }

  // don't parse some elements in inventories: processes
  val INVENTORIES_IGNORE_PROCESSES: Boolean = {
    try {
      config.getBoolean("inventory.parse.ignore.processes")
    } catch {
      case ex: ConfigException => false
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
          "Property 'metrics.healthcheck.scheduler.period' is missing or empty in rudder.configFile. Default to 6 hours."
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

  val TECHNIQUE_COMPILER_APP: TechniqueCompilerApp = {
    val default = TechniqueCompilerApp.Rudderc
    (try {
      TechniqueCompilerApp.parse(config.getString("rudder.technique.compiler.app"))
    } catch {
      case ex: ConfigException => Some(default)
    }).getOrElse(default)
  }

  val RUDDERC_CMD: String = {
    try {
      config.getString("rudder.technique.compiler.rudderc.cmd")
    } catch {
      case ex: ConfigException => "/opt/rudder/bin/rudderc"
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
        logger.info(s"`rudder.users.cleanup.cron` property is missing, using default schedule: ${default}")
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
    parseDuration("rudder.users.cleanup.account.disableAfterLastLogin", 60.days)
  val RUDDER_USERS_CLEAN_LAST_LOGIN_DELETE:  Duration         =
    parseDuration("rudder.users.cleanup.account.deleteAfterLastLogin", 120.days)
  val RUDDER_USERS_CLEAN_DELETED_PURGE:      Duration         = parseDuration("rudder.users.cleanup.purgeDeletedAfter", 30.days)
  val RUDDER_USERS_CLEAN_SESSIONS_PURGE:     Duration         = parseDuration("rudder.users.cleanup.sessions.purgeAfter", 30.days)

  def parseDuration(propName: String, default: Duration): Duration = {
    try {
      Duration.fromScala(scala.concurrent.duration.Duration(config.getString(propName)))
    } catch {
      case ex: ConfigException       => // default
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
  def RUDDER_BATCH_TECHNIQUELIBRARY_UPDATEINTERVAL = RudderParsedProperties.RUDDER_BATCH_TECHNIQUELIBRARY_UPDATEINTERVAL

  //
  // Theses services can be called from the outer world
  // They must be typed with there abstract interface, as
  // such service must not expose implementation details
  //

  // we need that to be the first thing, and take care of Exception so that the error is
  // human understandable when the directory is not up
  val rci: RudderServiceApi = RudderConfigInit.init()

  val ApiVersions:                         List[ApiVersion]                           = rci.apiVersions
  val acceptedNodeQueryProcessor:          QueryProcessor                             = rci.acceptedNodeQueryProcessor
  val acceptedNodesDit:                    InventoryDit                               = rci.acceptedNodesDit
  val agentRegister:                       AgentRegister                              = rci.agentRegister
  val aggregateReportScheduler:            FindNewReportsExecution                    = rci.aggregateReportScheduler
  val apiAuthorizationLevelService:        DefaultApiAuthorizationLevel               = rci.apiAuthorizationLevelService
  val apiDispatcher:                       RudderEndpointDispatcher                   = rci.apiDispatcher
  val asyncComplianceService:              AsyncComplianceService                     = rci.asyncComplianceService
  val asyncDeploymentAgent:                AsyncDeploymentActor                       = rci.asyncDeploymentAgent
  val asyncWorkflowInfo:                   AsyncWorkflowInfo                          = rci.asyncWorkflowInfo
  val authenticationProviders:             AuthBackendProvidersManager                = rci.authenticationProviders
  val authorizationApiMapping:             ExtensibleAuthorizationApiMapping          = rci.authorizationApiMapping
  val automaticReportLogger:               AutomaticReportLogger                      = rci.automaticReportLogger
  val automaticReportsCleaning:            AutomaticReportsCleaning                   = rci.automaticReportsCleaning
  val campaignEventRepo:                   CampaignEventRepositoryImpl                = rci.campaignEventRepo
  val campaignSerializer:                  CampaignSerializer                         = rci.campaignSerializer
  val categoryHierarchyDisplayer:          CategoryHierarchyDisplayer                 = rci.categoryHierarchyDisplayer
  val changeRequestChangesSerialisation:   ChangeRequestChangesSerialisation          = rci.changeRequestChangesSerialisation
  val changeRequestChangesUnserialisation: ChangeRequestChangesUnserialisation        = rci.changeRequestChangesUnserialisation
  val changeRequestEventLogService:        ChangeRequestEventLogService               = rci.changeRequestEventLogService
  val checkTechniqueLibrary:               CheckTechniqueLibrary                      = rci.checkTechniqueLibrary
  val clearCacheService:                   ClearCacheService                          = rci.clearCacheService
  val cmdbQueryParser:                     CmdbQueryParser                            = rci.cmdbQueryParser
  val commitAndDeployChangeRequest:        CommitAndDeployChangeRequestService        = rci.commitAndDeployChangeRequest
  val complianceService:                   ComplianceAPIService                       = rci.complianceService
  val configService:                       ReadConfigService with UpdateConfigService = rci.configService
  val configurationRepository:             ConfigurationRepository                    = rci.configurationRepository
  val databaseManager:                     DatabaseManager                            = rci.databaseManager
  val debugScript:                         DebugInfoService                           = rci.debugScript
  val dependencyAndDeletionService:        DependencyAndDeletionService               = rci.dependencyAndDeletionService
  val deploymentService:                   PromiseGeneration_Hooks                    = rci.deploymentService
  val diffDisplayer:                       DiffDisplayer                              = rci.diffDisplayer
  val diffService:                         DiffService                                = rci.diffService
  val directiveEditorService:              DirectiveEditorService                     = rci.directiveEditorService
  val ditQueryData:                        DitQueryData                               = rci.ditQueryData
  val doobie:                              Doobie                                     = rci.doobie
  val dynGroupService:                     DynGroupService                            = rci.dynGroupService
  val eventListDisplayer:                  EventListDisplayer                         = rci.eventListDisplayer
  val eventLogApi:                         EventLogAPI                                = rci.eventLogApi
  val eventLogDeploymentService:           EventLogDeploymentService                  = rci.eventLogDeploymentService
  val eventLogDetailsService:              EventLogDetailsService                     = rci.eventLogDetailsService
  val eventLogRepository:                  EventLogRepository                         = rci.eventLogRepository
  val findExpectedReportRepository:        FindExpectedReportRepository               = rci.findExpectedReportRepository
  val gitModificationRepository:           GitModificationRepository                  = rci.gitModificationRepository
  val gitRepo:                             GitRepositoryProvider                      = rci.gitRepo
  val gitRevisionProvider:                 GitRevisionProvider                        = rci.gitRevisionProvider
  val healthcheckNotificationService:      HealthcheckNotificationService             = rci.healthcheckNotificationService
  val historizeNodeCountBatch:             IOResult[Unit]                             = rci.historizeNodeCountBatch
  val interpolationCompiler:               InterpolatedValueCompilerImpl              = rci.interpolationCompiler
  val inventoryEventLogService:            InventoryEventLogService                   = rci.inventoryEventLogService
  val inventoryHistoryJdbcRepository:      InventoryHistoryJdbcRepository             = rci.inventoryHistoryJdbcRepository
  val inventoryWatcher:                    InventoryFileWatcher                       = rci.inventoryWatcher
  val itemArchiveManager:                  ItemArchiveManager                         = rci.itemArchiveManager
  val jsTreeUtilService:                   JsTreeUtilService                          = rci.jsTreeUtilService
  val jsonPluginDefinition:                ReadPluginPackageInfo                      = rci.jsonPluginDefinition
  val jsonReportsAnalyzer:                 JSONReportsAnalyser                        = rci.jsonReportsAnalyzer
  val linkUtil:                            LinkUtil                                   = rci.linkUtil
  val logDisplayer:                        LogDisplayer                               = rci.logDisplayer
  val mainCampaignService:                 MainCampaignService                        = rci.mainCampaignService
  val ncfTechniqueReader:                  ncf.EditorTechniqueReader                  = rci.ncfTechniqueReader
  val newNodeManager:                      NewNodeManager                             = rci.newNodeManager
  val nodeDit:                             NodeDit                                    = rci.nodeDit
  val nodeFactRepository:                  NodeFactRepository                         = rci.nodeFactRepository
  val nodeGrid:                            NodeGrid                                   = rci.nodeGrid
  val nodeInfoService:                     NodeInfoService                            = rci.nodeInfoService
  val pendingNodeCheckGroup:               CheckPendingNodeInDynGroups                = rci.pendingNodeCheckGroup
  val pendingNodesDit:                     InventoryDit                               = rci.pendingNodesDit
  val personIdentService:                  PersonIdentService                         = rci.personIdentService
  val policyGenerationBootGuard:           zio.Promise[Nothing, Unit]                 = rci.policyGenerationBootGuard
  val policyServerManagementService:       PolicyServerManagementService              = rci.policyServerManagementService
  val propertyEngineService:               PropertyEngineService                      = rci.propertyEngineService
  val purgeDeletedInventories:             PurgeDeletedInventories                    = rci.purgeDeletedInventories
  val purgeUnreferencedSoftwares:          PurgeUnreferencedSoftwares                 = rci.purgeUnreferencedSoftwares
  val readOnlySoftwareDAO:                 ReadOnlySoftwareDAO                        = rci.readOnlySoftwareDAO
  val recentChangesService:                NodeChangesService                         = rci.recentChangesService
  val removeNodeService:                   RemoveNodeService                          = rci.removeNodeService
  val reportDisplayer:                     ReportDisplayer                            = rci.reportDisplayer
  val reportingService:                    ReportingService                           = rci.reportingService
  val reportsRepository:                   ReportsRepository                          = rci.reportsRepository
  val restApiAccounts:                     RestApiAccounts                            = rci.restApiAccounts
  val restCompletion:                      RestCompletion                             = rci.restCompletion
  val restDataSerializer:                  RestDataSerializer                         = rci.restDataSerializer
  val restExtractorService:                RestExtractorService                       = rci.restExtractorService
  val restQuicksearch:                     RestQuicksearch                            = rci.restQuicksearch
  val roAgentRunsRepository:               RoReportsExecutionRepository               = rci.roAgentRunsRepository
  val roApiAccountRepository:              RoApiAccountRepository                     = rci.roApiAccountRepository
  val roDirectiveRepository:               RoDirectiveRepository                      = rci.roDirectiveRepository
  val roLDAPConnectionProvider:            LDAPConnectionProvider[RoLDAPConnection]   = rci.roLDAPConnectionProvider
  val roLDAPParameterRepository:           RoLDAPParameterRepository                  = rci.roLDAPParameterRepository
  val roNodeGroupRepository:               RoNodeGroupRepository                      = rci.roNodeGroupRepository
  val roParameterService:                  RoParameterService                         = rci.roParameterService
  val roRuleCategoryRepository:            RoRuleCategoryRepository                   = rci.roRuleCategoryRepository
  val roRuleRepository:                    RoRuleRepository                           = rci.roRuleRepository
  val rudderApi:                           LiftHandler                                = rci.rudderApi
  val rudderDit:                           RudderDit                                  = rci.rudderDit
  val rudderUserListProvider:              FileUserDetailListProvider                 = rci.rudderUserListProvider
  val ruleApplicationStatus:               RuleApplicationStatusService               = rci.ruleApplicationStatus
  val ruleCategoryService:                 RuleCategoryService                        = rci.ruleCategoryService
  val rwLdap:                              LDAPConnectionProvider[RwLDAPConnection]   = rci.rwLdap
  val secretEventLogService:               SecretEventLogService                      = rci.secretEventLogService
  val sharedFileApi:                       SharedFilesAPI                             = rci.sharedFileApi
  val snippetExtensionRegister:            SnippetExtensionRegister                   = rci.snippetExtensionRegister
  val srvGrid:                             SrvGrid                                    = rci.srvGrid
  val stringUuidGenerator:                 StringUuidGenerator                        = rci.stringUuidGenerator
  val techniqueRepository:                 TechniqueRepository                        = rci.techniqueRepository
  val tenantService:                       TenantService                              = rci.tenantService
  val tokenGenerator:                      TokenGeneratorImpl                         = rci.tokenGenerator
  val updateDynamicGroups:                 UpdateDynamicGroups                        = rci.updateDynamicGroups
  val updateDynamicGroupsService:          DynGroupUpdaterService                     = rci.updateDynamicGroupsService
  val updateTechniqueLibrary:              UpdateTechniqueLibrary                     = rci.updateTechniqueLibrary
  val userAuthorisationLevel:              DefaultUserAuthorisationLevel              = rci.userAuthorisationLevel
  val userPropertyService:                 UserPropertyService                        = rci.userPropertyService
  val userRepository:                      UserRepository                             = rci.userRepository
  val userService:                         UserService                                = rci.userService
  val woApiAccountRepository:              WoApiAccountRepository                     = rci.woApiAccountRepository
  val woDirectiveRepository:               WoDirectiveRepository                      = rci.woDirectiveRepository
  val woNodeGroupRepository:               WoNodeGroupRepository                      = rci.woNodeGroupRepository
  val woNodeRepository:                    WoNodeRepository                           = rci.woNodeRepository
  val woRuleCategoryRepository:            WoRuleCategoryRepository                   = rci.woRuleCategoryRepository
  val woRuleRepository:                    WoRuleRepository                           = rci.woRuleRepository
  val workflowEventLogService:             WorkflowEventLogService                    = rci.workflowEventLogService
  val workflowLevelService:                DefaultWorkflowLevel                       = rci.workflowLevelService

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

      rci.scoreService.init().runNow

      rci.scoreServiceManager.registerHandler(new SystemUpdateScoreHandler(rci.nodeFactRepository)).runNow

    }
  }

  def postPluginInitActions: Unit = {
    // todo: scheduler interval should be a property
    ZioRuntime.unsafeRun(jsonReportsAnalyzer.start(5.seconds).forkDaemon.provideLayer(ZioRuntime.layers))
    ZioRuntime.unsafeRun(MainCampaignService.start(mainCampaignService))
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
    woNodeRepository:                    WoNodeRepository,
    roNodeGroupRepository:               RoNodeGroupRepository,
    woNodeGroupRepository:               WoNodeGroupRepository,
    techniqueRepository:                 TechniqueRepository,
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
    nodeInfoService:                     NodeInfoService,
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
    restApiAccounts:                     RestApiAccounts,
    restQuicksearch:                     RestQuicksearch,
    restCompletion:                      RestCompletion,
    sharedFileApi:                       SharedFilesAPI,
    eventLogApi:                         EventLogAPI,
    stringUuidGenerator:                 StringUuidGenerator,
    inventoryWatcher:                    InventoryFileWatcher,
    configService:                       ReadConfigService with UpdateConfigService,
    historizeNodeCountBatch:             IOResult[Unit],
    policyGenerationBootGuard:           zio.Promise[Nothing, Unit],
    healthcheckNotificationService:      HealthcheckNotificationService,
    jsonPluginDefinition:                ReadPluginPackageInfo,
    rudderApi:                           LiftHandler,
    authorizationApiMapping:             ExtensibleAuthorizationApiMapping,
    roRuleCategoryRepository:            RoRuleCategoryRepository,
    woRuleCategoryRepository:            WoRuleCategoryRepository,
    workflowLevelService:                DefaultWorkflowLevel,
    ncfTechniqueReader:                  EditorTechniqueReader,
    recentChangesService:                NodeChangesService,
    ruleCategoryService:                 RuleCategoryService,
    restExtractorService:                RestExtractorService,
    snippetExtensionRegister:            SnippetExtensionRegister,
    clearCacheService:                   ClearCacheService,
    linkUtil:                            LinkUtil,
    userRepository:                      UserRepository,
    userService:                         UserService,
    apiVersions:                         List[ApiVersion],
    apiDispatcher:                       RudderEndpointDispatcher,
    configurationRepository:             ConfigurationRepository,
    roParameterService:                  RoParameterService,
    userAuthorisationLevel:              DefaultUserAuthorisationLevel,
    agentRegister:                       AgentRegister,
    asyncWorkflowInfo:                   AsyncWorkflowInfo,
    commitAndDeployChangeRequest:        CommitAndDeployChangeRequestService,
    doobie:                              Doobie,
    restDataSerializer:                  RestDataSerializer,
    workflowEventLogService:             WorkflowEventLogService,
    changeRequestEventLogService:        ChangeRequestEventLogService,
    changeRequestChangesUnserialisation: ChangeRequestChangesUnserialisation,
    diffService:                         DiffService,
    diffDisplayer:                       DiffDisplayer,
    rwLdap:                              LDAPConnectionProvider[RwLDAPConnection],
    apiAuthorizationLevelService:        DefaultApiAuthorizationLevel,
    tokenGenerator:                      TokenGeneratorImpl,
    roLDAPParameterRepository:           RoLDAPParameterRepository,
    interpolationCompiler:               InterpolatedValueCompilerImpl,
    deploymentService:                   PromiseGeneration_Hooks,
    campaignEventRepo:                   CampaignEventRepositoryImpl,
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
    tenantService:                       TenantService
)

/*
 * This object is in charge of class instantiation in a method to avoid dead lock.
 * See: https://issues.rudder.io/issues/22645
 */
object RudderConfigInit {
  import RudderParsedProperties._

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

    // test connection is up and try to make an human understandable error message.
    ApplicationLogger.debug(s"Test if LDAP connection is active")

    lazy val writeAllAgentSpecificFiles = new WriteAllAgentSpecificFiles(agentRegister)

    // all cache that need to be cleared are stored here
    lazy val clearableCache: Seq[CachedRepository] = Seq(
      cachedAgentRunRepository,
      recentChangesService,
      reportingServiceImpl
    )

    lazy val pluginSettingsService = new FilePluginSettingsService(
      root / "opt" / "rudder" / "etc" / "rudder-pkg" / "rudder-pkg.conf"
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

    // Plugin input interface for user management plugin
    lazy val userAuthorisationLevel = new DefaultUserAuthorisationLevel()

    // Plugin input interface for Authorization for API
    lazy val authorizationApiMapping = new ExtensibleAuthorizationApiMapping(AuthorizationApiMapping.Core :: Nil)

    ////////// end pluggable service providers //////////

    lazy val roleApiMapping = new RoleApiMapping(authorizationApiMapping)

    // rudder user list
    lazy val rudderUserListProvider: FileUserDetailListProvider = {
      UserFileProcessing.getUserResourceFile().either.runNow match {
        case Right(resource) =>
          new FileUserDetailListProvider(roleApiMapping, userAuthorisationLevel, resource)
        case Left(err)       =>
          ApplicationLogger.error(err.fullMsg)
          // make the application not available
          throw new javax.servlet.UnavailableException(s"Error when trying to parse Rudder users file, aborting.")
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
    lazy val workflowEventLogService = new WorkflowEventLogServiceImpl(eventLogRepository, uuidGen)
    lazy val diffService: DiffService = new DiffServiceImpl()
    lazy val diffDisplayer = new DiffDisplayer(linkUtil)
    lazy val commitAndDeployChangeRequest: CommitAndDeployChangeRequestService = {
      new CommitAndDeployChangeRequestServiceImpl(
        uuidGen,
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
        configService.rudder_workflow_enabled _,
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

    lazy val restExtractorService = {
      RestExtractorService(
        roRuleRepository,
        roDirectiveRepository,
        roNodeGroupRepository,
        techniqueRepository,
        queryParser,
        userPropertyService,
        workflowLevelService,
        stringUuidGenerator,
        typeParameterService
      )
    }

    lazy val zioJsonExtractor = new ZioJsonExtractor(queryParser)

    lazy val tokenGenerator = new TokenGeneratorImpl(32)

    implicit lazy val userService = new UserService {
      def getCurrentUser = CurrentUser
    }

    lazy val userRepository:   UserRepository = new JdbcUserRepository(doobie)
    // batch for cleaning users
    lazy val userCleanupBatch: CleanupUsers   = new CleanupUsers(
      userRepository,
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

    lazy val linkUtil           = new LinkUtil(roRuleRepository, roNodeGroupRepository, roDirectiveRepository, nodeFactInfoService)
    // REST API
    lazy val restApiAccounts    = new RestApiAccounts(
      roApiAccountRepository,
      woApiAccountRepository,
      restExtractorService,
      tokenGenerator,
      uuidGen,
      userService,
      apiAuthorizationLevelService,
      tenantService
    )
    lazy val restDataSerializer = RestDataSerializerImpl(techniqueRepository, diffService)

    lazy val restQuicksearch = new RestQuicksearch(
      new FullQuickSearchService()(
        roLDAPConnectionProvider,
        nodeDit,
        acceptedNodesDit,
        rudderDit,
        roDirectiveRepository,
        nodeFactRepository
      ),
      userService,
      linkUtil
    )
    lazy val restCompletion  = new RestCompletion(new RestCompletionService(roDirectiveRepository, roRuleRepository))

    lazy val ruleApiService2 = {
      new RuleApiService2(
        roRuleRepository,
        woRuleRepository,
        uuidGen,
        asyncDeploymentAgent,
        workflowLevelService,
        restExtractorService,
        restDataSerializer
      )
    }

    lazy val ruleApiService6  = {
      new RuleApiService6(
        roRuleCategoryRepository,
        roRuleRepository,
        woRuleCategoryRepository,
        restDataSerializer
      )
    }
    lazy val ruleApiService13 = {
      new RuleApiService14(
        roRuleRepository,
        woRuleRepository,
        configurationRepository,
        uuidGen,
        asyncDeploymentAgent,
        workflowLevelService,
        roRuleCategoryRepository,
        woRuleCategoryRepository,
        roDirectiveRepository,
        roNodeGroupRepository,
        nodeFactRepository,
        configService.rudder_global_policy_mode _,
        ruleApplicationStatus
      )
    }

    lazy val directiveApiService2 = {
      new DirectiveApiService2(
        roDirectiveRepository,
        woDirectiveRepository,
        uuidGen,
        asyncDeploymentAgent,
        workflowLevelService,
        restExtractorService,
        directiveEditorService,
        restDataSerializer,
        techniqueRepositoryImpl
      )
    }

    lazy val directiveApiService14 = {
      new DirectiveApiService14(
        roDirectiveRepository,
        configurationRepository,
        woDirectiveRepository,
        uuidGen,
        asyncDeploymentAgent,
        workflowLevelService,
        directiveEditorService,
        restDataSerializer,
        techniqueRepositoryImpl
      )
    }

    lazy val techniqueApiService6 = {
      new TechniqueAPIService6(
        roDirectiveRepository,
        restDataSerializer
      )
    }

    lazy val techniqueApiService14 = {
      new TechniqueAPIService14(
        roDirectiveRepository,
        gitParseTechniqueLibrary,
        ncfTechniqueReader,
        techniqueSerializer,
        restDataSerializer,
        techniqueCompiler
      )
    }

    lazy val groupApiService2 = {
      new GroupApiService2(
        roNodeGroupRepository,
        woNodeGroupRepository,
        uuidGen,
        asyncDeploymentAgent,
        workflowLevelService,
        restExtractorService,
        queryProcessor,
        restDataSerializer
      )
    }

    lazy val groupApiService6 = {
      new GroupApiService6(
        roNodeGroupRepository,
        woNodeGroupRepository,
        restDataSerializer
      )
    }

    lazy val groupApiService14 = {
      new GroupApiService14(
        roNodeGroupRepository,
        woNodeGroupRepository,
        roLDAPParameterRepository,
        uuidGen,
        asyncDeploymentAgent,
        workflowLevelService,
        restExtractorService,
        queryParser,
        queryProcessor,
        restDataSerializer
      )
    }

    lazy val nodeApiService = new NodeApiService(
      rwLdap,
      nodeFactRepository,
      roNodeGroupRepository,
      roLDAPParameterRepository,
      roAgentRunsRepository,
      ldapEntityMapper,
      stringUuidGenerator,
      nodeDit,
      pendingNodesDit,
      acceptedNodesDit,
      newNodeManagerImpl,
      removeNodeServiceImpl,
      restExtractorService,
      restDataSerializer,
      reportingServiceImpl,
      queryProcessor,
      inventoryQueryChecker,
      () => configService.rudder_global_policy_mode().toBox,
      RUDDER_RELAY_API,
      scoreService
    )

    lazy val parameterApiService2  = {
      new ParameterApiService2(
        roLDAPParameterRepository,
        woLDAPParameterRepository,
        uuidGen,
        workflowLevelService,
        restExtractorService,
        restDataSerializer
      )
    }
    lazy val parameterApiService14 = {
      new ParameterApiService14(
        roLDAPParameterRepository,
        woLDAPParameterRepository,
        uuidGen,
        workflowLevelService
      )
    }

    // System API

    lazy val clearCacheService = new ClearCacheServiceImpl(
      nodeConfigurationHashRepo,
      asyncDeploymentAgent,
      eventLogRepository,
      uuidGen,
      clearableCache
    )

    lazy val hookApiService = new HookApiService(HOOKS_D, HOOKS_IGNORE_SUFFIXES)

    lazy val systemApiService11 = new SystemApiService11(
      updateTechniqueLibrary,
      debugScript,
      clearCacheService,
      asyncDeploymentAgent,
      uuidGen,
      updateDynamicGroups,
      itemArchiveManager,
      personIdentService,
      gitConfigRepo
    )

    lazy val systemApiService13 = new SystemApiService13(
      healthcheckService,
      healthcheckNotificationService,
      restDataSerializer,
      deprecated.softwareService
    )

    lazy val ruleInternalApiService =
      new RuleInternalApiService(roRuleRepository, roNodeGroupRepository, roRuleCategoryRepository, nodeFactRepository)

    lazy val complianceAPIService = new ComplianceAPIService(
      roRuleRepository,
      nodeFactRepository,
      roNodeGroupRepository,
      reportingService,
      roDirectiveRepository,
      globalComplianceModeService.getGlobalComplianceMode.toIO,
      configService.rudder_global_policy_mode()
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
    lazy val techniqueCompiler:  TechniqueCompiler = new TechniqueCompilerWithFallback(
      new WebappTechniqueCompiler(
        interpolationCompiler,
        prettyPrinter,
        typeParameterService,
        ncfTechniqueReader,
        _.path,
        RUDDER_GIT_ROOT_CONFIG_REPO
      ),
      new RuddercServiceImpl(RUDDERC_CMD, 5.seconds),
      TECHNIQUE_COMPILER_APP,
      _.path,
      RUDDER_GIT_ROOT_CONFIG_REPO
    )
    lazy val ncfTechniqueWriter: TechniqueWriter   = new TechniqueWriterImpl(
      techniqueArchiver,
      updateTechniqueLibrary,
      new DeleteEditorTechniqueImpl(
        techniqueArchiver,
        updateTechniqueLibrary,
        roDirectiveRepository,
        woDirectiveRepository,
        techniqueRepository,
        workflowLevelService,
        RUDDER_GIT_ROOT_CONFIG_REPO
      ),
      techniqueCompiler,
      RUDDER_GIT_ROOT_CONFIG_REPO
    )

    lazy val pipelinedInventoryParser: InventoryParser = {
      val fusionReportParser = {
        new FusionInventoryParser(
          uuidGen,
          rootParsingExtensions = Nil,
          contentParsingExtensions = Nil,
          ignoreProcesses = INVENTORIES_IGNORE_PROCESSES
        )
      }

      new DefaultInventoryParser(
        fusionReportParser,
        Seq(
          new PreInventoryParserCheckConsistency
        )
      )
    }

    lazy val gitFactRepoProvider = GitRepositoryProviderImpl
      .make(RUDDER_GIT_ROOT_FACT_REPO)
      .runOrDie(err => new RuntimeException(s"Error when initializing git configuration repository: " + err.fullMsg))
    lazy val gitFactRepoGC       = new GitGC(gitFactRepoProvider, RUDDER_GIT_GC)
    gitFactRepoGC.start()
    lazy val gitFactStorage      = if (RUDDER_GIT_FACT_WRITE_NODES) {
      val r = new GitNodeFactStorageImpl(gitFactRepoProvider, Some(RUDDER_GROUP_OWNER_CONFIG_REPO), RUDDER_GIT_FACT_COMMIT_NODES)
      r.checkInit().runOrDie(err => new RuntimeException(s"Error when checking fact repository init: " + err.fullMsg))
      r
    } else NoopFactStorage

    // TODO WARNING POC: this can't work on a machine with lots of node
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
      uuidGen
    )

    lazy val getNodeBySoftwareName = new SoftDaoGetNodesbySofwareName(deprecated.softwareInventoryDAO)

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
        new TriggerInventoryScorePostCommit[Unit](scoreServiceManager) ::
        new PostCommitInventoryHooks[Unit](HOOKS_D, HOOKS_IGNORE_SUFFIXES)
        // removed: this is done as a callback of CoreNodeFactRepos
        // :: new TriggerPolicyGenerationPostCommit[Unit](asyncDeploymentAgent, uuidGen)
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
        new InventoryDigestServiceV1((id: NodeId) => nodeFactRepository.get(id)(QueryContext.systemQC)),
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
      val fileProcessor = new ProcessFile(inventoryProcessor, INVENTORY_DIR_INCOMING)

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

    lazy val archiveApi = {
      val archiveBuilderService =
        new ZipArchiveBuilderService(new FileArchiveNameService(), configurationRepository, gitParseTechniqueLibrary)
      // fixe archive name to make it simple to test
      val rootDirName           = "archive".succeed
      new com.normation.rudder.rest.lift.ArchiveApi(
        archiveBuilderService,
        rootDirName,
        new ZipArchiveReaderImpl(queryParser, techniqueParser),
        new SaveArchiveServicebyRepo(
          techniqueArchiver,
          techniqueReader,
          techniqueRepository,
          roDirectiveRepository,
          woDirectiveRepository,
          roNodeGroupRepository,
          woNodeGroupRepository,
          roRuleRepository,
          woRuleRepository,
          updateTechniqueLibrary,
          asyncDeploymentAgent
        ),
        new CheckArchiveServiceImpl(techniqueRepository)
      )
    }

    /*
     * API versions are incremented each time incompatible changes are made (like adding or deleting endpoints - modification
     * of an existing endpoint, if done in a purely compatible way, don't change api version).
     * It may happen that some rudder branches don't have a version bump, and other have several (in case of
     * horrible breaking bugs). We avoid the case where a previous release need a version bump.
     * For ex:
     * - 5.0: 14
     * - 5.1: 14 (no change)
     * - 5.2[.0~.4]: 15
     * - 5.2.5: 16
     */
    lazy val ApiVersions: List[ApiVersion] = {
      ApiVersion(14, true) ::  // rudder 7.0
      ApiVersion(15, true) ::  // rudder 7.1 - system update on node details
      ApiVersion(16, true) ::  // rudder 7.2 - create node api, import/export archive, hooks & campaigns internal API
      ApiVersion(17, true) ::  // rudder 7.3 - directive compliance, campaign API is public
      ApiVersion(18, false) :: // rudder 8.0 - allowed network
      ApiVersion(19, false) :: // rudder 8.1 - (score), tenants
      Nil
    }

    lazy val jsonPluginDefinition = new ReadPluginPackageInfo("/var/rudder/packages/index.json")

    lazy val resourceFileService = new GitResourceFileService(gitConfigRepo)
    lazy val apiDispatcher       = new RudderEndpointDispatcher(LiftApiProcessingLogger)
    lazy val rudderApi           = {
      import com.normation.rudder.rest.lift._

      val nodeInheritedProperties  =
        new NodeApiInheritedProperties(nodeFactRepository, roNodeGroupRepository, roLDAPParameterRepository)
      val groupInheritedProperties = new GroupApiInheritedProperties(roNodeGroupRepository, roLDAPParameterRepository)

      val campaignApi = new lift.CampaignApi(
        campaignRepo,
        campaignSerializer,
        campaignEventRepo,
        mainCampaignService,
        restExtractorService,
        stringUuidGenerator
      )
      val modules     = List(
        new ComplianceApi(restExtractorService, complianceAPIService, roDirectiveRepository),
        new GroupsApi(
          roLdapNodeGroupRepository,
          restExtractorService,
          zioJsonExtractor,
          stringUuidGenerator,
          groupApiService2,
          groupApiService6,
          groupApiService14,
          groupInheritedProperties
        ),
        new DirectiveApi(
          roDirectiveRepository,
          restExtractorService,
          zioJsonExtractor,
          stringUuidGenerator,
          directiveApiService2,
          directiveApiService14
        ),
        new NodeApi(
          restExtractorService,
          restDataSerializer,
          nodeApiService,
          nodeInheritedProperties,
          uuidGen,
          DeleteMode.Erase // only supported mode for Rudder 8.0
        ),
        new ParameterApi(restExtractorService, zioJsonExtractor, parameterApiService2, parameterApiService14),
        new SettingsApi(
          restExtractorService,
          configService,
          asyncDeploymentAgent,
          stringUuidGenerator,
          policyServerManagementService,
          nodeFactInfoService
        ),
        new TechniqueApi(
          restExtractorService,
          techniqueApiService6,
          techniqueApiService14,
          ncfTechniqueWriter,
          ncfTechniqueReader,
          techniqueRepository,
          techniqueSerializer,
          stringUuidGenerator,
          resourceFileService,
          RUDDER_GIT_ROOT_CONFIG_REPO
        ),
        new RuleApi(
          restExtractorService,
          zioJsonExtractor,
          ruleApiService2,
          ruleApiService6,
          ruleApiService13,
          stringUuidGenerator
        ),
        new SystemApi(
          restExtractorService,
          systemApiService11,
          systemApiService13,
          rudderMajorVersion,
          rudderFullVersion,
          builtTimestamp
        ),
        new InventoryApi(restExtractorService, inventoryWatcher, better.files.File(INVENTORY_DIR_INCOMING)),
        new PluginApi(restExtractorService, pluginSettingsService),
        new RecentChangesAPI(recentChangesService, restExtractorService),
        new RulesInternalApi(ruleInternalApiService, ruleApiService13),
        campaignApi,
        new HookApi(hookApiService),
        archiveApi
        // info api must be resolved latter, because else it misses plugin apis !
      )

      val api = new LiftHandler(
        apiDispatcher,
        ApiVersions,
        new AclApiAuthorization(LiftApiProcessingLogger, userService, () => apiAuthorizationLevelService.aclEnabled),
        None
      )
      modules.foreach(module => api.addModules(module.getLiftEndpoints()))
      api
    }

    // Internal APIs
    lazy val sharedFileApi     = new SharedFilesAPI(restExtractorService, RUDDER_DIR_SHARED_FILES_FOLDER, RUDDER_GIT_ROOT_CONFIG_REPO)
    lazy val eventLogApi       = new EventLogAPI(eventLogRepository, restExtractorService, eventLogDetailsGenerator, personIdentService)
    lazy val asyncWorkflowInfo = new AsyncWorkflowInfo
    lazy val configService: ReadConfigService with UpdateConfigService = {
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
    //// everything was private[this]
    ////////////////////////////////////////////////////////////////////

    lazy val roLDAPApiAccountRepository = new RoLDAPApiAccountRepository(
      rudderDitImpl,
      roLdap,
      ldapEntityMapper,
      tokenGenerator,
      ApiAuthorization.allAuthz.acl // for system token
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

    def DN(rdn: String, parent: DN)           = new DN(new RDN(rdn), parent)
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
    lazy val uuidGen                   = stringUuidGenerator
    lazy val systemVariableSpecService = new SystemVariableSpecServiceImpl()
    lazy val ldapEntityMapper: LDAPEntityMapper =
      new LDAPEntityMapper(rudderDitImpl, nodeDitImpl, acceptedNodesDitImpl, queryParser, inventoryMapper)

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
      Constants.CFENGINE_COMMUNITY_PROMISES_PATH,
      Constants.CFENGINE_NOVA_PROMISES_PATH
    )

    /*
     * For now, we don't want to query server other
     * than the accepted ones.
     */
    lazy val getSubGroupChoices = new DefaultSubGroupComparatorRepository(roLdapNodeGroupRepository)
    lazy val nodeQueryData      = new NodeQueryCriteriaData(() => getSubGroupChoices)
    lazy val ditQueryDataImpl   = new DitQueryData(acceptedNodesDitImpl, nodeDit, rudderDit, nodeQueryData)
    lazy val queryParser        = new CmdbQueryParser with DefaultStringQueryParser with JsonQueryLexer {
      override val criterionObjects = Map[String, ObjectCriterion]() ++ ditQueryDataImpl.criteriaMap
    }
    lazy val inventoryMapper: InventoryMapper =
      new InventoryMapper(inventoryDitService, pendingNodesDitImpl, acceptedNodesDitImpl, removedNodesDitImpl)

    lazy val ldapDiffMapper = new LDAPDiffMapper(ldapEntityMapper, queryParser)

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
    lazy val entityMigration                        = DefaultXmlEventLogMigration

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
      configService.rudder_ui_changeMessage_enabled _,
      configService.rudder_ui_changeMessage_mandatory _,
      configService.rudder_ui_changeMessage_explanation _
    )
    lazy val userPropertyService     = userPropertyServiceImpl

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
        poolSize = LDAP_MAX_POOL_SIZE
      )
    }
    lazy val roLDAPConnectionProvider = roLdap
    lazy val rwLdap                   = {
      new RWPooledSimpleAuthConnectionProvider(
        host = LDAP_HOST,
        port = LDAP_PORT,
        authDn = LDAP_AUTHDN,
        authPw = LDAP_AUTHPW,
        poolSize = LDAP_MAX_POOL_SIZE
      )
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

    // used in accept node to see & store inventories on acceptation
    lazy val inventoryHistoryLogRepository: InventoryHistoryLogRepository = {
      val fullInventoryFromLdapEntries: FullInventoryFromLdapEntries =
        new FullInventoryFromLdapEntriesImpl(inventoryDitService, inventoryMapper)

      new InventoryHistoryLogRepository(
        HISTORY_INVENTORIES_ROOTDIR,
        new FullInventoryFileParser(fullInventoryFromLdapEntries, inventoryMapper)
      )
    }

    lazy val nodeGridImpl = new NodeGrid(nodeFactRepository, configService)

    lazy val modificationService      =
      new ModificationService(logRepository, gitModificationRepository, itemArchiveManagerImpl, uuidGen)
    lazy val eventListDisplayerImpl   = new EventListDisplayer(logRepository)
    lazy val eventLogDetailsGenerator = new EventLogDetailsGenerator(
      eventLogDetailsServiceImpl,
      logRepository,
      roLdapNodeGroupRepository,
      roLdapDirectiveRepository,
      nodeFactInfoService,
      roLDAPRuleCategoryRepository,
      modificationService,
      personIdentServiceImpl,
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
        uuidGen,
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

    lazy val woFactNodeRepository: WoNodeRepository = new WoFactNodeRepositoryProxy(nodeFactRepository)

    lazy val roLdapNodeGroupRepository = new RoLDAPNodeGroupRepository(
      rudderDitImpl,
      roLdap,
      ldapEntityMapper,
      groupLibReadWriteMutex
    )
    lazy val roNodeGroupRepository: RoNodeGroupRepository = roLdapNodeGroupRepository

    lazy val woLdapNodeGroupRepository = new WoLDAPNodeGroupRepository(
      roLdapNodeGroupRepository,
      rwLdap,
      ldapDiffMapper,
      uuidGen,
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
        uuidGen,
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
      gitRevisionProviderImpl,
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
        () => configService.rudder_compliance_mode_name().toBox,
        () => configService.rudder_compliance_heartbeatPeriod().toBox
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
      () => configService.cfengine_server_denybadclocks().toBox,
      () => configService.relay_server_sync_method().toBox,
      () => configService.relay_server_syncpromises().toBox,
      () => configService.relay_server_syncsharedfiles().toBox,
      () => configService.cfengine_modified_files_ttl().toBox,
      () => configService.cfengine_outputs_ttl().toBox,
      () => configService.send_server_metrics().toBox,
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
        uuidGen
      )
    )

    lazy val techniqueRepositoryImpl = {
      val service = new TechniqueRepositoryImpl(
        techniqueReader,
        Seq(),
        uuidGen
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

    lazy val deploymentService = {
      new PromiseGenerationServiceImpl(
        roLdapRuleRepository,
        woLdapRuleRepository,
        ruleValService,
        systemVariableService,
        nodeConfigurationHashRepo,
        nodeFactRepository,
        updateExpectedRepo,
        roNodeGroupRepository,
        roDirectiveRepository,
        configurationRepository,
        ruleApplicationStatusImpl,
        roParameterServiceImpl,
        interpolationCompiler,
        globalComplianceModeService,
        globalAgentRunService,
        reportingServiceImpl,
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
        POSTGRESQL_IS_LOCAL
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
        acceptHostnameAndIp ::
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
    lazy val scoreService          = new ScoreServiceImpl(globalScoreRepository, scoreRepository)
    lazy val scoreServiceManager: ScoreServiceManager = new ScoreServiceManager(scoreService)

    /////// reporting ///////

    lazy val nodeConfigurationHashRepo: NodeConfigurationHashRepository = {
      val x = new FileBasedNodeConfigurationHashRepository(FileBasedNodeConfigurationHashRepository.defaultHashesPath)
      x.init
      x
    }

    lazy val reportingServiceImpl: CachedReportingServiceImpl = {
      val reportingServiceImpl = new CachedReportingServiceImpl(
        new ReportingServiceImpl(
          findExpectedRepo,
          reportsRepositoryImpl,
          roAgentRunsRepository,
          globalAgentRunService,
          nodeFactRepository,
          roLdapDirectiveRepository,
          roRuleRepository,
          cachedNodeConfigurationService,
          () => globalComplianceModeService.getGlobalComplianceMode,
          configService.rudder_global_policy_mode _,
          () => configService.rudder_compliance_unexpected_report_interpretation().toBox,
          RUDDER_JDBC_BATCH_MAX_SIZE
        ),
        nodeFactRepository,
        RUDDER_JDBC_BATCH_MAX_SIZE, // use same size as for SQL requests

        complianceRepositoryImpl,
        scoreServiceManager
      )
      // to avoid a StackOverflowError, we set the compliance cache once it'z ready,
      // and can construct the nodeconfigurationservice without the comlpince cache
      cachedNodeConfigurationService.addHook(reportingServiceImpl)
      reportingServiceImpl
    }

    lazy val reportingService: ReportingService = reportingServiceImpl

    lazy val pgIn                     = new PostgresqlInClause(70)
    lazy val findExpectedRepo         = new FindExpectedReportsJdbcRepository(doobie, pgIn, RUDDER_JDBC_BATCH_MAX_SIZE)
    lazy val updateExpectedRepo       = new UpdateExpectedReportsJdbcRepository(doobie, pgIn, RUDDER_JDBC_BATCH_MAX_SIZE)
    lazy val reportsRepositoryImpl    = new ReportsJdbcRepository(doobie)
    lazy val reportsRepository        = reportsRepositoryImpl
    lazy val complianceRepositoryImpl = new ComplianceJdbcRepository(
      doobie,
      () => configService.rudder_save_db_compliance_details().toBox,
      () => configService.rudder_save_db_compliance_levels().toBox
    )
    lazy val dataSourceProvider       = new RudderDatasourceProvider(
      RUDDER_JDBC_DRIVER,
      RUDDER_JDBC_URL,
      RUDDER_JDBC_USERNAME,
      RUDDER_JDBC_PASSWORD,
      RUDDER_JDBC_MAX_POOL_SIZE
    )
    lazy val doobie                   = new Doobie(dataSourceProvider.datasource)

    lazy val parseRules:                  ParseRules with RuleRevisionRepository         = new GitParseRules(
      ruleUnserialisation,
      gitConfigRepo,
      entityMigration,
      rulesDirectoryName
    )
    lazy val parseActiveTechniqueLibrary: GitParseActiveTechniqueLibrary                 = new GitParseActiveTechniqueLibrary(
      activeTechniqueCategoryUnserialisation,
      activeTechniqueUnserialisation,
      directiveUnserialisation,
      gitConfigRepo,
      gitRevisionProvider,
      entityMigration,
      userLibraryDirectoryName
    )
    lazy val importTechniqueLibrary:      ImportTechniqueLibrary                         = new ImportTechniqueLibraryImpl(
      rudderDitImpl,
      rwLdap,
      ldapEntityMapper,
      uptLibReadWriteMutex
    )
    lazy val parseGroupLibrary:           ParseGroupLibrary with GroupRevisionRepository = new GitParseGroupLibrary(
      nodeGroupCategoryUnserialisation,
      nodeGroupUnserialisation,
      gitConfigRepo,
      entityMigration,
      groupLibraryDirectoryName
    )
    lazy val parseGlobalParameter:        ParseGlobalParameters                          = new GitParseGlobalParameters(
      globalParameterUnserialisation,
      gitConfigRepo,
      entityMigration,
      parametersDirectoryName
    )
    lazy val parseRuleCategories:         ParseRuleCategories                            = new GitParseRuleCategories(
      ruleCategoryUnserialisation,
      gitConfigRepo,
      entityMigration,
      ruleCategoriesDirectoryName
    )
    lazy val importGroupLibrary:          ImportGroupLibrary                             = new ImportGroupLibraryImpl(
      rudderDitImpl,
      rwLdap,
      ldapEntityMapper,
      groupLibReadWriteMutex
    )
    lazy val importRuleCategoryLibrary:   ImportRuleCategoryLibrary                      = new ImportRuleCategoryLibraryImpl(
      rudderDitImpl,
      rwLdap,
      ldapEntityMapper,
      ruleCatReadWriteMutex
    )
    lazy val eventLogDeploymentServiceImpl = new EventLogDeploymentService(logRepository, eventLogDetailsServiceImpl)

    lazy val nodeFactInfoService = new NodeInfoServiceProxy(nodeFactRepository)
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
      asyncDeploymentAgentImpl,
      uuidGen,
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
      asyncDeploymentAgent,
      uuidGen,
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
        new RemoveNodeFromGroups(roNodeGroupRepository, woNodeGroupRepository, uuidGen)
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
    lazy val campaignPath                   = root / "var" / "rudder" / "configuration-repository" / "campaigns"

    lazy val campaignRepo = CampaignRepositoryImpl
      .make(campaignSerializer, campaignPath, campaignEventRepo)
      .runOrDie(err => new RuntimeException(s"Error during initialization of campaign repository: " + err.fullMsg))

    lazy val mainCampaignService = new MainCampaignService(campaignEventRepo, campaignRepo, uuidGen, 1, 1)
    lazy val jsonReportsAnalyzer = JSONReportsAnalyser(reportsRepository, propertyRepository)

    /*
     * *************************************************
     * Bootstrap check actions
     * **************************************************
     */

    lazy val allBootstrapChecks = new SequentialImmediateBootStrapChecks(
      new CheckConnections(dataSourceProvider, rwLdap),
      new CheckTableScore(doobie),
      new CheckTableUsers(doobie),
      new MigrateEventLogEnforceSchema(doobie),
      new MigrateChangeValidationEnforceSchema(doobie),
      new MigrateNodeAcceptationInventories(
        nodeFactInfoService,
        doobie,
        inventoryHistoryLogRepository,
        inventoryHistoryJdbcRepository,
        KEEP_DELETED_NODE_FACT_DURATION
      ),
      new CheckTechniqueLibraryReload(
        techniqueRepositoryImpl,
        uuidGen
      ),
      new CheckAddSpecialNodeGroupsDescription(rwLdap),
      new CheckRemoveRuddercSetting(rwLdap),
      new CheckDIT(pendingNodesDitImpl, acceptedNodesDitImpl, removedNodesDitImpl, rudderDitImpl, rwLdap),
      new CheckInitUserTemplateLibrary(
        rudderDitImpl,
        rwLdap,
        techniqueRepositoryImpl,
        roLdapDirectiveRepository,
        woLdapDirectiveRepository,
        uuidGen,
        asyncDeploymentAgentImpl
      ), // new CheckDirectiveBusinessRules()

      new CheckRudderGlobalParameter(roLDAPParameterRepository, woLDAPParameterRepository, uuidGen),
      new CheckInitXmlExport(itemArchiveManagerImpl, personIdentServiceImpl, uuidGen),
      new MigrateNodeAcceptationInventories(
        nodeFactInfoService,
        doobie,
        inventoryHistoryLogRepository,
        inventoryHistoryJdbcRepository,
        KEEP_DELETED_NODE_FACT_DURATION
      ),
      new CheckNcfTechniqueUpdate(
        ncfTechniqueWriter,
        roLDAPApiAccountRepository.systemAPIAccount,
        uuidGen,
        updateTechniqueLibrary,
        ncfTechniqueReader,
        resourceFileService
      ),
      new MigrateJsonTechniquesToYaml(
        ncfTechniqueWriter,
        uuidGen,
        updateTechniqueLibrary,
        gitConfigRepo.rootDirectory.pathAsString
      ),
      new TriggerPolicyUpdate(
        asyncDeploymentAgent,
        uuidGen
      ),
      new RemoveFaultyLdapEntries(
        woDirectiveRepository,
        uuidGen
      ),
      new CreateSystemToken(roLDAPApiAccountRepository.systemAPIAccount),
      new LoadNodeComplianceCache(nodeFactInfoService, reportingServiceImpl),
      new CloseOpenUserSessions(userRepository)
    )

    //////////////////////////////////////////////////////////////////////////////////////////
    ////////////////////////////// Directive Editor and web fields //////////////////////////////
    //////////////////////////////////////////////////////////////////////////////////////////

    import com.normation.cfclerk.domain._

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
              case DateVType(r)               => new DateField(Translator.isoDateFormatter)(id)
              case TimeVType(r)               => new TimeField(Translator.isoTimeFormatter)(id)
              case PermVType                  => new FilePermsField(id)
              case BooleanVType               => new CheckboxField(id)
              case TextareaVType(r)           =>
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
              case AixDerivedPasswordVType    => new DerivedPasswordField(id, HashAlgoConstraint.DerivedPasswordType.AIX)
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

      override def default(id: String) = new TextField(id, () => configService.rudder_featureSwitch_directiveScriptEngine().toBox)
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
      techniqueRepositoryImpl,
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
      val maxCatchupTime  = {
        val temp = FiniteDuration(RUDDER_REPORTS_EXECUTION_MAX_DAYS.toLong, "day") + FiniteDuration(
          RUDDER_REPORTS_EXECUTION_MAX_MINUTES.toLong,
          "minutes"
        )
        if (temp.toMillis == 0) {
          logger.error(
            "'rudder.aggregateReports.maxDays' and 'rudder.aggregateReports.maxMinutes' properties are both 0 or empty. Set using 30 minutes as default value, please check /opt/rudder/etc/rudder-web.properties"
          )
          FiniteDuration(30, "minutes")
        } else {
          temp
        }
      }
      val maxCatchupBatch = FiniteDuration(RUDDER_REPORTS_EXECUTION_MAX_SIZE.toLong, "minutes")

      new ReportsExecutionService(
        reportsRepository,
        updatesEntryJdbcRepository,
        recentChangesService,
        reportingServiceImpl,
        complianceRepositoryImpl,
        maxCatchupTime,
        maxCatchupBatch
      )
    }

    lazy val aggregateReportScheduler = new FindNewReportsExecution(executionService, RUDDER_REPORTS_EXECUTION_INTERVAL)

    // aggregate information about node count
    // don't forget to start-it once out of the zone which lead to dead-lock (ie: in Lift boot)
    lazy val historizeNodeCountBatch = for {
      gitLogger <- CommitLogServiceImpl.make(METRICS_NODES_DIRECTORY_GIT_ROOT)
      writer    <- WriteNodeCSV.make(METRICS_NODES_DIRECTORY_GIT_ROOT, ';', "yyyy-MM")
      service    = new HistorizeNodeCountService(
                     new FetchDataServiceImpl(RudderConfig.nodeFactRepository, RudderConfig.reportingService),
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
//      uuidGen,
//      RUDDER_BATCH_CHECK_NODE_CACHE_INTERVAL
//    )

    lazy val asynComplianceService = new AsyncComplianceService(reportingService)

    /*
     * here goes deprecated services that we can't remove yet, for example because they are used for migration
     */
    object deprecated {
      lazy val ldapFullInventoryRepository =
        new FullInventoryRepositoryImpl(inventoryDitService, inventoryMapper, rwLdap)

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
          override def getNodeIds(groupId: NodeGroupId): IOResult[Chunk[NodeId]] = Chunk.empty.succeed

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
            new NodeQueryCriteriaData(() => subGroup)
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
        reportingServiceImpl
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
      woFactNodeRepository,
      roLdapNodeGroupRepository,
      woLdapNodeGroupRepository,
      techniqueRepositoryImpl,
      techniqueRepositoryImpl,
      roLdapDirectiveRepository,
      woLdapDirectiveRepository,
      deprecated.softwareInventoryDAO,
      eventLogRepository,
      eventLogDetailsServiceImpl,
      reportingServiceImpl,
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
      nodeFactInfoService,
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
      new SrvGrid(roAgentRunsRepository, configService, roLdapRuleRepository, nodeFactInfoService, scoreService),
      findExpectedRepo,
      roLDAPApiAccountRepository,
      woLDAPApiAccountRepository,
      cachedAgentRunRepository,
      pendingNodeCheckGroup,
      allBootstrapChecks,
      authenticationProviders,
      rudderUserListProvider,
      restApiAccounts,
      restQuicksearch,
      restCompletion,
      sharedFileApi,
      eventLogApi,
      uuidGen,
      inventoryWatcher,
      configService,
      historizeNodeCountBatch,
      policyGenerationBootGuard,
      healthcheckNotificationService,
      jsonPluginDefinition,
      rudderApi,
      authorizationApiMapping,
      roRuleCategoryRepository,
      woRuleCategoryRepository,
      workflowLevelService,
      ncfTechniqueReader,
      recentChangesService,
      ruleCategoryService,
      restExtractorService,
      snippetExtensionRegister,
      clearCacheService,
      linkUtil,
      userRepository,
      userService,
      ApiVersions,
      apiDispatcher,
      configurationRepository,
      roParameterService,
      userAuthorisationLevel,
      agentRegister,
      asyncWorkflowInfo,
      commitAndDeployChangeRequest,
      doobie,
      restDataSerializer,
      workflowEventLogService,
      changeRequestEventLogService,
      changeRequestChangesUnserialisation,
      diffService,
      diffDisplayer,
      rwLdap,
      apiAuthorizationLevelService,
      tokenGenerator,
      roLDAPParameterRepository,
      interpolationCompiler,
      deploymentService,
      campaignEventRepo,
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
      tenantService
    )

    // need to be done here to avoid cyclic dependencies
    (nodeFactRepository.registerChangeCallbackAction(
      new GenerationOnChange(updateDynamicGroups, asyncDeploymentAgent, uuidGen)
    ) *>
    nodeFactRepository.registerChangeCallbackAction(
      new CacheInvalidateNodeFactEventCallback(cachedNodeConfigurationService, reportingServiceImpl, Nil)
    )).runNow
    // This needs to be done at the end, to be sure that all is initialized
    deploymentService.setDynamicsGroupsService(dyngroupUpdaterBatch)
    // we need to reference batches not part of the API to start them since
    // they are lazy val
    cleanOldInventoryBatch.start()
    gitFactRepoGC.start()
    gitConfigRepoGC.start()
    // todo: scheduler interval should be a property
    ZioRuntime.unsafeRun(jsonReportsAnalyzer.start(5.seconds).forkDaemon.provideLayer(ZioRuntime.layers))
    ZioRuntime.unsafeRun(MainCampaignService.start(mainCampaignService))
    rudderUserListProvider.registerCallback(UserRepositoryUpdateOnFileReload.createCallback(userRepository))
    userCleanupBatch.start()

    // UpdateDynamicGroups is part of rci
    // reportingServiceImpl part of rci
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
