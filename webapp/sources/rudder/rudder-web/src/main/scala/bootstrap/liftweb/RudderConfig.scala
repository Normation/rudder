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

import java.io.File
import java.security.Security
import java.util.concurrent.TimeUnit
import better.files.File.root
import com.normation.rudder.apidata.RestDataSerializerImpl
import com.normation.appconfig._

import com.normation.box._
import com.normation.cfclerk.services._
import com.normation.cfclerk.services.impl._
import com.normation.cfclerk.xmlparsers._
import com.normation.cfclerk.xmlwriters.SectionSpecWriter
import com.normation.cfclerk.xmlwriters.SectionSpecWriterImpl

import com.normation.errors.IOResult
import com.normation.errors.SystemError
import com.normation.inventory.domain._
import com.normation.inventory.ldap.core._
import com.normation.inventory.ldap.provisioning.AddIpValues
import com.normation.inventory.ldap.provisioning.CheckMachineName
import com.normation.inventory.ldap.provisioning.CheckOsType
import com.normation.inventory.ldap.provisioning.DefaultLDIFInventoryLogger
import com.normation.inventory.ldap.provisioning.DefaultInventorySaver
import com.normation.inventory.ldap.provisioning.FromMotherBoardUuidIdFinder
import com.normation.inventory.ldap.provisioning.LastInventoryDate
import com.normation.inventory.ldap.provisioning.LogInventoryPreCommit
import com.normation.inventory.ldap.provisioning.NameAndVersionIdFinder
import com.normation.inventory.ldap.provisioning.PendingNodeIfNodeWasRemoved
import com.normation.inventory.ldap.provisioning.PostCommitLogger
import com.normation.inventory.ldap.provisioning.UseExistingMachineIdFinder
import com.normation.inventory.ldap.provisioning.UseExistingNodeIdFinder
import com.normation.inventory.ldap.provisioning.UuidMergerPreCommit
import com.normation.inventory.provisioning.fusion.FusionInventoryParser
import com.normation.inventory.provisioning.fusion.PreInventoryParserCheckConsistency
import com.normation.inventory.services.core._
import com.normation.inventory.services.provisioning.DefaultInventoryParser
import com.normation.inventory.services.provisioning.InventoryDigestServiceV1
import com.normation.inventory.services.provisioning.MachineDNFinderService
import com.normation.inventory.services.provisioning.NamedMachineDNFinderAction
import com.normation.inventory.services.provisioning.NamedNodeInventoryDNFinderAction
import com.normation.inventory.services.provisioning.NodeInventoryDNFinderService
import com.normation.inventory.services.provisioning.PreCommit
import com.normation.inventory.services.provisioning.InventoryParser
import com.normation.ldap.sdk._
import com.normation.plugins.FilePluginSettingsService
import com.normation.plugins.ReadPluginPackageInfo
import com.normation.plugins.SnippetExtensionRegister
import com.normation.plugins.SnippetExtensionRegisterImpl
import com.normation.rudder.UserService
import com.normation.rudder.api._
import com.normation.rudder.apidata.ZioJsonExtractor
import com.normation.rudder.batch._
import com.normation.rudder.configuration.ConfigurationRepositoryImpl
import com.normation.rudder.configuration.RuleRevisionRepository
import com.normation.rudder.db.Doobie
import com.normation.rudder.domain._
import com.normation.rudder.domain.logger.ApplicationLogger
import com.normation.rudder.domain.logger.NodeConfigurationLoggerImpl
import com.normation.rudder.domain.logger.ScheduledJobLoggerPure
import com.normation.rudder.domain.queries._
import com.normation.rudder.facts.nodes.GitNodeFactRepository
import com.normation.rudder.git.GitRepositoryProviderImpl
import com.normation.rudder.git.GitRevisionProvider
import com.normation.rudder.inventory.DefaultProcessInventoryService
import com.normation.rudder.inventory.FactRepositoryPostCommit
import com.normation.rudder.inventory.InventoryFailedHook
import com.normation.rudder.inventory.InventoryFileWatcher
import com.normation.rudder.inventory.InventoryMover
import com.normation.rudder.inventory.InventoryProcessor
import com.normation.rudder.inventory.PostCommitInventoryHooks
import com.normation.rudder.inventory.ProcessFile
import com.normation.rudder.metrics._
import com.normation.rudder.migration.DefaultXmlEventLogMigration
import com.normation.rudder.ncf
import com.normation.rudder.ncf.ParameterType.PlugableParameterTypeService
import com.normation.rudder.ncf.ResourceFileService
import com.normation.rudder.ncf.RudderCRunner
import com.normation.rudder.ncf.TechniqueArchiverImpl
import com.normation.rudder.ncf.TechniqueSerializer
import com.normation.rudder.ncf.TechniqueWriter
import com.normation.rudder.reports.AgentRunIntervalService
import com.normation.rudder.reports.AgentRunIntervalServiceImpl
import com.normation.rudder.reports.ComplianceModeService
import com.normation.rudder.reports.ComplianceModeServiceImpl
import com.normation.rudder.reports.execution._
import com.normation.rudder.repository._
import com.normation.rudder.repository.jdbc._
import com.normation.rudder.repository.ldap._
import com.normation.rudder.repository.xml.GitParseTechniqueLibrary
import com.normation.rudder.repository.xml._
import com.normation.rudder.rest.RestExtractorService
import com.normation.rudder.rest._
import com.normation.rudder.rest.internal._
import com.normation.rudder.rest.lift._
import com.normation.rudder.rule.category.GitRuleCategoryArchiverImpl
import com.normation.rudder.rule.category._
import com.normation.rudder.services._
import com.normation.rudder.services.eventlog.EventLogFactoryImpl
import com.normation.rudder.services.eventlog._
import com.normation.rudder.services.healthcheck._
import com.normation.rudder.services.marshalling._
import com.normation.rudder.services.modification.DiffService
import com.normation.rudder.services.modification.DiffServiceImpl
import com.normation.rudder.services.modification.ModificationService
import com.normation.rudder.services.nodes._
import com.normation.rudder.services.policies.DeployOnTechniqueCallback
import com.normation.rudder.services.policies._
import com.normation.rudder.services.policies.nodeconfig._
import com.normation.rudder.services.policies.write.AgentRegister
import com.normation.rudder.services.policies.write.BuildBundleSequence
import com.normation.rudder.services.policies.write.PathComputerImpl
import com.normation.rudder.services.policies.write.PolicyWriterServiceImpl
import com.normation.rudder.services.policies.write.PrepareTemplateVariablesImpl
import com.normation.rudder.services.policies.write.WriteAllAgentSpecificFiles
import com.normation.rudder.services.queries._
import com.normation.rudder.services.quicksearch.FullQuickSearchService
import com.normation.rudder.services.reports._
import com.normation.rudder.services.servers._
import com.normation.rudder.services.system._
import com.normation.rudder.services.user.PersonIdentService
import com.normation.rudder.services.user.TrivialPersonIdentService
import com.normation.rudder.services.workflows._
import com.normation.rudder.web.model._
import com.normation.rudder.web.services.EventLogDetailsGenerator
import com.normation.rudder.web.services.UserPropertyService
import com.normation.rudder.web.services._
import com.normation.templates.FillTemplatesService
import com.normation.utils.CronParser._
import com.normation.utils.StringUuidGenerator
import com.normation.utils.StringUuidGeneratorImpl

import bootstrap.liftweb.checks.action.CheckNcfTechniqueUpdate
import bootstrap.liftweb.checks.action.CheckTechniqueLibraryReload
import bootstrap.liftweb.checks.action.CreateSystemToken
import bootstrap.liftweb.checks.action.LoadNodeComplianceCache
import bootstrap.liftweb.checks.action.TriggerPolicyUpdate
import bootstrap.liftweb.checks.consistency.CheckConnections
import bootstrap.liftweb.checks.consistency.CheckDIT
import bootstrap.liftweb.checks.consistency.CheckRudderGlobalParameter
import bootstrap.liftweb.checks.migration.CheckAddSpecialTargetAllPolicyServers
import bootstrap.liftweb.checks.migration.CheckMigratedSystemTechniques
import bootstrap.liftweb.checks.onetimeinit.CheckInitUserTemplateLibrary
import bootstrap.liftweb.checks.onetimeinit.CheckInitXmlExport

import com.normation.zio._
import com.typesafe.config.Config
import com.typesafe.config.ConfigException
import com.typesafe.config.ConfigFactory
import com.unboundid.ldap.sdk.DN
import com.unboundid.ldap.sdk.RDN
import net.liftweb.common.Loggable
import net.liftweb.common._
import org.apache.commons.io.FileUtils
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.joda.time.DateTimeZone

import zio.IO
import zio.syntax._
import zio.duration._
import scala.collection.mutable.Buffer
import scala.concurrent.duration.FiniteDuration

import zio.Ref

object RUDDER_CHARSET {
  import java.nio.charset.StandardCharsets
  def name = "UTF-8"
  def value = StandardCharsets.UTF_8
}

/**
 * Define a resource for configuration.
 * For now, config properties can only be loaded from either
 * a file in the classpath, or a file in the file system.
 */
sealed trait ConfigResource extends Any
final case class ClassPathResource(name: String) extends AnyVal with ConfigResource
final case class FileSystemResource(file: File) extends AnyVal with ConfigResource

/**
 * User defined configuration variable
 * (from properties file or alike)
 */
object RudderProperties {

  val JVM_CONFIG_FILE_KEY = "rudder.configFile"
  val DEFAULT_CONFIG_FILE_NAME = "configuration.properties"

  // Set security provider with bouncy castle one
  Security.addProvider(new BouncyCastleProvider())

  /**
   * Where to go to look for properties
   */
  val configResource = System.getProperty(JVM_CONFIG_FILE_KEY) match {
      case null | "" => //use default location in classpath
        ApplicationLogger.info("JVM property -D%s is not defined, use configuration file in classpath".format(JVM_CONFIG_FILE_KEY))
        ClassPathResource(DEFAULT_CONFIG_FILE_NAME)
      case x => //so, it should be a full path, check it
        val config = new File(x)
        if(config.exists && config.canRead) {
          ApplicationLogger.info("Use configuration file defined by JVM property -D%s : %s".format(JVM_CONFIG_FILE_KEY, config.getPath))
          FileSystemResource(config)
        } else {
          ApplicationLogger.error("Can not find configuration file specified by JVM property %s: %s ; abort".format(JVM_CONFIG_FILE_KEY, config.getPath))
          throw new javax.servlet.UnavailableException("Configuration file not found: %s".format(config.getPath))
        }
    }

  // some value used as defaults for migration
  val migrationConfig =
    s"""rudder.batch.reportscleaner.compliancelevels.delete.TTL=15
    """

  val config : Config = {
    (configResource match {
      case ClassPathResource(name) => ConfigFactory.load(name)
      case FileSystemResource(file) => ConfigFactory.load(ConfigFactory.parseFile(file))
    }).withFallback(ConfigFactory.parseString(migrationConfig))
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

/**
 * Static initialization of Rudder services.
 * This is not a cake-pattern, just a plain object with load of lazy vals.
 */
object RudderConfig extends Loggable {
  import RudderProperties.config

  private case class InitError(msg: String) extends Throwable(msg, null, false, false)

  // set the file location that contains mime info
  System.setProperty("content.types.user.table", this.getClass.getClassLoader.getResource("content-types.properties").getPath)

  //
  // Public properties
  // Here, we define static nouns for all theses properties
  //

  private[this] val filteredPasswords = scala.collection.mutable.Buffer[String]()

  //the LDAP password used for authentication is not used here, but should not appear nonetheless
  filteredPasswords += "rudder.auth.ldap.connection.bind.password"
  // filter the fallback admin password
  filteredPasswords += "rudder.auth.admin.password"

  // list of configuration properties that we want to totally hide
  val hiddenRegisteredProperties = scala.collection.mutable.Buffer[String]()
  hiddenRegisteredProperties += "rudder.dir.licensesFolder"

  // auth backend is init too late to have a chance to hide its values, which is a bit sad.
  // We still need to make invisible all oauth/oidc client secret
  hiddenRegisteredProperties ++= {
    import scala.jdk.CollectionConverters._
    config.entrySet().asScala.map(_.getKey).filter(s => s.startsWith("rudder.auth.oauth2.provider") && s.endsWith("client.secret"))
  }
  //other values

  val LDAP_HOST = config.getString("ldap.host")
  val LDAP_PORT = config.getInt("ldap.port")
  val LDAP_AUTHDN = config.getString("ldap.authdn")
  val LDAP_AUTHPW = config.getString("ldap.authpw") ; filteredPasswords += "ldap.authpw"
  val LDAP_MAX_POOL_SIZE = {
    try {
      config.getInt("ldap.maxPoolSize")
    } catch {
      case ex: ConfigException =>
        ApplicationLogger.info("Property 'ldap.maxPoolSize' is missing or empty in rudder.configFile. Default to 2 connections.")
        2
    }
  }
  val LDAP_CACHE_NODE_INFO_MIN_INTERVAL = {
    val x = try {
      config.getInt("ldap.nodeinfo.cache.min.interval")
    } catch {
      case ex: ConfigException =>
        ApplicationLogger.debug("Property 'ldap.nodeinfo.cache.min.interval' is missing or empty in rudder.configFile. Default to 100 ms.")
        100
    }
    if(x < 0) { // 0 is ok, it means "always check"
      100.millis
    } else {
      x.millis
    }
  }
  val RUDDER_DIR_BACKUP = {
    try {
      config.getString("rudder.dir.backup").trim match {
        case "" => None
        case x  => Some(x)
      }
    } catch {
      case ex: ConfigException => None
    }
  }
  val RUDDER_DIR_DEPENDENCIES = config.getString("rudder.dir.dependencies")
  val RUDDER_DIR_LOCK = config.getString("rudder.dir.lock") //TODO no more used ?
  val RUDDER_DIR_SHARED_FILES_FOLDER = config.getString("rudder.dir.shared.files.folder")
  val RUDDER_WEBDAV_USER = config.getString("rudder.webdav.user")
  val RUDDER_WEBDAV_PASSWORD = config.getString("rudder.webdav.password") ; filteredPasswords += "rudder.webdav.password"
  val CFENGINE_POLICY_DISTRIBUTION_PORT = try {
    config.getInt("rudder.policy.distribution.port.cfengine")
  } catch {
    case ex: ConfigException =>
      try {
        config.getInt("rudder.community.port") // for compat
      } catch {
        case ex: ConfigException =>
          ApplicationLogger.info("Property 'rudder.policy.distribution.port.cfengine' is missing or empty in Rudder configuration file. Default to 5309")
          5309
      }
  }
  val HTTPS_POLICY_DISTRIBUTION_PORT = try {
    config.getInt("rudder.policy.distribution.port.https")
  } catch {
    case ex: ConfigException =>
      ApplicationLogger.info("Property 'rudder.policy.distribution.port.https' is missing or empty in Rudder configuration file. Default to 443")
      443
  }

  val POSTGRESQL_IS_LOCAL = {
    try {
      config.getBoolean("rudder.postgresql.local")
    } catch {
      case ex:ConfigException => true
    }
  }


  val RUDDER_JDBC_DRIVER = config.getString("rudder.jdbc.driver")
  val RUDDER_JDBC_URL = config.getString("rudder.jdbc.url")
  val RUDDER_JDBC_USERNAME = config.getString("rudder.jdbc.username")
  val RUDDER_JDBC_PASSWORD = config.getString("rudder.jdbc.password") ; filteredPasswords += "rudder.jdbc.password"
  val RUDDER_JDBC_MAX_POOL_SIZE = config.getInt("rudder.jdbc.maxPoolSize")

  val RUDDER_JDBC_BATCH_MAX_SIZE = {
    val x = try {
      config.getInt("rudder.jdbc.batch.max.size")
    } catch {
      case ex: ConfigException =>
        ApplicationLogger.debug("Property 'rudder.jdbc.batch.max.size' is missing or empty in rudder.configFile. Default to 500.")
        500
    }
    if(x < 0) { // 0 is ok, it means "always check"
      500
    } else {
      x
    }
  }

  val RUDDER_GIT_GC = (try {
    config.getString("rudder.git.gc")
  } catch {
    // missing key, perhaps due to migration, use default
    case ex: Exception => {
      val default = "0 42 3 * * ?"
      logger.info(s"`rudder.git.gc` property is missing, using default schedule: ${default}")
      default
    }
  }).toOptCron match {
    case Left(err)  =>
      logger.error(s"Error when parsing cron for 'rudder.git.gc', it will be disabled: ${err.fullMsg}")
      None
    case Right(opt) => opt
  }

  /*
   * Root directory for git config-repo et fact-repo.
   * We should homogeneize naming here, ie s/rudder.dir.gitRoot/rudder.dir.gitRootConfigRepo/
   */
  val RUDDER_GIT_ROOT_CONFIG_REPO = config.getString("rudder.dir.gitRoot")
  val RUDDER_GIT_ROOT_FACT_REPO = {
    try {
      config.getString("rudder.dir.gitRootFactRepo")
    } catch {
      case ex: Exception => "/var/rudder/fact-repository"
    }
  }
  val RUDDER_DIR_TECHNIQUES = RUDDER_GIT_ROOT_CONFIG_REPO + "/techniques"
  val RUDDER_BATCH_DYNGROUP_UPDATEINTERVAL = config.getInt("rudder.batch.dyngroup.updateInterval") //60 //one hour
  val RUDDER_BATCH_TECHNIQUELIBRARY_UPDATEINTERVAL = config.getInt("rudder.batch.techniqueLibrary.updateInterval") //60 * 5 //five minutes
  val RUDDER_BATCH_REPORTSCLEANER_ARCHIVE_TTL = config.getInt("rudder.batch.reportscleaner.archive.TTL") //AutomaticReportsCleaning.defaultArchiveTTL
  val RUDDER_BATCH_REPORTSCLEANER_DELETE_TTL = config.getInt("rudder.batch.reportscleaner.delete.TTL") //AutomaticReportsCleaning.defaultDeleteTTL
  val RUDDER_BATCH_REPORTSCLEANER_COMPLIANCE_DELETE_TTL = config.getInt("rudder.batch.reportscleaner.compliancelevels.delete.TTL") //AutomaticReportsCleaning.defaultDeleteTTL
  val RUDDER_BATCH_REPORTSCLEANER_LOG_DELETE_TTL = try {
    config.getString("rudder.batch.reportscleaner.deleteReportLog.TTL")
  } catch {
    case ex: Exception => "2x"
  }
  val RUDDER_BATCH_REPORTSCLEANER_FREQUENCY = config.getString("rudder.batch.reportscleaner.frequency") //AutomaticReportsCleaning.defaultDay
  val RUDDER_BATCH_DATABASECLEANER_RUNTIME_HOUR = config.getInt("rudder.batch.databasecleaner.runtime.hour") //AutomaticReportsCleaning.defaultHour
  val RUDDER_BATCH_DATABASECLEANER_RUNTIME_MINUTE = config.getInt("rudder.batch.databasecleaner.runtime.minute") //AutomaticReportsCleaning.defaultMinute
  val RUDDER_BATCH_DATABASECLEANER_RUNTIME_DAY = config.getString("rudder.batch.databasecleaner.runtime.day") //"sunday"
  val RUDDER_BATCH_REPORTS_LOGINTERVAL = config.getInt("rudder.batch.reports.logInterval") //1 //one minute
  val RUDDER_TECHNIQUELIBRARY_GIT_REFS_PATH = "refs/heads/master"
  // THIS ONE IS STILL USED FOR USERS USING GIT REPLICATION
  val RUDDER_AUTOARCHIVEITEMS = config.getBoolean("rudder.autoArchiveItems") //true
  val RUDDER_REPORTS_EXECUTION_MAX_DAYS = config.getInt("rudder.batch.storeAgentRunTimes.maxDays") // In days : 0
  val RUDDER_REPORTS_EXECUTION_MAX_MINUTES = { // Tis is handled at the object creation, days and minutes = 0 => 30 minutes
    try {
      config.getInt("rudder.batch.storeAgentRunTimes.maxMinutes")
    } catch {
      case ex: ConfigException =>
        ApplicationLogger.info("Property 'rudder.batch.storeAgentRunTimes.maxMinutes' is missing or empty in rudder.configFile. Default to 0 minutes.")
        0
    }
  }
  val RUDDER_REPORTS_EXECUTION_MAX_SIZE = { // In minutes: 5
    try {
      config.getInt("rudder.batch.storeAgentRunTimes.maxBatchSize")
    } catch {
      case ex: ConfigException =>
        ApplicationLogger.info("Property 'rudder.batch.storeAgentRunTimes.maxBatchSize' is missing or empty in rudder.configFile. Default to 5 minutes.")
        5
    }
  }

  val RUDDER_REPORTS_EXECUTION_INTERVAL = config.getInt("rudder.batch.storeAgentRunTimes.updateInterval") // In seconds : 5

  val HISTORY_INVENTORIES_ROOTDIR = config.getString("history.inventories.rootdir")

  val RUDDER_DEBUG_NODE_CONFIGURATION_PATH = config.getString("rudder.debug.nodeconfiguration.path")

  val RUDDER_BATCH_PURGE_DELETED_INVENTORIES = {
    try {
      config.getInt("rudder.batch.purge.inventories.delete.TTL")
    } catch {
      case ex: ConfigException =>
        ApplicationLogger.info("Property 'rudder.batch.purge.inventories.delete.TTL' is missing or empty in rudder.configFile. Default to 7 days.")
        7
    }
  }

  val RUDDER_BCRYPT_COST = {
    try {
      config.getInt("rudder.bcrypt.cost")
    } catch {
      case ex: ConfigException =>
        ApplicationLogger.debug("Property 'rudder.bcrypt.cost' is missing or empty in rudder.configFile. Default cost to 12.")
        12
    }
  }

  val RUDDER_BATCH_PURGE_DELETED_INVENTORIES_INTERVAL = {
    try {
      config.getInt("rudder.batch.purge.inventories.delete.interval")
    } catch {
      case ex: ConfigException =>
        ApplicationLogger.info("Property 'rudder.batch.purge.inventories.delete.interval' is missing or empty in rudder.configFile. Default to 24 hours.")
        24
    }
  }

  val RUDDER_BATCH_DELETE_SOFTWARE_INTERVAL = {
    try {
      config.getInt("rudder.batch.delete.software.interval")
    } catch {
      case ex: ConfigException =>
        ApplicationLogger.info("Property 'rudder.batch.delete.software.interval' is missing or empty in rudder.configFile. Default to 24 hours.")
        24
    }
  }

  val RUDDER_BATCH_CHECK_NODE_CACHE_INTERVAL = {
    try {
      Duration.fromScala(scala.concurrent.duration.Duration(config.getString("rudder.batch.check.node.cache.interval")))
    } catch {
      case ex: Exception =>
        ApplicationLogger.info("Property 'rudder.batch.check.node.cache.interval' is missing or empty in rudder.configFile. Default to '15 s'.")
        Duration(15, TimeUnit.SECONDS)
    }
  }
  val RUDDER_GROUP_OWNER_CONFIG_REPO = {
    try {
      config.getString("rudder.config.repo.new.file.group.owner")
    } catch {
      case ex: Exception =>
        ApplicationLogger.info("Property 'rudder.config.repo.new.file.group.owner' is missing or empty in rudder.configFile. Default to 'rudder'.")
        "rudder"
    }
  }
  val RUDDER_GROUP_OWNER_GENERATED_POLICIES = {
    try {
      config.getString("rudder.generated.policies.group.owner")
    } catch {
      case ex: Exception =>
        ApplicationLogger.info("Property 'rudder.generated.policies.group.owner' is missing or empty in rudder.configFile. Default to 'rudder-policy-reader'.")
        "rudder-policy-reader"
    }
  }

  val RUDDER_RELAY_API = config.getString("rudder.server.relay.api")

  val RUDDER_RELAY_RELOAD = {
    try {
      config.getString("rudder.relayd.reload")
    } catch {
      // by default, if property is missing
      case ex:ConfigException => "/opt/rudder/bin/rudder relay reload -p"
    }
  }

  // The base directory for hooks. I'm not sure it needs to be configurable
  // as we only use it in generation.
  val HOOKS_D = "/opt/rudder/etc/hooks.d"
  val UPDATED_NODE_IDS_PATH = "/var/rudder/policy-generation-info/last-updated-nodeids"
  val GENERATION_FAILURE_MSG_PATH = "/var/rudder/policy-generation-info/last-failure-message"
  /*
   * This is a parameter for compatibility mode for Rudder 5.0.
   * It should be removed in 5.1 and up.
   */
  val UPDATED_NODE_IDS_COMPABILITY = {
    try {
      Some(config.getBoolean("rudder.hooks.policy-generation-finished.nodeids.compability"))
    } catch {
      case ex:ConfigException => None
    }
  }


  val HOOKS_IGNORE_SUFFIXES = RudderProperties.splitProperty(config.getString("rudder.hooks.ignore-suffixes"))

  val logentries = "logentries.xml"
  val prettyPrinter = new RudderPrettyPrinter(Int.MaxValue, 2)
  val userLibraryDirectoryName = "directives"
  val groupLibraryDirectoryName = "groups"
  val rulesDirectoryName = "rules"
  val ruleCategoriesDirectoryName = "ruleCategories"
  val parametersDirectoryName = "parameters"

  // properties from version.properties file,
  val (
      rudderMajorVersion
    , rudderFullVersion
    , currentYear
    , builtTimestamp
  ) = {
    val p = new java.util.Properties
    p.load(this.getClass.getClassLoader.getResourceAsStream("version.properties"))
    (
      p.getProperty("rudder-major-version")
    , p.getProperty("rudder-full-version")
    , p.getProperty("current-year")
    , p.getProperty("build-timestamp")
    )
  }

  val LDIF_TRACELOG_ROOT_DIR = try {
    config.getString("ldif.tracelog.rootdir")
  } catch {
    case ex:ConfigException => "/var/rudder/inventories/debug"
  }

  // the number of inventories parsed and saved in parallel.
  // That number should be small, LDAP doesn't like lots of write
  // Minimum 1, 1x mean "0.5x number of cores"
  val MAX_PARSE_PARALLEL = try {
    config.getString("inventory.parse.parallelization")
  } catch {
    case ex: ConfigException => "2"
  }

  val INVENTORY_ROOT_DIR = try {
    config.getString("inventories.root.directory")
  } catch {
    case ex: ConfigException => "/var/rudder/inventories"
  }

  val WATCHER_ENABLE = try {
    config.getBoolean("inventories.watcher.enable")
  } catch {
    case ex: ConfigException => true
  }

  val WATCHER_GARBAGE_OLD_INVENTORIES_PERIOD = try {
    Duration.fromScala(scala.concurrent.duration.Duration.apply(config.getString("inventories.watcher.period.garbage.old")))
  } catch {
    case ex: Exception => 5.minutes
  }

  val WATCHER_DELETE_OLD_INVENTORIES_AGE = try {
    Duration.fromScala(scala.concurrent.duration.Duration.apply(config.getString("inventories.watcher.max.age.before.deletion")))
  } catch {
    case _: Exception => 3.days
  }

  val METRICS_NODES_DIRECTORY_GIT_ROOT = "/var/rudder/metrics/nodes"

  val METRICS_NODES_MIN_PERIOD = try {
    Duration.fromScala(scala.concurrent.duration.Duration(config.getString("metrics.node.scheduler.period.min")))
  } catch {
    case ex: ConfigException => // default
      15.minutes
    case ex: NumberFormatException =>
      ApplicationLogger.error(s"Error when reading key: 'metrics.node.scheduler.period.min', defaulting to 15min: ${ex.getMessage}")
      15.minutes
  }
  val METRICS_NODES_MAX_PERIOD = try {
    Duration.fromScala(scala.concurrent.duration.Duration(config.getString("metrics.node.scheduler.period.max")))
  } catch {
    case ex: ConfigException => // default
      4.hours
    case ex: NumberFormatException =>
      ApplicationLogger.error(s"Error when reading key: 'metrics.node.scheduler.period.max', defaulting to 4h: ${ex.getMessage}")
      4.hours
  }
  if(METRICS_NODES_MAX_PERIOD <= METRICS_NODES_MIN_PERIOD) {
    throw new IllegalArgumentException(s"Value for 'metrics.node.scheduler.period.max' (${METRICS_NODES_MAX_PERIOD.render}) must " +
                                       s"be bigger than for 'metrics.node.scheduler.period.max' (${METRICS_NODES_MIN_PERIOD.render})")
  }

  val RUDDER_HEALTHCHECK_PERIOD = try {
    Duration.fromScala(scala.concurrent.duration.Duration(config.getString("metrics.healthcheck.scheduler.period")))
  } catch {
    case ex: ConfigException =>
      ApplicationLogger.info("Property 'metrics.healthcheck.scheduler.period' is missing or empty in rudder.configFile. Default to 6 hours.")
      6.hours
    case ex: NumberFormatException =>
      ApplicationLogger.error(s"Error when reading key: 'metrics.node.scheduler.period.max', defaulting to 6 hours: ${ex.getMessage}")
      6.hours
  }

  val RUDDER_LANG_EXEC_TEST_LOOP = {
    try {
      config.getBoolean("rudder.lang.test-loop.exec")
    } catch {
      case ex: ConfigException => true
    }
  }

  val RUDDER_DEFAULT_DELETE_NODE_MODE = {
    val default = DeleteMode.MoveToRemoved
    val mode = try {
      config.getString("rudder.nodes.delete.defaultMode")
    } catch {
      case ex: ConfigException => default.name
    }
    val cfg = DeleteMode.all.find(_.name == mode).getOrElse(default)
    ApplicationLogger.info(s"Using '${cfg.name}' behavior when a node is deleted")
    cfg
  }


  ApplicationLogger.info(s"Starting Rudder ${rudderFullVersion} web application [build timestamp: ${builtTimestamp}]")

  //
  // Theses services can be called from the outer world
  // They must be typed with there abstract interface, as
  // such service must not expose implementation details
  //

  // we need that to be the first thing, and take care of Exception so that the error is
  // human undertandable when the directory is not up


  val roLDAPConnectionProvider: LDAPConnectionProvider[RoLDAPConnection] = roLdap
  // test connection is up and try to make an human understandable error message.
  ApplicationLogger.debug(s"Test if LDAP connection is active")
  // it seems that we need to call `ZioRuntime.internal.unsafeRun` in place of `.runNow` to avoir a deadlock (not clear)
  ZioRuntime.internal.unsafeRun((for {
    con    <- roLdap
  } yield {
    con.backed.getConnectionName
  }).flip.option).foreach(err =>
    throw new InitError("An error occured when testing for LDAP connection, please check it: " + err.fullMsg)
  )

  val pendingNodesDit: InventoryDit = pendingNodesDitImpl
  val acceptedNodesDit: InventoryDit = acceptedNodesDitImpl
  val nodeDit: NodeDit = nodeDitImpl
  val rudderDit: RudderDit = rudderDitImpl
  val roRuleRepository: RoRuleRepository = roLdapRuleRepository
  val woRuleRepository: WoRuleRepository = woLdapRuleRepository
  val woNodeRepository: WoNodeRepository = woLdapNodeRepository
  val roNodeGroupRepository: RoNodeGroupRepository = roLdapNodeGroupRepository
  val woNodeGroupRepository: WoNodeGroupRepository = woLdapNodeGroupRepository
  val techniqueRepository: TechniqueRepository = techniqueRepositoryImpl
  val updateTechniqueLibrary: UpdateTechniqueLibrary = techniqueRepositoryImpl
  val roDirectiveRepository: RoDirectiveRepository = roLdapDirectiveRepository
  val woDirectiveRepository: WoDirectiveRepository = woLdapDirectiveRepository
  val readOnlySoftwareDAO: ReadOnlySoftwareDAO = softwareInventoryDAO
  val eventLogRepository: EventLogRepository = logRepository
  val eventLogDetailsService: EventLogDetailsService = eventLogDetailsServiceImpl
  val reportingService: ReportingService = reportingServiceImpl
  lazy val asyncComplianceService : AsyncComplianceService = new AsyncComplianceService(reportingService)
  val debugScript : DebugInfoService = scriptLauncher
  val stringUuidGenerator: StringUuidGenerator = uuidGen
  val cmdbQueryParser: CmdbQueryParser = queryParser
  val inventoryHistoryLogRepository: InventoryHistoryLogRepository = diffRepos
  val inventoryEventLogService: InventoryEventLogService = inventoryLogEventServiceImpl
  val ruleApplicationStatus: RuleApplicationStatusService = ruleApplicationStatusImpl
  val propertyEngineService: PropertyEngineService = propertyEngineServiceImpl
  val newNodeManager: NewNodeManager = newNodeManagerImpl
  val nodeGrid: NodeGrid = nodeGridImpl
  val nodeSummaryService: NodeSummaryService = nodeSummaryServiceImpl
  val jsTreeUtilService: JsTreeUtilService = jsTreeUtilServiceImpl
  val directiveEditorService: DirectiveEditorService = directiveEditorServiceImpl
  val userPropertyService: UserPropertyService = userPropertyServiceImpl
  val eventListDisplayer: EventListDisplayer = eventListDisplayerImpl
  lazy val asyncDeploymentAgent: AsyncDeploymentActor = asyncDeploymentAgentImpl
  val policyServerManagementService: PolicyServerManagementService = psMngtService
  //val updateDynamicGroupsService : DynGroupUpdaterService = dynGroupUpdaterService
  val updateDynamicGroups: UpdateDynamicGroups = dyngroupUpdaterBatch
  val checkInventoryUpdate = new CheckInventoryUpdate(nodeInfoServiceImpl, asyncDeploymentAgent, stringUuidGenerator, RUDDER_BATCH_CHECK_NODE_CACHE_INTERVAL)
  val purgeDeletedInventories = new PurgeDeletedInventories(removeNodeServiceImpl, FiniteDuration(RUDDER_BATCH_PURGE_DELETED_INVENTORIES_INTERVAL.toLong, "hours"), RUDDER_BATCH_PURGE_DELETED_INVENTORIES)
  val purgeUnreferencedSoftwares = new PurgeUnreferencedSoftwares(softwareService, FiniteDuration(RUDDER_BATCH_DELETE_SOFTWARE_INTERVAL.toLong, "hours"))
  val databaseManager: DatabaseManager = databaseManagerImpl
  val automaticReportsCleaning: AutomaticReportsCleaning = dbCleaner
  val checkTechniqueLibrary: CheckTechniqueLibrary = techniqueLibraryUpdater
  val automaticReportLogger: AutomaticReportLogger = autoReportLogger
  val removeNodeService: RemoveNodeService = removeNodeServiceImpl
  val nodeInfoService: NodeInfoService = nodeInfoServiceImpl
  val reportDisplayer: ReportDisplayer = reportDisplayerImpl
  lazy val dependencyAndDeletionService: DependencyAndDeletionService =  dependencyAndDeletionServiceImpl
  val itemArchiveManager: ItemArchiveManager = itemArchiveManagerImpl
  val personIdentService: PersonIdentService = personIdentServiceImpl
  lazy val gitRevisionProvider: GitRevisionProvider = gitRevisionProviderImpl
  val logDisplayer: LogDisplayer  = logDisplayerImpl
  val fullInventoryRepository: LDAPFullInventoryRepository = ldapFullInventoryRepository
  val acceptedNodeQueryProcessor: QueryProcessor = queryProcessor
  val categoryHierarchyDisplayer: CategoryHierarchyDisplayer = categoryHierarchyDisplayerImpl
  val dynGroupService: DynGroupService = dynGroupServiceImpl
  val ditQueryData: DitQueryData = ditQueryDataImpl
  val reportsRepository : ReportsRepository = reportsRepositoryImpl
  val eventLogDeploymentService: EventLogDeploymentService = eventLogDeploymentServiceImpl
  lazy val srvGrid = new SrvGrid(roAgentRunsRepository, configService, roLdapRuleRepository, nodeInfoService)
  val findExpectedReportRepository : FindExpectedReportRepository = findExpectedRepo
  val roApiAccountRepository : RoApiAccountRepository = roLDAPApiAccountRepository
  val woApiAccountRepository : WoApiAccountRepository = woLDAPApiAccountRepository

  lazy val roAgentRunsRepository : RoReportsExecutionRepository = cachedAgentRunRepository

  lazy val writeAllAgentSpecificFiles = new WriteAllAgentSpecificFiles(agentRegister)

  //all cache that need to be cleared are stored here
  lazy val clearableCache: Seq[CachedRepository] = Seq(
      cachedAgentRunRepository
    , recentChangesService
    , reportingServiceImpl
    , nodeInfoServiceImpl
  )

  val ldapInventoryMapper = inventoryMapper

  val pluginSettingsService = new FilePluginSettingsService(root / "opt" / "rudder" / "etc" / "rudder-pkg" / "rudder-pkg.conf" )
  ////////////////////////////////////////////////
  ////////// plugable service providers //////////
  ////////////////////////////////////////////////

  /*
   * Plugable service:
   * - Rudder Agent (agent type, agent os)
   * - API ACL
   * - Change Validation workflow
   * - User authentication backends
   * - User authorization capabilities
   */
  // Plugable agent register
  lazy val agentRegister = new AgentRegister()

  // Plugin input interface to
  lazy val apiAuthorizationLevelService = new DefaultApiAuthorizationLevel(LiftApiProcessingLogger)

  // Plugin input interface for alternative workflow
  lazy val workflowLevelService = new DefaultWorkflowLevel(new NoWorkflowServiceImpl(
      commitAndDeployChangeRequest
  ))

  // Plugin input interface for alternative authentication providers
  lazy val authenticationProviders = new AuthBackendProvidersManager()

  // Plugin input interface for user management plugin
  lazy val userAuthorisationLevel = new DefaultUserAuthorisationLevel()

  // Plugin input interface for Authorization for API
  lazy val authorizationApiMapping = new ExtensibleAuthorizationApiMapping(AuthorizationApiMapping.Core :: Nil)

  ////////// end plugable service providers //////////

  lazy val roleApiMapping = new RoleApiMapping(authorizationApiMapping)

  lazy val rudderUsernameCaseSensitive: Boolean = {
    (for {
      resource <- UserFileProcessing.getUserResourceFile()
      test <- UserFileProcessing.parseCaseSensitivityOpt(resource)
    } yield {
      test
    }) match {
      case Right(resource) => resource
      case Left(UserConfigFileError(msg, exception)) =>
        ApplicationLogger.error(msg, Box(exception))
        //make the application not available
        throw new javax.servlet.UnavailableException(s"Error when triyng to parse Rudder users file, aborting.")
    }
  }

  // rudder user list
  lazy val rudderUserListProvider : FileUserDetailListProvider = {
    (for {
      resource <- UserFileProcessing.getUserResourceFile()
    } yield {
      resource
    }) match {
        case Right(resource) =>
          new FileUserDetailListProvider(roleApiMapping, userAuthorisationLevel, resource)
        case Left(UserConfigFileError(msg, exception)) =>
          ApplicationLogger.error(msg, Box(exception))
          //make the application not available
          throw new javax.servlet.UnavailableException(s"Error when triyng to parse Rudder users file, aborting.")
      }
  }


  val roRuleCategoryRepository : RoRuleCategoryRepository = roLDAPRuleCategoryRepository
  val ruleCategoryService      : RuleCategoryService = new RuleCategoryService()
  val woRuleCategoryRepository : WoRuleCategoryRepository = woLDAPRuleCategoryRepository

  val changeRequestEventLogService : ChangeRequestEventLogService = new ChangeRequestEventLogServiceImpl(eventLogRepository)
  val secretEventLogService : SecretEventLogService = new SecretEventLogServiceImpl(eventLogRepository)

  lazy val xmlSerializer = XmlSerializerImpl(
      ruleSerialisation
    , directiveSerialisation
    , nodeGroupSerialisation
    , globalParameterSerialisation
    , ruleCategorySerialisation
  )

  lazy val xmlUnserializer = XmlUnserializerImpl(
      ruleUnserialisation
    , directiveUnserialisation
    , nodeGroupUnserialisation
    , globalParameterUnserialisation
    , ruleCategoryUnserialisation
  )
  val workflowEventLogService = new WorkflowEventLogServiceImpl(eventLogRepository,uuidGen)
  val diffService: DiffService = new DiffServiceImpl()
  lazy val diffDisplayer = new DiffDisplayer(linkUtil)
  lazy val commitAndDeployChangeRequest : CommitAndDeployChangeRequestService =
    new CommitAndDeployChangeRequestServiceImpl(
        uuidGen
      , roDirectiveRepository
      , woDirectiveRepository
      , roNodeGroupRepository
      , woNodeGroupRepository
      , roRuleRepository
      , woRuleRepository
      , roLDAPParameterRepository
      , woLDAPParameterRepository
      , asyncDeploymentAgent
      , dependencyAndDeletionService
      , configService.rudder_workflow_enabled _
      , xmlSerializer
      , xmlUnserializer
      , sectionSpecParser
      , dynGroupUpdaterService
    )

  val roParameterService : RoParameterService = roParameterServiceImpl
  val woParameterService : WoParameterService = woParameterServiceImpl


  //////////////////////////////////////////////////////////////////////////////////////////
  ///////////////////////////////////////// REST ///////////////////////////////////////////
  //////////////////////////////////////////////////////////////////////////////////////////

  val restExtractorService =
    RestExtractorService (
        roRuleRepository
      , roDirectiveRepository
      , roNodeGroupRepository
      , techniqueRepository
      , queryParser
      , userPropertyService
      , workflowLevelService
      , stringUuidGenerator
      , typeParameterService
    )

  val zioJsonExtractor = new ZioJsonExtractor(queryParser)

  val tokenGenerator = new TokenGeneratorImpl(32)

  implicit val userService = new UserService {
    def getCurrentUser = CurrentUser
  }

  val ncfTechniqueReader : ncf.TechniqueReader = new ncf.TechniqueReader(
    restExtractorService
    , stringUuidGenerator
    , personIdentService
    , gitConfigRepo
    , prettyPrinter
    , gitModificationRepository
    , RUDDER_CHARSET.name
    , RUDDER_GROUP_OWNER_CONFIG_REPO
  )

  val techniqueSerializer = new TechniqueSerializer(typeParameterService)


  lazy val linkUtil = new LinkUtil(roRuleRepository, roNodeGroupRepository, roDirectiveRepository, nodeInfoServiceImpl)
  // REST API
  val restApiAccounts       = new RestApiAccounts(roApiAccountRepository,woApiAccountRepository,restExtractorService,tokenGenerator, uuidGen, userService, apiAuthorizationLevelService)
  val restDataSerializer    = RestDataSerializerImpl(techniqueRepository,diffService)
  val restQuicksearch       = new RestQuicksearch(new FullQuickSearchService()(roLDAPConnectionProvider, nodeDit, acceptedNodesDit, rudderDit, roDirectiveRepository, nodeInfoService), userService, linkUtil)
  val restCompletion        = new RestCompletion(new RestCompletionService(roDirectiveRepository, roRuleRepository))

  val ruleApiService2 =
    new RuleApiService2(
        roRuleRepository
      , woRuleRepository
      , uuidGen
      , asyncDeploymentAgent
      , workflowLevelService
      , restExtractorService
      , restDataSerializer
    )

  val ruleApiService6 =
    new RuleApiService6 (
        roRuleCategoryRepository
      , roRuleRepository
      , woRuleCategoryRepository
      , restDataSerializer
    )
  val ruleApiService13 =
    new RuleApiService14 (
        roRuleRepository
      , woRuleRepository
      , configurationRepository
      , uuidGen
      , asyncDeploymentAgent
      , workflowLevelService
      , roRuleCategoryRepository
      , woRuleCategoryRepository
      , roDirectiveRepository
      , roNodeGroupRepository
      , nodeInfoService
      , configService.rudder_global_policy_mode _
      , ruleApplicationStatus
    )

  val directiveApiService2 =
    new DirectiveApiService2 (
        roDirectiveRepository
      , woDirectiveRepository
      , uuidGen
      , asyncDeploymentAgent
      , workflowLevelService
      , restExtractorService
      , directiveEditorService
      , restDataSerializer
      , techniqueRepositoryImpl
    )

  val directiveApiService14 =
    new DirectiveApiService14 (
        roDirectiveRepository
      , configurationRepository
      , woDirectiveRepository
      , uuidGen
      , asyncDeploymentAgent
      , workflowLevelService
      , directiveEditorService
      , restDataSerializer
      , techniqueRepositoryImpl
    )

  val techniqueApiService6 =
    new TechniqueAPIService6 (
        roDirectiveRepository
      , restDataSerializer
    )

  val techniqueApiService14 =
    new TechniqueAPIService14 (
        roDirectiveRepository
      , gitParseTechniqueLibrary
      , ncfTechniqueReader
      , techniqueSerializer
      , restDataSerializer
    )

  val groupApiService2 =
    new GroupApiService2 (
        roNodeGroupRepository
      , woNodeGroupRepository
      , uuidGen
      , asyncDeploymentAgent
      , workflowLevelService
      , restExtractorService
      , queryProcessor
      , restDataSerializer
    )

  val groupApiService6 =
    new GroupApiService6 (
      roNodeGroupRepository
    , woNodeGroupRepository
    , restDataSerializer
  )

  val groupApiService14 =
    new GroupApiService14 (
        roNodeGroupRepository
      , woNodeGroupRepository
      , roLDAPParameterRepository
      , uuidGen
      , asyncDeploymentAgent
      , workflowLevelService
      , restExtractorService
      , queryParser
      , queryProcessor
      , restDataSerializer
    )

  val nodeApiService2 = new NodeApiService2 (
      newNodeManager
    , nodeInfoService
    , removeNodeService
    , uuidGen
    , restExtractorService
    , restDataSerializer
  )

  val nodeApiService4 = new NodeApiService4 (
      fullInventoryRepository
    , nodeInfoService
    , softwareInventoryDAO
    , uuidGen
    , restExtractorService
    , restDataSerializer
    , roAgentRunsRepository
  )

  val nodeApiService8 = {
    new NodeApiService8(
        woNodeRepository
      , nodeInfoService
      , uuidGen
      , asyncDeploymentAgent
      , RUDDER_RELAY_API
      , userService
    )
  }

  val nodeApiService12 = new NodeApiService12(
      removeNodeService
    , uuidGen
    , restDataSerializer
  )

  val nodeApiService6 = new NodeApiService6(
      nodeInfoService
    , fullInventoryRepository
    , softwareInventoryDAO
    , restExtractorService
    , restDataSerializer
    , queryProcessor
    , inventoryQueryChecker
    , roAgentRunsRepository
  )

  val nodeApiService13 = new NodeApiService13 (
      nodeInfoService, cachedAgentRunRepository, readOnlySoftwareDAO, restExtractorService
    , () => configService.rudder_global_policy_mode().toBox, reportingServiceImpl, roNodeGroupRepository, roLDAPParameterRepository
  )

  val nodeApiService16 = new NodeApiService15(
      fullInventoryRepository
    , rwLdap
    , ldapEntityMapper
    , newNodeManager
    , stringUuidGenerator
    , nodeDit
    , pendingNodesDit
    , acceptedNodesDit
  )

  val parameterApiService2 =
    new ParameterApiService2 (
        roLDAPParameterRepository
      , woLDAPParameterRepository
      , uuidGen
      , workflowLevelService
      , restExtractorService
      , restDataSerializer
    )
  val parameterApiService14 =
    new ParameterApiService14 (
        roLDAPParameterRepository
      , woLDAPParameterRepository
      , uuidGen
      , workflowLevelService
    )

  // System API

  val clearCacheService = new ClearCacheServiceImpl(
      nodeConfigurationHashRepo
    , asyncDeploymentAgent
    , eventLogRepository
    , uuidGen
    , clearableCache
  )

  val hookApiService = new HookApiService(HOOKS_D, HOOKS_IGNORE_SUFFIXES)

  val systemApiService11 = new SystemApiService11(
      updateTechniqueLibrary
    , debugScript
    , clearCacheService
    , asyncDeploymentAgent
    , uuidGen
    , updateDynamicGroups
    , itemArchiveManager
    , personIdentService
    , gitConfigRepo
  )

  val systemApiService13 = new SystemApiService13(
      healthcheckService
    , healthcheckNotificationService
    , restDataSerializer
    , softwareService
  )

  val ruleInternalApiService =  new RuleInternalApiService (
      roRuleRepository
    , roNodeGroupRepository
    , nodeInfoService)

  private[this] val complianceAPIService = new ComplianceAPIService(
          roRuleRepository
        , nodeInfoService
        , roNodeGroupRepository
        , reportingService
        , roDirectiveRepository
        , () => globalComplianceModeService.getGlobalComplianceMode
      )

  val techniqueArchiver = new TechniqueArchiverImpl(gitConfigRepo, prettyPrinter, gitModificationRepository, personIdentService, RUDDER_GROUP_OWNER_CONFIG_REPO)
  val techniqueCompiler = new RudderCRunner("/opt/rudder/etc/rudderc.conf","/opt/rudder/bin/rudderc",RUDDER_GIT_ROOT_CONFIG_REPO)
  val ncfTechniqueWriter = new TechniqueWriter(
      techniqueArchiver
    , updateTechniqueLibrary
    , interpolationCompiler
    , roDirectiveRepository
    , woDirectiveRepository
    , techniqueRepository
    , workflowLevelService
    , prettyPrinter
    , RUDDER_GIT_ROOT_CONFIG_REPO
    , typeParameterService
    , techniqueSerializer
    , techniqueCompiler
    , "/var/log/rudder"
    , configService.rudder_generation_rudderc_enabled_targets()
  )

  lazy val pipelinedInventoryParser: InventoryParser = {
    val fusionReportParser = {
      new FusionInventoryParser(
          uuidGen
        , rootParsingExtensions    = Nil
        , contentParsingExtensions = Nil
      )
    }

    new DefaultInventoryParser(
     fusionReportParser,
     Seq(
         new PreInventoryParserCheckConsistency
     )
    )
  }

  lazy val automaticMerger: PreCommit = new UuidMergerPreCommit(
      uuidGen
    , acceptedNodesDit
    , new NodeInventoryDNFinderService(Seq(
        //start by trying to use an already given UUID
        NamedNodeInventoryDNFinderAction("use_existing_id", new UseExistingNodeIdFinder(inventoryDitService,roLdap,acceptedNodesDit.BASE_DN.getParent))
    ))
    , new MachineDNFinderService(Seq(
        //start by trying to use an already given UUID
        NamedMachineDNFinderAction("use_existing_id", new UseExistingMachineIdFinder(inventoryDitService,roLdap,acceptedNodesDit.BASE_DN.getParent))
        //look if it's in the accepted inventories
      , NamedMachineDNFinderAction("check_mother_board_uuid_accepted", new FromMotherBoardUuidIdFinder(roLdap,acceptedNodesDit,inventoryDitService))
        //see if it's in the "pending" branch
      , NamedMachineDNFinderAction("check_mother_board_uuid_pending", new FromMotherBoardUuidIdFinder(roLdap,pendingNodesDit,inventoryDitService))
        //see if it's in the "removed" branch
      , NamedMachineDNFinderAction("check_mother_board_uuid_removed", new FromMotherBoardUuidIdFinder(roLdap,removedNodesDitImpl,inventoryDitService))
    ))
    , new NameAndVersionIdFinder(
      "check_name_and_version"
    , roLdap
    , inventoryMapper
    , acceptedNodesDit
  )
  )

  lazy val gitFactRepo = GitRepositoryProviderImpl.make(RUDDER_GIT_ROOT_FACT_REPO).runNow
  private[this] lazy val gitFactRepoGC = new GitGC(gitFactRepo, RUDDER_GIT_GC)
  gitFactRepoGC.start()
  lazy val factRepo = new GitNodeFactRepository(gitFactRepo, RUDDER_GROUP_OWNER_CONFIG_REPO)
  factRepo.checkInit().runNow

  lazy val ldifInventoryLogger = new DefaultLDIFInventoryLogger(LDIF_TRACELOG_ROOT_DIR)
  lazy val inventorySaver      = new DefaultInventorySaver(
      rwLdap
    , acceptedNodesDit
    , inventoryMapper
    , (
         CheckOsType
      :: automaticMerger
      :: CheckMachineName
      :: new LastInventoryDate()
      :: AddIpValues
      :: new LogInventoryPreCommit(inventoryMapper,ldifInventoryLogger)
      :: Nil
      )
    , (
         new PendingNodeIfNodeWasRemoved(fullInventoryRepository)
      :: new FactRepositoryPostCommit(factRepo, nodeInfoService)
      :: new PostCommitLogger(ldifInventoryLogger)
      :: new PostCommitInventoryHooks(HOOKS_D, HOOKS_IGNORE_SUFFIXES)
      :: Nil
      )
  )

  lazy val inventoryProcessorInternal = {
    val checkLdapAlive: () => IOResult[Unit] = {
      () =>
      for {
        con <- rwLdap
        res <- con.get(pendingNodesDit.NODES.dn, "1.1")
      } yield {
        ()
      }
    }
    val maxParallel = try {
      val user = if(MAX_PARSE_PARALLEL.endsWith("x")) {
        val xx = MAX_PARSE_PARALLEL.substring(0, MAX_PARSE_PARALLEL.size-1)
        java.lang.Double.parseDouble(xx) * Runtime.getRuntime.availableProcessors()
      } else {
        java.lang.Double.parseDouble(MAX_PARSE_PARALLEL)
      }
      Math.max(1, user).toLong
    } catch {
      case ex: Exception =>
        // logs are not available here
        println(s"ERROR Error when parsing configuration properties for the parallelization of inventory processing. " +
                s"Expecting a positive integer or number of time the available processors. Default to '0.5x': " +
                s"inventory.parse.parallelization=${MAX_PARSE_PARALLEL}")
        Math.max(1, Math.ceil(Runtime.getRuntime.availableProcessors().toDouble/2).toLong)
    }
    new InventoryProcessor(
        pipelinedInventoryParser
      , inventorySaver
      , maxParallel
      , fullInventoryRepository
      , new InventoryDigestServiceV1(fullInventoryRepository)
      , checkLdapAlive
      , pendingNodesDit
    )
  }
  lazy val inventoryProcessor = {
    val mover = new InventoryMover(
        INVENTORY_ROOT_DIR + "/received"
      , INVENTORY_ROOT_DIR + "/failed"
      , new InventoryFailedHook(
          HOOKS_D
        , HOOKS_IGNORE_SUFFIXES
      )
    )
    new DefaultProcessInventoryService(inventoryProcessorInternal, mover)
  }
  val INVENTORY_INCOMING_DIR = INVENTORY_ROOT_DIR + "/incoming"
  lazy val inventoryWatcher = {
    val fileProcessor = new ProcessFile(inventoryProcessor, INVENTORY_INCOMING_DIR)

    new InventoryFileWatcher(
        fileProcessor
      , INVENTORY_INCOMING_DIR
      , INVENTORY_ROOT_DIR + "/accepted-nodes-updates"
      , WATCHER_DELETE_OLD_INVENTORIES_AGE
      , WATCHER_GARBAGE_OLD_INVENTORIES_PERIOD
    )
  }

  /*
   * API versions are incremented each time incompatible changes are made (like adding or deleting endpoints - modification
   * of an existing endpoint, if done in a purely compatible way, don't change api version).
   * It may happen that some rudder branches don't have a version bump, and other have several (in case of
   * horrible breaking bugs). We avoid the case where a previous release need a version bump.
   * For ex:
   * - 7.0: 14
   * - 7.1: 14 (no change)
   * - 7.2[.0~.4]: 15
   * - 7.2.5: 16
   */
  val ApiVersions =
    ApiVersion(12 , true) :: // rudder 6.0, 6.1
    ApiVersion(13 , true) :: // rudder 6.2
    ApiVersion(14 , false) :: // rudder 7.0
    ApiVersion(15 , false) :: // rudder 7.2
    Nil

  val jsonPluginDefinition = new ReadPluginPackageInfo("/var/rudder/packages/index.json")

  val resourceFileService = new ResourceFileService(gitConfigRepo)
  lazy val apiDispatcher = new RudderEndpointDispatcher(LiftApiProcessingLogger)
  lazy val rudderApi = {
    import com.normation.rudder.rest.lift._

    val nodeInheritedProperties = new NodeApiInheritedProperties(nodeInfoService, roNodeGroupRepository, roLDAPParameterRepository)
    val groupInheritedProperties = new GroupApiInheritedProperties(roNodeGroupRepository, roLDAPParameterRepository)

    val modules = List(
        new ComplianceApi(restExtractorService, complianceAPIService)
      , new GroupsApi(roLdapNodeGroupRepository, restExtractorService, zioJsonExtractor, stringUuidGenerator, groupApiService2, groupApiService6, groupApiService14, groupInheritedProperties)
      , new DirectiveApi(roDirectiveRepository, restExtractorService, zioJsonExtractor, stringUuidGenerator, directiveApiService2, directiveApiService14)
      , new NodeApi(restExtractorService, restDataSerializer, nodeApiService2, nodeApiService4, nodeApiService6, nodeApiService8, nodeApiService12, nodeApiService13, nodeApiService16, nodeInheritedProperties, RUDDER_DEFAULT_DELETE_NODE_MODE)
      , new ParameterApi(restExtractorService, zioJsonExtractor, parameterApiService2, parameterApiService14)
      , new SettingsApi(restExtractorService, configService, asyncDeploymentAgent, stringUuidGenerator, policyServerManagementService, nodeInfoService)
      , new TechniqueApi(restExtractorService, techniqueApiService6, techniqueApiService14, ncfTechniqueWriter, ncfTechniqueReader, techniqueRepository, techniqueSerializer, stringUuidGenerator, resourceFileService)
      , new RuleApi(restExtractorService, zioJsonExtractor, ruleApiService2, ruleApiService6, ruleApiService13, stringUuidGenerator)
      , new SystemApi(restExtractorService,systemApiService11, systemApiService13, rudderMajorVersion, rudderFullVersion, builtTimestamp)
      , new InventoryApi(restExtractorService, inventoryWatcher, better.files.File(INVENTORY_INCOMING_DIR))
      , new PluginApi(restExtractorService, pluginSettingsService)
      , new RecentChangesAPI(recentChangesService, restExtractorService)
      , new RulesInternalApi(restExtractorService, ruleInternalApiService)
      , new HookApi(hookApiService)
      // info api must be resolved latter, because else it misses plugin apis !
    )

    val api = new LiftHandler(apiDispatcher, ApiVersions, new AclApiAuthorization(LiftApiProcessingLogger, userService, () => apiAuthorizationLevelService.aclEnabled), None)
    modules.foreach { module =>
      api.addModules(module.getLiftEndpoints())
    }
    api
  }


  // Internal APIs
  val sharedFileApi = new SharedFilesAPI(restExtractorService,RUDDER_DIR_SHARED_FILES_FOLDER)
  val eventLogApi= new EventLogAPI(eventLogRepository, restExtractorService, eventLogDetailsGenerator, personIdentService)
  lazy val asyncWorkflowInfo = new AsyncWorkflowInfo
  lazy val configService: ReadConfigService with UpdateConfigService = {
    new GenericConfigService(
        config
      , new LdapConfigRepository(rudderDit, rwLdap, ldapEntityMapper, eventLogRepository, stringUuidGenerator)
      , asyncWorkflowInfo
      , workflowLevelService
    )
  }

  lazy val recentChangesService = new CachedNodeChangesServiceImpl(new NodeChangesServiceImpl(reportsRepository), () => configService.rudder_compute_changes().toBox)

  //////////////////////////////////////////////////////////////////////////////////////////
  //////////////////////////////////////////////////////////////////////////////////////////

  /**
   * A method to call to force initialisation of all object and services.
   * This is a good place to check boottime things, and throws
   * "application broken - can not start" exception
   *
   * Important: if that method is not called, RudderConfig will be
   * lazy and will only be initialised on the first call to one
   * of its (public) methods.
   */
  def init() : IO[SystemError, Unit] = {

    IOResult.effect {
      import scala.jdk.CollectionConverters._
      val config = RudderProperties.config
      if(ApplicationLogger.isInfoEnabled) {
        //sort properties by key name
        val properties = config.entrySet.asScala.toSeq.sortBy( _.getKey ).flatMap{ x =>
          //the log line: registered property: property_name=property_value
          if(hiddenRegisteredProperties.contains(x.getKey)) None
          else Some(s"registered property: ${x.getKey}=${if(filteredPasswords.contains(x.getKey)) "**********" else x.getValue.render}")
        }
        ApplicationLogger.info("List of registered properties:")
        properties.foreach { p =>
          ApplicationLogger.info(p)
        }
        ApplicationLogger.info("Plugin's license directory: '/opt/rudder/etc/plugins/licenses/'")
      }

      ////////// bootstraps checks //////////
      // they must be out of Lift boot() because that method
      // is encapsulated in a try/catch ( see net.liftweb.http.provider.HTTPProvider.bootLift )
      val checks = RudderConfig.allBootstrapChecks
      checks.checks()
    }
  }

  //
  // Concrete implementation.
  // They are private to that object, and they can refer to other
  // private implementation as long as they conform to interface.
  //

  lazy val gitParseTechniqueLibrary = new GitParseTechniqueLibrary(
        techniqueParser
      , gitConfigRepo
      , gitRevisionProvider
      , "techniques"
      , "metadata.xml"
    )
  lazy val configurationRepository = new ConfigurationRepositoryImpl(
      roLdapDirectiveRepository
    , techniqueRepository
    , roLdapRuleRepository
    , parseActiveTechniqueLibrary
    , gitParseTechniqueLibrary
    , parseRules
  )

  private[this] lazy val roLDAPApiAccountRepository = new RoLDAPApiAccountRepository(
      rudderDitImpl
    , roLdap
    , ldapEntityMapper
    , stringUuidGenerator
    , ApiAuthorization.allAuthz.acl // for system token
  )

  private[this] lazy val woLDAPApiAccountRepository = new WoLDAPApiAccountRepository(
      rudderDitImpl
    , rwLdap
    , ldapEntityMapper
    , ldapDiffMapper
    , logRepository
    , personIdentServiceImpl
  )

  private[this] lazy val ruleApplicationStatusImpl: RuleApplicationStatusService = new RuleApplicationStatusServiceImpl()
  private[this] lazy val propertyEngineServiceImpl: PropertyEngineService = new PropertyEngineServiceImpl(
    List.empty
  )

  def DN(rdn: String, parent: DN) = new DN(new RDN(rdn),  parent)
  private[this] lazy val LDAP_BASEDN = new DN("cn=rudder-configuration")
  private[this] lazy val LDAP_INVENTORIES_BASEDN = DN("ou=Inventories", LDAP_BASEDN)
  private[this] lazy val LDAP_INVENTORIES_SOFTWARE_BASEDN = LDAP_INVENTORIES_BASEDN

  private[this] lazy val acceptedNodesDitImpl: InventoryDit = new InventoryDit(DN("ou=Accepted Inventories", LDAP_INVENTORIES_BASEDN), LDAP_INVENTORIES_SOFTWARE_BASEDN, "Accepted inventories")
  private[this] lazy val pendingNodesDitImpl: InventoryDit = new InventoryDit(DN("ou=Pending Inventories", LDAP_INVENTORIES_BASEDN), LDAP_INVENTORIES_SOFTWARE_BASEDN, "Pending inventories")
  private[this] lazy val removedNodesDitImpl = new InventoryDit(DN("ou=Removed Inventories", LDAP_INVENTORIES_BASEDN), LDAP_INVENTORIES_SOFTWARE_BASEDN,"Removed Servers")
  private[this] lazy val rudderDitImpl: RudderDit = new RudderDit(DN("ou=Rudder", LDAP_BASEDN))
  private[this] lazy val nodeDitImpl: NodeDit = new NodeDit(LDAP_BASEDN)
  private[this] lazy val inventoryDitService: InventoryDitService = new InventoryDitServiceImpl(pendingNodesDitImpl, acceptedNodesDitImpl, removedNodesDitImpl)
  private[this] lazy val uuidGen: StringUuidGenerator = new StringUuidGeneratorImpl
  private[this] lazy val systemVariableSpecService = new SystemVariableSpecServiceImpl()
  private[this] lazy val ldapEntityMapper: LDAPEntityMapper = new LDAPEntityMapper(rudderDitImpl, nodeDitImpl, acceptedNodesDitImpl, queryParser, inventoryMapper)

  ///// items serializer - service that transforms items to XML /////
  private[this] lazy val ruleSerialisation: RuleSerialisation = new RuleSerialisationImpl(Constants.XML_CURRENT_FILE_FORMAT.toString)
  private[this] lazy val ruleCategorySerialisation: RuleCategorySerialisation = new RuleCategorySerialisationImpl(Constants.XML_CURRENT_FILE_FORMAT.toString)
  private[this] lazy val rootSectionSerialisation : SectionSpecWriter = new SectionSpecWriterImpl()
  private[this] lazy val activeTechniqueCategorySerialisation: ActiveTechniqueCategorySerialisation =
    new ActiveTechniqueCategorySerialisationImpl(Constants.XML_CURRENT_FILE_FORMAT.toString)
  private[this] lazy val activeTechniqueSerialisation: ActiveTechniqueSerialisation =
    new ActiveTechniqueSerialisationImpl(Constants.XML_CURRENT_FILE_FORMAT.toString)
  private[this] lazy val directiveSerialisation: DirectiveSerialisation =
    new DirectiveSerialisationImpl(Constants.XML_CURRENT_FILE_FORMAT.toString)
  private[this] lazy val nodeGroupCategorySerialisation: NodeGroupCategorySerialisation =
    new NodeGroupCategorySerialisationImpl(Constants.XML_CURRENT_FILE_FORMAT.toString)
  private[this] lazy val nodeGroupSerialisation: NodeGroupSerialisation =
    new NodeGroupSerialisationImpl(Constants.XML_CURRENT_FILE_FORMAT.toString)
  private[this] lazy val deploymentStatusSerialisation : DeploymentStatusSerialisation =
    new DeploymentStatusSerialisationImpl(Constants.XML_CURRENT_FILE_FORMAT.toString)
  private[this] lazy val globalParameterSerialisation: GlobalParameterSerialisation =
    new GlobalParameterSerialisationImpl(Constants.XML_CURRENT_FILE_FORMAT.toString)
  private[this] lazy val apiAccountSerialisation: APIAccountSerialisation =
    new APIAccountSerialisationImpl(Constants.XML_CURRENT_FILE_FORMAT.toString)
  private[this] lazy val propertySerialization: GlobalPropertySerialisation =
    new GlobalPropertySerialisationImpl(Constants.XML_CURRENT_FILE_FORMAT.toString)
  lazy val changeRequestChangesSerialisation : ChangeRequestChangesSerialisation =
    new ChangeRequestChangesSerialisationImpl(
        Constants.XML_CURRENT_FILE_FORMAT.toString
      , nodeGroupSerialisation
      , directiveSerialisation
      , ruleSerialisation
      , globalParameterSerialisation
      , techniqueRepositoryImpl
      , rootSectionSerialisation
    )
  lazy val secretSerialisation: SecretSerialisation =
    new SecretSerialisationImpl(Constants.XML_CURRENT_FILE_FORMAT.toString)
  private[this] lazy val eventLogFactory = new EventLogFactoryImpl(
      ruleSerialisation
    , directiveSerialisation
    , nodeGroupSerialisation
    , activeTechniqueSerialisation
    , globalParameterSerialisation
    , apiAccountSerialisation
    , propertySerialization
    , secretSerialisation
  )
  private[this] lazy val pathComputer = new PathComputerImpl(
      Constants.NODE_PROMISES_PARENT_DIR_BASE
    , Constants.NODE_PROMISES_PARENT_DIR
    , RUDDER_DIR_BACKUP
    , Constants.CFENGINE_COMMUNITY_PROMISES_PATH
    , Constants.CFENGINE_NOVA_PROMISES_PATH
  )

  /*
   * For now, we don't want to query server other
   * than the accepted ones.
   */
  private[this] lazy val getSubGroupChoices = () => roLdapNodeGroupRepository.getAll().map( seq => seq.map(g => SubGroupChoice(g.id, g.name)))
  private[this] lazy val ditQueryDataImpl = new DitQueryData(acceptedNodesDitImpl, nodeDit, rudderDit, getSubGroupChoices)
  private[this] lazy val queryParser = new CmdbQueryParser with DefaultStringQueryParser with JsonQueryLexer {
    override val criterionObjects = Map[String, ObjectCriterion]() ++ ditQueryDataImpl.criteriaMap
  }
  private[this] lazy val inventoryMapper: InventoryMapper = new InventoryMapper(inventoryDitService, pendingNodesDitImpl, acceptedNodesDitImpl, removedNodesDitImpl)
  private[this] lazy val fullInventoryFromLdapEntries: FullInventoryFromLdapEntries = new FullInventoryFromLdapEntriesImpl(inventoryDitService, inventoryMapper)
  private[this] lazy val ldapDiffMapper = new LDAPDiffMapper(ldapEntityMapper, queryParser)

  private[this] lazy val activeTechniqueCategoryUnserialisation = new ActiveTechniqueCategoryUnserialisationImpl
  private[this] lazy val activeTechniqueUnserialisation = new ActiveTechniqueUnserialisationImpl
  private[this] lazy val directiveUnserialisation = new DirectiveUnserialisationImpl
  private[this] lazy val nodeGroupCategoryUnserialisation = new NodeGroupCategoryUnserialisationImpl
  private[this] lazy val nodeGroupUnserialisation = new NodeGroupUnserialisationImpl(queryParser)
  private[this] lazy val ruleUnserialisation = new RuleUnserialisationImpl
  private[this] lazy val ruleCategoryUnserialisation = new RuleCategoryUnserialisationImpl
  private[this] lazy val globalParameterUnserialisation = new GlobalParameterUnserialisationImpl
  lazy val changeRequestChangesUnserialisation = new ChangeRequestChangesUnserialisationImpl(
      nodeGroupUnserialisation
    , directiveUnserialisation
    , ruleUnserialisation
    , globalParameterUnserialisation
    , techniqueRepository
    , sectionSpecParser
  )
  private[this] lazy val entityMigration = DefaultXmlEventLogMigration

  private[this] lazy val eventLogDetailsServiceImpl = new EventLogDetailsServiceImpl(
      queryParser
    , new DirectiveUnserialisationImpl
    , new NodeGroupUnserialisationImpl(queryParser)
    , new RuleUnserialisationImpl
    , new ActiveTechniqueUnserialisationImpl
    , new DeploymentStatusUnserialisationImpl
    , new GlobalParameterUnserialisationImpl
    , new ApiAccountUnserialisationImpl
    , new SecretUnserialisationImpl
  )

  //////////////////////////////////////////////////////////
  //  non success services that could perhaps be
  //////////////////////////////////////////////////////////

  // => rwLdap is only used to repair an error, that could be repaired elsewhere.

  // => because of systemVariableSpecService
  // metadata.xml parser

  private[this] lazy val variableSpecParser = new VariableSpecParser
  private[this] lazy val sectionSpecParser = new SectionSpecParser(variableSpecParser)
  private[this] lazy val techniqueParser = {
    new TechniqueParser(variableSpecParser,sectionSpecParser,systemVariableSpecService)
  }

  private[this] lazy val userPropertyServiceImpl = new StatelessUserPropertyService(
      configService.rudder_ui_changeMessage_enabled _
    , configService.rudder_ui_changeMessage_mandatory _
    , configService.rudder_ui_changeMessage_explanation _
  )

  ////////////////////////////////////
  //  non success services
  ////////////////////////////////////

  ///// end /////

  private[this] lazy val logRepository = {
    val eventLogRepo = new EventLogJdbcRepository(doobie, eventLogFactory)
    techniqueRepositoryImpl.registerCallback(new LogEventOnTechniqueReloadCallback(
        "LogEventTechnique"
      , 100 // must be before most of other
      , eventLogRepo
    ))
    eventLogRepo
  }
  private[this] lazy val inventoryLogEventServiceImpl = new InventoryEventLogServiceImpl(logRepository)
  private[this] lazy val gitConfigRepo = GitRepositoryProviderImpl.make(RUDDER_GIT_ROOT_CONFIG_REPO).runNow
  private[this] lazy val gitConfigRepoGC = new GitGC(gitConfigRepo, RUDDER_GIT_GC)
  gitConfigRepoGC.start()
  private[this] lazy val gitRevisionProviderImpl = new LDAPGitRevisionProvider(rwLdap, rudderDitImpl, gitConfigRepo, RUDDER_TECHNIQUELIBRARY_GIT_REFS_PATH)
  private[this] lazy val techniqueReader: TechniqueReader = {
    //find the relative path from gitConfigRepo to the ptlib root
    val gitSlash = new File(RUDDER_GIT_ROOT_CONFIG_REPO).getPath + "/"
    if(!RUDDER_DIR_TECHNIQUES.startsWith(gitSlash)) {
      ApplicationLogger.error("The Technique library root directory must be a sub-directory of '%s', but it is configured to be: '%s'".format(RUDDER_GIT_ROOT_CONFIG_REPO, RUDDER_DIR_TECHNIQUES))
      throw new RuntimeException("The Technique library root directory must be a sub-directory of '%s', but it is configured to be: '%s'".format(RUDDER_GIT_ROOT_CONFIG_REPO, RUDDER_DIR_TECHNIQUES))
    }

    //create a demo default-directive-names.conf if none exists
    val defaultDirectiveNames = new File(RUDDER_DIR_TECHNIQUES, "default-directive-names.conf")
    if(!defaultDirectiveNames.exists) {
      FileUtils.writeStringToFile(defaultDirectiveNames, """
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
        |""".stripMargin, RUDDER_CHARSET.value)
    }

    val relativePath = RUDDER_DIR_TECHNIQUES.substring(gitSlash.size, RUDDER_DIR_TECHNIQUES.size)
    new GitTechniqueReader(
        techniqueParser
      , gitRevisionProviderImpl
      , gitConfigRepo
      , "metadata.xml"
      , "category.xml"
      , Some(relativePath)
      , "default-directive-names.conf"
    )
  }

  private[this] lazy val roLdap =
    new ROPooledSimpleAuthConnectionProvider(
        host = LDAP_HOST
      , port = LDAP_PORT
      , authDn = LDAP_AUTHDN
      , authPw = LDAP_AUTHPW
      , poolSize = LDAP_MAX_POOL_SIZE
      , blockingModule = ZioRuntime.environment
    )
  lazy val rwLdap =
    new RWPooledSimpleAuthConnectionProvider(
        host = LDAP_HOST
      , port = LDAP_PORT
      , authDn = LDAP_AUTHDN
      , authPw = LDAP_AUTHPW
      , poolSize = LDAP_MAX_POOL_SIZE
      , blockingModule = ZioRuntime.environment
    )

  //query processor for accepted nodes
  private[this] lazy val queryProcessor = new AcceptedNodesLDAPQueryProcessor(
    nodeDitImpl,
    acceptedNodesDitImpl,
    new InternalLDAPQueryProcessor(roLdap, acceptedNodesDitImpl, nodeDit, ditQueryDataImpl, ldapEntityMapper),
    nodeInfoServiceImpl
  )

  //we need a roLdap query checker for nodes in pending
  private[this] lazy val inventoryQueryChecker = new PendingNodesLDAPQueryChecker(
    new InternalLDAPQueryProcessor(
        roLdap
      , pendingNodesDitImpl
      , nodeDit
        // here, we don't want to look for subgroups to show them in the form => always return an empty list
      , new DitQueryData(pendingNodesDitImpl, nodeDit, rudderDit, () => Nil.succeed)
      , ldapEntityMapper
    ),
    nodeInfoServiceImpl
  )
  private[this] lazy val dynGroupServiceImpl = new DynGroupServiceImpl(rudderDitImpl, roLdap, ldapEntityMapper)

  lazy val pendingNodeCheckGroup = new CheckPendingNodeInDynGroups(inventoryQueryChecker)

  private[this] lazy val ldapFullInventoryRepository = new FullInventoryRepositoryImpl(inventoryDitService, inventoryMapper, rwLdap)
  private[this] lazy val unitRefuseGroup: UnitRefuseInventory = new RefuseGroups(
    "refuse_node:delete_id_in_groups",
    roLdapNodeGroupRepository, woLdapNodeGroupRepository)
  private[this] lazy val acceptInventory: UnitAcceptInventory with UnitRefuseInventory = new AcceptInventory(
    "accept_new_server:inventory",
    pendingNodesDitImpl,
    acceptedNodesDitImpl,
    ldapFullInventoryRepository)
  private[this] lazy val acceptNodeAndMachineInNodeOu: UnitAcceptInventory with UnitRefuseInventory = new AcceptFullInventoryInNodeOu(
      "accept_new_server:ou=node"
    , nodeDitImpl
    , rwLdap
    , ldapEntityMapper
    , PendingInventory
    , () => configService.rudder_node_onaccept_default_policy_mode().toBox
    , () => configService.rudder_node_onaccept_default_state().toBox
  )

  private[this] lazy val acceptHostnameAndIp: UnitAcceptInventory = new AcceptHostnameAndIp(
      "accept_new_server:check_hostname_unicity"
    , AcceptedInventory
    , queryProcessor
    , ditQueryDataImpl
    , psMngtService
    , configService.node_accept_duplicated_hostname()
  )

  private[this] lazy val historizeNodeStateOnChoice: UnitAcceptInventory with UnitRefuseInventory = new HistorizeNodeStateOnChoice(
      "accept_or_refuse_new_node:historize_inventory"
    , ldapFullInventoryRepository
    , diffRepos
    , PendingInventory
  )
  private[this] lazy val updateFactRepoOnChoice: UnitAcceptInventory with UnitRefuseInventory = new UpdateFactRepoOnChoice(
      "accept_or_refuse_new_node:update_fact_repo"
    , PendingInventory
    , factRepo
  )

  private[this] lazy val nodeGridImpl = new NodeGrid(ldapFullInventoryRepository, nodeInfoServiceImpl, configService)

  private[this] lazy val modificationService = new ModificationService(logRepository,gitModificationRepository,itemArchiveManagerImpl,uuidGen)
  private[this] lazy val eventListDisplayerImpl = new EventListDisplayer( logRepository )
  private[this] lazy val eventLogDetailsGenerator = new EventLogDetailsGenerator(
      eventLogDetailsServiceImpl
    , logRepository
    , roLdapNodeGroupRepository
    , roLdapDirectiveRepository
    , nodeInfoServiceImpl
    , roLDAPRuleCategoryRepository
    , modificationService
    , personIdentServiceImpl
    , linkUtil
    , diffDisplayer
  )
  private[this] lazy val databaseManagerImpl = new DatabaseManagerImpl(reportsRepositoryImpl, updateExpectedRepo)
  private[this] lazy val softwareInventoryDAO: ReadOnlySoftwareDAO = new ReadOnlySoftwareDAOImpl(inventoryDitService, roLdap, inventoryMapper)
  private[this] lazy val softwareInventoryRWDAO: WriteOnlySoftwareDAO = new WriteOnlySoftwareDAOImpl(acceptedNodesDitImpl, rwLdap)
  private[this] lazy val softwareService: SoftwareService = new SoftwareServiceImpl(softwareInventoryDAO, softwareInventoryRWDAO, acceptedNodesDit)

  private[this] lazy val nodeSummaryServiceImpl = new NodeSummaryServiceImpl(inventoryDitService, inventoryMapper, roLdap)
  private[this] lazy val diffRepos: InventoryHistoryLogRepository =
    new InventoryHistoryLogRepository(HISTORY_INVENTORIES_ROOTDIR, new FullInventoryFileParser(fullInventoryFromLdapEntries, inventoryMapper))

  private[this] lazy val personIdentServiceImpl = new TrivialPersonIdentService

  private[this] lazy val roParameterServiceImpl = new RoParameterServiceImpl(roLDAPParameterRepository)
  private[this] lazy val woParameterServiceImpl = new WoParameterServiceImpl(roParameterServiceImpl, woLDAPParameterRepository, asyncDeploymentAgentImpl)

  ///// items archivers - services that allows to transform items to XML and save then on a Git FS /////
  private[this] lazy val gitModificationRepository = new GitModificationRepositoryImpl(doobie)
  private[this] lazy val gitRuleArchiver: GitRuleArchiver = new GitRuleArchiverImpl(
      gitConfigRepo
    , ruleSerialisation
    , rulesDirectoryName
    , prettyPrinter
    , gitModificationRepository
    , RUDDER_CHARSET.name
    , RUDDER_GROUP_OWNER_CONFIG_REPO
  )
  private[this] lazy val gitRuleCategoryArchiver: GitRuleCategoryArchiver = new GitRuleCategoryArchiverImpl(
      gitConfigRepo
    , ruleCategorySerialisation
    , ruleCategoriesDirectoryName
    , prettyPrinter
    , gitModificationRepository
    , RUDDER_CHARSET.name
    , "category.xml"
    , RUDDER_GROUP_OWNER_CONFIG_REPO
  )
  private[this] lazy val gitActiveTechniqueCategoryArchiver: GitActiveTechniqueCategoryArchiver = new GitActiveTechniqueCategoryArchiverImpl(
      gitConfigRepo
    , activeTechniqueCategorySerialisation
    , userLibraryDirectoryName
    , prettyPrinter
    , gitModificationRepository
    , RUDDER_CHARSET.name
    , "category.xml"
    , RUDDER_GROUP_OWNER_CONFIG_REPO
  )
  private[this] lazy val gitActiveTechniqueArchiver: GitActiveTechniqueArchiverImpl = new GitActiveTechniqueArchiverImpl(
      gitConfigRepo
    , activeTechniqueSerialisation
    , userLibraryDirectoryName
    , prettyPrinter
    , gitModificationRepository
    , Buffer()
    , RUDDER_CHARSET.name
    , "activeTechniqueSettings.xml"
    , RUDDER_GROUP_OWNER_CONFIG_REPO
  )
  private[this] lazy val gitDirectiveArchiver: GitDirectiveArchiver = new GitDirectiveArchiverImpl(
      gitConfigRepo
    , directiveSerialisation
    , userLibraryDirectoryName
    , prettyPrinter
    , gitModificationRepository
    , RUDDER_CHARSET.name
    , RUDDER_GROUP_OWNER_CONFIG_REPO
  )
  private[this] lazy val gitNodeGroupArchiver: GitNodeGroupArchiver = new GitNodeGroupArchiverImpl(
      gitConfigRepo
    , nodeGroupSerialisation
    , nodeGroupCategorySerialisation
    , groupLibraryDirectoryName
    , prettyPrinter
    , gitModificationRepository
    , RUDDER_CHARSET.name
    , "category.xml"
    , RUDDER_GROUP_OWNER_CONFIG_REPO
  )
  private[this] lazy val gitParameterArchiver: GitParameterArchiver = new GitParameterArchiverImpl(
      gitConfigRepo
    , globalParameterSerialisation
    , parametersDirectoryName
    , prettyPrinter
    , gitModificationRepository
    , RUDDER_CHARSET.name
    , RUDDER_GROUP_OWNER_CONFIG_REPO
  )
  ////////////// MUTEX FOR rwLdap REPOS //////////////

  private[this] lazy val uptLibReadWriteMutex    = new ZioTReentrantLock("directive-lock")
  private[this] lazy val groupLibReadWriteMutex  = new ZioTReentrantLock("group-lock")
  private[this] lazy val nodeReadWriteMutex      = new ZioTReentrantLock("node-lock")
  private[this] lazy val parameterReadWriteMutex = new ZioTReentrantLock("parameter-lock")
  private[this] lazy val ruleReadWriteMutex      = new ZioTReentrantLock("rule-lock")
  private[this] lazy val ruleCatReadWriteMutex   = new ZioTReentrantLock("rule-cat-lock")

  private[this] lazy val roLdapDirectiveRepository = new RoLDAPDirectiveRepository(
        rudderDitImpl, roLdap, ldapEntityMapper, techniqueRepositoryImpl, uptLibReadWriteMutex)
  private[this] lazy val woLdapDirectiveRepository = {{
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

      techniqueRepositoryImpl.registerCallback(new SaveDirectivesOnTechniqueCallback("SaveDirectivesOnTechniqueCallback", 100, directiveEditorServiceImpl, roLdapDirectiveRepository, repo))

      repo
    }
  }
  private[this] lazy val roLdapRuleRepository = new RoLDAPRuleRepository(rudderDitImpl, roLdap, ldapEntityMapper, ruleReadWriteMutex)

  private[this] lazy val woLdapRuleRepository: WoRuleRepository = new WoLDAPRuleRepository(
      roLdapRuleRepository
    , rwLdap
    , ldapDiffMapper
    , roLdapNodeGroupRepository
    , logRepository
    , gitRuleArchiver
    , personIdentServiceImpl
    , RUDDER_AUTOARCHIVEITEMS
  )

  private[this] lazy val woLdapNodeRepository: WoNodeRepository = new WoLDAPNodeRepository(
      nodeDitImpl
    , acceptedNodesDit
    , ldapEntityMapper
    , rwLdap
    , logRepository
    , nodeReadWriteMutex
    , cachedNodeConfigurationService
    , reportingServiceImpl
  )

  private[this] lazy val roLdapNodeGroupRepository = new RoLDAPNodeGroupRepository(
      rudderDitImpl, roLdap, ldapEntityMapper, groupLibReadWriteMutex
  )
  private[this] lazy val woLdapNodeGroupRepository = new WoLDAPNodeGroupRepository(
      roLdapNodeGroupRepository
    , rwLdap
    , ldapDiffMapper
    , uuidGen
    , logRepository
    , gitNodeGroupArchiver
    , personIdentServiceImpl
    , RUDDER_AUTOARCHIVEITEMS
  )

  private[this] lazy val roLDAPRuleCategoryRepository = {
    new RoLDAPRuleCategoryRepository(
        rudderDitImpl
      , roLdap
      , ldapEntityMapper
      , ruleCatReadWriteMutex
    )
  }
  private[this] lazy val woLDAPRuleCategoryRepository = {
    new WoLDAPRuleCategoryRepository(
        roLDAPRuleCategoryRepository
      , rwLdap
      , uuidGen
      , gitRuleCategoryArchiver
      , personIdentServiceImpl
      , RUDDER_AUTOARCHIVEITEMS
    )
  }

  lazy val roLDAPParameterRepository = new RoLDAPParameterRepository(
      rudderDitImpl, roLdap, ldapEntityMapper, parameterReadWriteMutex
  )
  private[this] lazy val woLDAPParameterRepository = new WoLDAPParameterRepository(
      roLDAPParameterRepository
    , rwLdap
    , ldapDiffMapper
    , logRepository
    , gitParameterArchiver
    , personIdentServiceImpl
    , RUDDER_AUTOARCHIVEITEMS
  )

  private[this] lazy val itemArchiveManagerImpl = new ItemArchiveManagerImpl(
      roLdapRuleRepository
    , woLdapRuleRepository
    , roLDAPRuleCategoryRepository
    , roLdapDirectiveRepository
    , roLdapNodeGroupRepository
    , roLDAPParameterRepository
    , woLDAPParameterRepository
    , gitConfigRepo
    , gitRevisionProviderImpl
    , gitRuleArchiver
    , gitRuleCategoryArchiver
    , gitActiveTechniqueCategoryArchiver
    , gitActiveTechniqueArchiver
    , gitNodeGroupArchiver
    , gitParameterArchiver
    , parseRules
    , parseActiveTechniqueLibrary
    , parseGlobalParameter
    , parseRuleCategories
    , importTechniqueLibrary
    , parseGroupLibrary
    , importGroupLibrary
    , importRuleCategoryLibrary
    , logRepository
    , asyncDeploymentAgentImpl
    , gitModificationRepository
    , dynGroupUpdaterService
  )

  private[this] lazy val globalComplianceModeService : ComplianceModeService =
    new ComplianceModeServiceImpl(
      () => configService.rudder_compliance_mode_name().toBox
      , () => configService.rudder_compliance_heartbeatPeriod().toBox
    )
  private[this] lazy val globalAgentRunService : AgentRunIntervalService =
    new AgentRunIntervalServiceImpl(
        nodeInfoServiceImpl
      , () => configService.agent_run_interval().toBox
      , () => configService.agent_run_start_hour().toBox
      , () => configService.agent_run_start_minute().toBox
      , () => configService.agent_run_splaytime().toBox
      , () => configService.rudder_compliance_heartbeatPeriod().toBox
    )

  private[this] lazy val systemVariableService: SystemVariableService = new SystemVariableServiceImpl(
      systemVariableSpecService
    , psMngtService
    , RUDDER_DIR_DEPENDENCIES
    , CFENGINE_POLICY_DISTRIBUTION_PORT
    , HTTPS_POLICY_DISTRIBUTION_PORT
    , RUDDER_DIR_SHARED_FILES_FOLDER
    , RUDDER_WEBDAV_USER
    , RUDDER_WEBDAV_PASSWORD
    , RUDDER_JDBC_URL
    , RUDDER_JDBC_USERNAME
    , RUDDER_JDBC_PASSWORD
    , RUDDER_GIT_ROOT_CONFIG_REPO
    , rudderFullVersion
    , () => configService.cfengine_server_denybadclocks().toBox
    , () => configService.relay_server_sync_method().toBox
    , () => configService.relay_server_syncpromises().toBox
    , () => configService.relay_server_syncsharedfiles().toBox
    , () => configService.cfengine_modified_files_ttl().toBox
    , () => configService.cfengine_outputs_ttl().toBox
    , () => configService.send_server_metrics().toBox
    , () => configService.rudder_report_protocol_default().toBox
    , () => configService.rudder_verify_certificates().toBox
  )
  private[this] lazy val rudderCf3PromisesFileWriterService = new PolicyWriterServiceImpl(
      techniqueRepositoryImpl
    , pathComputer
    , new NodeConfigurationLoggerImpl(RUDDER_DEBUG_NODE_CONFIGURATION_PATH)
    , new PrepareTemplateVariablesImpl(techniqueRepositoryImpl, systemVariableSpecService, new BuildBundleSequence(systemVariableSpecService, writeAllAgentSpecificFiles), agentRegister)
    , new FillTemplatesService()
    , writeAllAgentSpecificFiles
    , HOOKS_D
    , HOOKS_IGNORE_SUFFIXES
    , RUDDER_CHARSET.value
    , Some(RUDDER_GROUP_OWNER_GENERATED_POLICIES)
  )

  //must be here because of circular dependency if in techniqueRepository
  techniqueRepositoryImpl.registerCallback(new TechniqueAcceptationUpdater(
      "UpdatePTAcceptationDatetime"
    , 50
    , roLdapDirectiveRepository
    , woLdapDirectiveRepository
    , techniqueRepository
    , uuidGen
  ))

  private[this] lazy val techniqueRepositoryImpl = {
    val service = new TechniqueRepositoryImpl(
        techniqueReader,
      Seq(),
      uuidGen
    )
    service
  }
  lazy val interpolationCompiler = new InterpolatedValueCompilerImpl(propertyEngineService)
  lazy val typeParameterService : PlugableParameterTypeService = new PlugableParameterTypeService()
  private[this] lazy val ruleValService: RuleValService = new RuleValServiceImpl(interpolationCompiler)

  private[this] lazy val psMngtService: PolicyServerManagementService = new PolicyServerManagementServiceImpl(
      rwLdap
    , rudderDit
    , eventLogRepository
  )

  lazy val deploymentService = {
    new PromiseGenerationServiceImpl(
        roLdapRuleRepository
      , woLdapRuleRepository
      , ruleValService
      , systemVariableService
      , nodeConfigurationHashRepo
      , nodeInfoServiceImpl
      , updateExpectedRepo
      , roNodeGroupRepository
      , roDirectiveRepository
      , configurationRepository
      , ruleApplicationStatusImpl
      , roParameterServiceImpl
      , interpolationCompiler
      , ldapFullInventoryRepository
      , globalComplianceModeService
      , globalAgentRunService
      , reportingServiceImpl
      , rudderCf3PromisesFileWriterService
      , new WriteNodeCertificatesPemImpl(Some(RUDDER_RELAY_RELOAD))
      , cachedNodeConfigurationService
      , () => configService.rudder_featureSwitch_directiveScriptEngine().toBox
      , () => configService.rudder_global_policy_mode().toBox
      , () => configService.rudder_generation_compute_dyngroups().toBox
      , () => configService.rudder_generation_max_parallelism().toBox
      , () => configService.rudder_generation_js_timeout().toBox
      , () => configService.rudder_generation_continue_on_error().toBox
      , HOOKS_D
      , HOOKS_IGNORE_SUFFIXES
      , UPDATED_NODE_IDS_PATH
      , UPDATED_NODE_IDS_COMPABILITY
      , GENERATION_FAILURE_MSG_PATH
      , allNodeCertificatesPemFile = better.files.File("/var/rudder/lib/ssl/allnodescerts.pem")
      , POSTGRESQL_IS_LOCAL
  )}


  lazy val policyGenerationBootGuard = zio.Promise.make[Nothing, Unit].runNow

  private[this] lazy val asyncDeploymentAgentImpl: AsyncDeploymentActor = {
    val agent = new AsyncDeploymentActor(
        deploymentService
      , eventLogDeploymentServiceImpl
      , deploymentStatusSerialisation
      , () => configService.rudder_generation_delay()
      , () => configService.rudder_generation_trigger()
      , policyGenerationBootGuard
    )
    techniqueRepositoryImpl.registerCallback(
        new DeployOnTechniqueCallback("DeployOnPTLibUpdate", 1000, agent)
    )
    agent
  }

  private[this] lazy val newNodeManagerImpl = {
    //the sequence of unit process to accept a new inventory
    val unitAcceptors =
      historizeNodeStateOnChoice ::
      updateFactRepoOnChoice ::
      acceptNodeAndMachineInNodeOu ::
      acceptInventory ::
      acceptHostnameAndIp ::
      Nil

    //the sequence of unit process to refuse a new inventory
    val unitRefusors =
      historizeNodeStateOnChoice ::
      updateFactRepoOnChoice ::
      unitRefuseGroup ::
      acceptInventory ::
      Nil

    new NewNodeManagerImpl(
        roLdap
      , pendingNodesDitImpl
      , acceptedNodesDitImpl
      , nodeSummaryServiceImpl
      , ldapFullInventoryRepository
      , unitAcceptors
      , unitRefusors
      , inventoryHistoryLogRepository
      , eventLogRepository
      , dyngroupUpdaterBatch
      , List(nodeInfoServiceImpl)
      , cachedNodeConfigurationService
      , reportingServiceImpl
      , nodeInfoServiceImpl
      , HOOKS_D
      , HOOKS_IGNORE_SUFFIXES
    )
  }

  lazy val nodeConfigurationHashRepo: NodeConfigurationHashRepository = {
    val x = new FileBasedNodeConfigurationHashRepository(FileBasedNodeConfigurationHashRepository.defaultHashesPath)
    x.init
    x
  }

  private[this] lazy val reportingServiceImpl = {
    val reportingServiceImpl = new CachedReportingServiceImpl(
        new ReportingServiceImpl(
            findExpectedRepo
          , reportsRepositoryImpl
          , roAgentRunsRepository
          , globalAgentRunService
          , nodeInfoServiceImpl
          , roLdapDirectiveRepository
          , roRuleRepository
          , cachedNodeConfigurationService
          , () => globalComplianceModeService.getGlobalComplianceMode
          , configService.rudder_global_policy_mode _
          , () => configService.rudder_compliance_unexpected_report_interpretation().toBox
          , RUDDER_JDBC_BATCH_MAX_SIZE
        )
      , nodeInfoServiceImpl
      , RUDDER_JDBC_BATCH_MAX_SIZE // use same size as for SQL requests
      , complianceRepositoryImpl
    )
    // to avoid a StackOverflowError, we set the compliance cache once it'z ready,
    // and can construct the nodeconfigurationservice without the comlpince cache
    cachedNodeConfigurationService.addHook(reportingServiceImpl)
    reportingServiceImpl
  }


  private[this] lazy val pgIn = new PostgresqlInClause(70)
  private[this] lazy val findExpectedRepo = new FindExpectedReportsJdbcRepository(doobie, pgIn, RUDDER_JDBC_BATCH_MAX_SIZE)
  private[this] lazy val updateExpectedRepo = new UpdateExpectedReportsJdbcRepository(doobie, pgIn, RUDDER_JDBC_BATCH_MAX_SIZE)
  private[this] lazy val reportsRepositoryImpl = new ReportsJdbcRepository(doobie)
  private[this] lazy val complianceRepositoryImpl = new ComplianceJdbcRepository(
      doobie
    , () => configService.rudder_save_db_compliance_details().toBox
    , () => configService.rudder_save_db_compliance_levels().toBox
  )
  private[this] lazy val dataSourceProvider = new RudderDatasourceProvider(RUDDER_JDBC_DRIVER, RUDDER_JDBC_URL, RUDDER_JDBC_USERNAME, RUDDER_JDBC_PASSWORD, RUDDER_JDBC_MAX_POOL_SIZE)
  lazy val doobie = new Doobie(dataSourceProvider.datasource)

  private[this] lazy val parseRules : ParseRules with RuleRevisionRepository = new GitParseRules(
      ruleUnserialisation
    , gitConfigRepo
    , entityMigration
    , rulesDirectoryName
  )
  lazy val parseActiveTechniqueLibrary : GitParseActiveTechniqueLibrary = new GitParseActiveTechniqueLibrary(
      activeTechniqueCategoryUnserialisation
    , activeTechniqueUnserialisation
    , directiveUnserialisation
    , gitConfigRepo
    , gitRevisionProvider
    , entityMigration
    , userLibraryDirectoryName
  )
  private[this] lazy val importTechniqueLibrary : ImportTechniqueLibrary = new ImportTechniqueLibraryImpl(
     rudderDitImpl
   , rwLdap
   , ldapEntityMapper
   , uptLibReadWriteMutex
  )
  private[this] lazy val parseGroupLibrary : ParseGroupLibrary = new GitParseGroupLibrary(
      nodeGroupCategoryUnserialisation
    , nodeGroupUnserialisation
    , gitConfigRepo
    , entityMigration
    , groupLibraryDirectoryName
  )
  private[this] lazy val parseGlobalParameter : ParseGlobalParameters = new GitParseGlobalParameters(
      globalParameterUnserialisation
    , gitConfigRepo
    , entityMigration
    , parametersDirectoryName
  )
  private[this] lazy val parseRuleCategories : ParseRuleCategories = new GitParseRuleCategories(
      ruleCategoryUnserialisation
    , gitConfigRepo
    , entityMigration
    , ruleCategoriesDirectoryName
  )
  private[this] lazy val importGroupLibrary : ImportGroupLibrary = new ImportGroupLibraryImpl(
     rudderDitImpl
   , rwLdap
   , ldapEntityMapper
   , groupLibReadWriteMutex
  )
  private[this] lazy val importRuleCategoryLibrary : ImportRuleCategoryLibrary = new ImportRuleCategoryLibraryImpl(
     rudderDitImpl
   , rwLdap
   , ldapEntityMapper
   , ruleCatReadWriteMutex
  )
  private[this] lazy val eventLogDeploymentServiceImpl = new EventLogDeploymentService(logRepository, eventLogDetailsServiceImpl)
  private[this] lazy val nodeInfoServiceImpl = new NodeInfoServiceCachedImpl(
      roLdap
    , nodeDitImpl
    , acceptedNodesDitImpl
    , removedNodesDitImpl
    , pendingNodesDitImpl
    , ldapEntityMapper
    , inventoryMapper
    , FiniteDuration(LDAP_CACHE_NODE_INFO_MIN_INTERVAL.toMillis, "millis")
  )
  private[this] lazy val dependencyAndDeletionServiceImpl: DependencyAndDeletionService = new DependencyAndDeletionServiceImpl(
        new FindDependenciesImpl(roLdap, rudderDitImpl, ldapEntityMapper)
      , roLdapDirectiveRepository
      , woLdapDirectiveRepository
      , woLdapRuleRepository
      , woLdapNodeGroupRepository
  )

  private[this] lazy val logDisplayerImpl: LogDisplayer = new LogDisplayer(reportsRepositoryImpl, configurationRepository, roLdapRuleRepository)
  private[this] lazy val categoryHierarchyDisplayerImpl: CategoryHierarchyDisplayer = new CategoryHierarchyDisplayer()
  private[this] lazy val dyngroupUpdaterBatch: UpdateDynamicGroups = new UpdateDynamicGroups(
      dynGroupServiceImpl
    , dynGroupUpdaterService
    , asyncDeploymentAgentImpl
    , uuidGen
    , RUDDER_BATCH_DYNGROUP_UPDATEINTERVAL
    , () => configService.rudder_compute_dyngroups_max_parallelism().toBox
  )

  private[this] lazy val dynGroupUpdaterService = new DynGroupUpdaterServiceImpl(roLdapNodeGroupRepository, woLdapNodeGroupRepository, queryProcessor)

  private[this] lazy val dbCleaner: AutomaticReportsCleaning = {
    val cleanFrequency = AutomaticReportsCleaning.buildFrequency(
        RUDDER_BATCH_REPORTSCLEANER_FREQUENCY
      , RUDDER_BATCH_DATABASECLEANER_RUNTIME_MINUTE
      , RUDDER_BATCH_DATABASECLEANER_RUNTIME_HOUR
      , RUDDER_BATCH_DATABASECLEANER_RUNTIME_DAY) match {
      case Full(freq) => freq
      case eb:EmptyBox => val fail = eb ?~! "automatic reports cleaner is not correct"
        val exceptionMsg = "configuration file (/opt/rudder/etc/rudder-webapp.conf) is not correctly set, cause is %s".format(fail.msg)
        throw new RuntimeException(exceptionMsg)
    }


    new AutomaticReportsCleaning(
      databaseManagerImpl
    , roLDAPConnectionProvider
    , RUDDER_BATCH_REPORTSCLEANER_DELETE_TTL
    , RUDDER_BATCH_REPORTSCLEANER_ARCHIVE_TTL
    , RUDDER_BATCH_REPORTSCLEANER_COMPLIANCE_DELETE_TTL
    , RUDDER_BATCH_REPORTSCLEANER_LOG_DELETE_TTL
    , cleanFrequency
    )
  }

  private[this] lazy val techniqueLibraryUpdater = new CheckTechniqueLibrary(
      techniqueRepositoryImpl
    , asyncDeploymentAgent
    , uuidGen
    , RUDDER_BATCH_TECHNIQUELIBRARY_UPDATEINTERVAL
  )

  private[this] lazy val jsTreeUtilServiceImpl = new JsTreeUtilService(roLdapDirectiveRepository, techniqueRepositoryImpl)

  /*
   * Cleaning action are run for the case where the node was accepted, deleted, and unknown
   * (ie: we want to be able to run cleaning actions even on a node that was deleted in the past, but
   * for some reason the user discover that theres remaining things, and he wants to get rid of them
   * without knowing rudder internal place to look for all possible garbages)
   */

  /*
   * The list of post deletion action to execute in a shared reference, created at class instanciation.
   * External services can update it from removeNodeServiceImpl.
   */
  private[this] lazy val postNodeDeleteActions = Ref.make(
       new RemoveNodeInfoFromCache(nodeInfoServiceImpl)
    :: new CloseNodeConfiguration(updateExpectedRepo)
    :: new RemoveNodeFromComplianceCache(cachedNodeConfigurationService, reportingServiceImpl)
    :: new DeletePolicyServerPolicies(policyServerManagementService)
    :: new ResetKeyStatus(rwLdap, removedNodesDitImpl)
    :: new CleanUpCFKeys()
    :: new CleanUpNodePolicyFiles("/var/rudder/share")
    :: new DeleteNodeFact(factRepo)
    :: Nil
  ).runNow

  private[this] lazy val removeNodeServiceImpl = new RemoveNodeServiceImpl(
        nodeDitImpl
      , rudderDitImpl
      , pendingNodesDitImpl
      , acceptedNodesDitImpl
      , removedNodesDitImpl
      , rwLdap
      , ldapEntityMapper
      , roLdapNodeGroupRepository
      , woLdapNodeGroupRepository
      , nodeInfoServiceImpl
      , ldapFullInventoryRepository
      , logRepository
      , nodeReadWriteMutex
      , pathComputer
      , newNodeManager
      , postNodeDeleteActions
      , HOOKS_D
      , HOOKS_IGNORE_SUFFIXES
  )

  private[this] lazy val healthcheckService = new HealthcheckService(
    List(
        CheckCoreNumber
      , CheckFreeSpace
      , new CheckFileDescriptorLimit(nodeInfoService)
    )
  )

  lazy val healthcheckNotificationService = new HealthcheckNotificationService(healthcheckService, RUDDER_HEALTHCHECK_PERIOD)

  /**
   * *************************************************
   * Bootstrap check actions
   * **************************************************
   */

  lazy val allBootstrapChecks = new SequentialImmediateBootStrapChecks(
      new CheckConnections(dataSourceProvider, rwLdap)
    , new CheckTechniqueLibraryReload(
      techniqueRepositoryImpl
      , uuidGen
    )
    , new CheckAddSpecialTargetAllPolicyServers(rwLdap)
    , new CheckMigratedSystemTechniques(policyServerManagementService, gitConfigRepo, nodeInfoService, rwLdap, techniqueRepository, techniqueRepositoryImpl, uuidGen, woDirectiveRepository, woRuleRepository)
    , new CheckDIT(pendingNodesDitImpl, acceptedNodesDitImpl, removedNodesDitImpl, rudderDitImpl, rwLdap)
    , new CheckInitUserTemplateLibrary(
        rudderDitImpl, rwLdap, techniqueRepositoryImpl,
        roLdapDirectiveRepository, woLdapDirectiveRepository, uuidGen, asyncDeploymentAgentImpl) //new CheckDirectiveBusinessRules()
    , new CheckRudderGlobalParameter(roLDAPParameterRepository, woLDAPParameterRepository, uuidGen)
    , new CheckInitXmlExport(itemArchiveManagerImpl, personIdentServiceImpl, uuidGen)
    , new CheckNcfTechniqueUpdate(
          restExtractorService
        , ncfTechniqueWriter
        , roLDAPApiAccountRepository.systemAPIAccount
        , uuidGen
        , updateTechniqueLibrary
        , ncfTechniqueReader
        , resourceFileService
      )
    , new TriggerPolicyUpdate(
          asyncDeploymentAgent
        , uuidGen
    )
    , new CreateSystemToken(roLDAPApiAccountRepository.systemAPIAccount)
    , new LoadNodeComplianceCache(nodeInfoService, reportingServiceImpl)
  )

  //////////////////////////////////////////////////////////////////////////////////////////
  ////////////////////////////// Directive Editor and web fields //////////////////////////////
  //////////////////////////////////////////////////////////////////////////////////////////

  import com.normation.cfclerk.domain._

  object FieldFactoryImpl extends DirectiveFieldFactory {
    //only one field

    override def forType(v: VariableSpec, id: String): DirectiveField = {
      val prefixSize = "size-"
      v match {
        case selectOne: SelectOneVariableSpec => new SelectOneField(id, selectOne.valueslabels)
        case select: SelectVariableSpec => new SelectField(id, select.valueslabels)
        case input: InputVariableSpec => v.constraint.typeName match {
          case str: SizeVType => new InputSizeField(id, () => configService.rudder_featureSwitch_directiveScriptEngine().toBox, str.name.substring(prefixSize.size))
          case SharedFileVType => new FileField(id)
          case DestinationPathVType => default(id)
          case DateVType(r) => new DateField(Translator.isoDateFormatter)(id)
          case TimeVType(r) => new TimeField(Translator.isoTimeFormatter)(id)
          case PermVType => new FilePermsField(id)
          case BooleanVType => new CheckboxField(id)
          case TextareaVType(r) => new TextareaField(id, () => configService.rudder_featureSwitch_directiveScriptEngine().toBox)
          // Same field type for password and MasterPassword, difference is that master will have slave/used derived passwords, and password will not have any slave/used field
          case PasswordVType(algos) => new PasswordField(id, algos, input.constraint.mayBeEmpty, () => configService.rudder_featureSwitch_directiveScriptEngine().toBox)
          case MasterPasswordVType(algos) => new PasswordField(id, algos, input.constraint.mayBeEmpty, () => configService.rudder_featureSwitch_directiveScriptEngine().toBox)
          case AixDerivedPasswordVType => new DerivedPasswordField(id, HashAlgoConstraint.DerivedPasswordType.AIX)
          case LinuxDerivedPasswordVType => new DerivedPasswordField(id, HashAlgoConstraint.DerivedPasswordType.Linux)
          case _ => default(id)
        }
        case predefinedField: PredefinedValuesVariableSpec => new ReadOnlyTextField(id)

        case _ =>
          logger.error("Unexpected case : variable %s should not be displayed. Only select1, select or input can be displayed.".format(v.name))
          default(id)
      }
    }

    override def default(id: String) = new TextField(id, () => configService.rudder_featureSwitch_directiveScriptEngine().toBox)
  }

  private[this] lazy val section2FieldService: Section2FieldService = {
    new Section2FieldService(FieldFactoryImpl, Translator.defaultTranslators)
  }
  private[this] lazy val directiveEditorServiceImpl: DirectiveEditorService =
    new DirectiveEditorServiceImpl(configurationRepository, section2FieldService)

  private[this] lazy val reportDisplayerImpl = new ReportDisplayer(
      roLdapRuleRepository
    , roLdapDirectiveRepository
    , techniqueRepositoryImpl
    , configService
    , logDisplayerImpl
  )
  private[this] lazy val propertyRepository = new RudderPropertiesRepositoryImpl(doobie)
  private[this] lazy val autoReportLogger = new AutomaticReportLogger(
      propertyRepository
    , reportsRepositoryImpl
    , roLdapRuleRepository
    , roLdapDirectiveRepository
    , nodeInfoServiceImpl
    , RUDDER_BATCH_REPORTS_LOGINTERVAL )

  private[this] lazy val scriptLauncher = new DebugInfoServiceImpl

  ////////////////////// Snippet plugins & extension register //////////////////////
  lazy val snippetExtensionRegister: SnippetExtensionRegister = new SnippetExtensionRegisterImpl()

  private[this] lazy val cachedNodeConfigurationService: CachedNodeConfigurationService = {
    val cached = new CachedNodeConfigurationService(findExpectedRepo, nodeInfoServiceImpl)
    cached.init().runNow
    cached
  }

  /*
   * Agent runs: we use a cache for them.
   */
  private[this] lazy val cachedAgentRunRepository = {
    val roRepo = new RoReportsExecutionRepositoryImpl(doobie, new WoReportsExecutionRepositoryImpl(doobie), cachedNodeConfigurationService, pgIn, RUDDER_JDBC_BATCH_MAX_SIZE)
    new CachedReportsExecutionRepository(
        roRepo
    )
  }

  val updatesEntryJdbcRepository = new LastProcessedReportRepositoryImpl(doobie)

  val executionService = {
    val maxCatchupTime = {
      val temp = FiniteDuration(RUDDER_REPORTS_EXECUTION_MAX_DAYS.toLong, "day") + FiniteDuration(RUDDER_REPORTS_EXECUTION_MAX_MINUTES.toLong, "minutes")
        if (temp.toMillis == 0) {
          logger.error("'rudder.aggregateReports.maxDays' and 'rudder.aggregateReports.maxMinutes' properties are both 0 or empty. Set using 30 minutes as default value, please check /opt/rudder/etc/rudder-web.properties")
          FiniteDuration(30, "minutes")
        } else {
          temp
        }
    }
    val maxCatchupBatch = FiniteDuration(RUDDER_REPORTS_EXECUTION_MAX_SIZE.toLong, "minutes")


    new ReportsExecutionService(
      reportsRepository
    , updatesEntryJdbcRepository
    , recentChangesService
    , reportingServiceImpl
    , complianceRepositoryImpl
    , maxCatchupTime
    , maxCatchupBatch
    )
  }

  val aggregateReportScheduler = new FindNewReportsExecution(executionService,RUDDER_REPORTS_EXECUTION_INTERVAL)

  // This needs to be done at the end, to be sure that all is initialized
  deploymentService.setDynamicsGroupsService(dyngroupUpdaterBatch)

  // aggregate information about node count
  // don't forget to start-it once out of the zone which lead to dead-lock (ie: in Lift boot)
  val historizeNodeCountBatch = for {
    gitLogger  <- CommitLogServiceImpl.make(METRICS_NODES_DIRECTORY_GIT_ROOT)
    writer     <- WriteNodeCSV.make(METRICS_NODES_DIRECTORY_GIT_ROOT, ";", "yyyy-MM")
    service    =  new HistorizeNodeCountService(
                      new FetchDataServiceImpl(RudderConfig.nodeInfoService, RudderConfig.reportingService)
                    , writer
                    , gitLogger
                    , ZioRuntime.environment
                    , DateTimeZone.UTC // never change log line
                  )
    cron       <- Scheduler.make(METRICS_NODES_MIN_PERIOD, METRICS_NODES_MAX_PERIOD, s => service.scheduledLog(s)
                    , "Automatic recording of active nodes".succeed, ZioRuntime.environment
                  )
    _          <- ScheduledJobLoggerPure.metrics.info(
                    s"Starting node count historization batch (min:${METRICS_NODES_MIN_PERIOD.render}; max:${METRICS_NODES_MAX_PERIOD.render})"
                  )
    _          <- cron.start
  } yield ()

}
