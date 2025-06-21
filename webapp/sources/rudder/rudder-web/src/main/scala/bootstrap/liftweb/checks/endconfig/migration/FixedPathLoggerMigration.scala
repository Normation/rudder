package bootstrap.liftweb.checks.endconfig.migration

import bootstrap.liftweb.BootstrapChecks
import bootstrap.liftweb.BootstrapLogger
import com.normation.utils.DateFormaterService
import org.joda.time.DateTime
import org.joda.time.DateTimeZone
import org.springframework.core.io.ClassPathResource
import scala.xml.Elem
import scala.xml.Node as XmlNode
import scala.xml.NodeSeq
import scala.xml.PrettyPrinter
import scala.xml.parsing.ConstructingParser

class FixedPathLoggerMigration extends BootstrapChecks {

  override def description: String = "Ensure Rudder webapp logs are written in a fixed log file"

  // Remove APILOG_DIR (which should be updated) and OPSLOG_DIR (which should be removed)
  def removeProperties(xml: NodeSeq): NodeSeq = {
    xml.map {
      case elem: Elem =>
        elem.copy(child = {
          elem.child.filterNot(node => {
            node.label == "property" && node
              .attribute("name")
              .exists(_.exists(n => n.text == "APILOG_DIR" || n.text == "OPSLOG_DIR"))
          })
        })
      case n => n
    }
  }

  // Remove STDOUT appender and APILOG which should be update, and remove OPSLOG
  def removeStdoutAppender(xml: NodeSeq): NodeSeq = {
    xml.map {
      case elem: Elem =>
        elem.copy(child = {
          elem.child.filterNot(node => {
            node.label == "appender" && node
              .attribute("name")
              .exists(_.exists(n => n.text == "STDOUT" || n.text == "OPSLOG" || n.text == "APILOG"))
          })
        })
      case n => n
    }
  }

  // Add new WEBAPP_DIR property and update APILOG_DIR property
  def addProperties(xml: NodeSeq) = {
    val appenderElem = {
      <property name="WEBAPP_DIR" value="/var/log/rudder/webapp" /> ::
      <property name="APILOG_DIR" value="${WEBAPP_DIR}/api" /> ::
      Nil
    }
    xml.map {
      case elem: Elem => elem.copy(child = appenderElem ::: elem.child.toList)
      case n => n
    }
  }

  // Add new APILOG appender (migrate rolling policy)
  def addApiAppender(xml: NodeSeq) = {
    val appenderElem: XmlNode = {
      <appender name="APILOG" class="ch.qos.logback.core.rolling.RollingFileAppender">
          <file>${{APILOG_DIR}}/api.log</file>
          <append>true</append>

          <rollingPolicy class="ch.qos.logback.core.rolling.TimeBasedRollingPolicy">
            <fileNamePattern>${{APILOG_DIR}}/api-%d{{yyyy-MM-dd}}.log.gz</fileNamePattern>
            <maxHistory>30</maxHistory>
          </rollingPolicy>

          <encoder>
            <pattern>%d{{MMM dd HH:mm:ss}} ${{HOSTNAME}} rudder[%logger]: [%level] %msg%n</pattern>
      </encoder>
      </appender>
    }
    xml.map {
      case elem: Elem => elem.copy(child = appenderElem :: elem.child.toList)
      case n => n
    }
  }

  // Add new conditional STDOUT appender
  def addAppender(xml: NodeSeq) = {
    val appenderElem: XmlNode = {
      <if condition='property("run.mode").equalsIgnoreCase("production")'>
        <then>
          <appender name="STDOUT" class="ch.qos.logback.core.rolling.RollingFileAppender">
            <file>${{WEBAPP_DIR}}/webapp.log</file>
            <append>true</append>
            <rollingPolicy class="ch.qos.logback.core.rolling.TimeBasedRollingPolicy">
              <fileNamePattern>${{WEBAPP_DIR}}/webapp-%d{{yyyy-MM-dd}}.log.gz</fileNamePattern>
              <maxHistory>30</maxHistory>
            </rollingPolicy>
            <encoder>
              <pattern>%d{{[yyyy-MM-dd HH:mm:ssZ]}} %-5level %logger - %msg%n%ex{{full}}</pattern>
            </encoder>
          </appender>
        </then>
        <else>
          <appender name="STDOUT" class="ch.qos.logback.core.ConsoleAppender">
            <encoder class="ch.qos.logback.classic.encoder.PatternLayoutEncoder">
              <Pattern>%d{{[yyyy-MM-dd HH:mm:ssZ]}} %-5level %logger - %msg%n%ex{{full}}</Pattern>
            </encoder>
          </appender>
        </else>
      </if>

    }

    xml.map {
      case elem: Elem => elem.copy(child = appenderElem :: elem.child.toList)
      case n => n
    }
  }

  def migrateLogback(xml: NodeSeq) = {
    addProperties(addApiAppender(addAppender(removeProperties(removeStdoutAppender(xml)))))
  }

  override def checks(): Unit = {

    val LOGBACK_CONFIGURATION_FILE = "logback.configurationFile"

    val logbackPath = System.getProperty(LOGBACK_CONFIGURATION_FILE) match {
      case null | "" =>
        val path = new ClassPathResource("logback.xml").getURL
        path
      case s         => better.files.File(s).url
    }
    val logbackFile = better.files.File(logbackPath)
    val xml         = ConstructingParser.fromFile(logbackFile.toJava, true).document()

    val shouldMigrateLogback = xml \\ "if" match {
      case NodeSeq.Empty =>
        true
      case nodes         =>
        nodes \\ "appender" match {
          case NodeSeq.Empty =>
            true
          case appenders     =>
            appenders.find(_.attribute("name").exists(_.exists(_.text == "STDOUT"))).isEmpty
        }
    }

    if (shouldMigrateLogback) {
      val newLogback = migrateLogback(xml)
      val backPath   = logbackFile.parent / s"logback.xml-backup-${DateFormaterService.serialize(DateTime.now(DateTimeZone.UTC))}"

      // In case file already exists
      logbackFile.copyTo(backPath, overwrite = true)

      BootstrapLogger.logEffect.info(
        s"Migrating logback.xml to new format. A backup of previous file is available at ${backPath.pathAsString}"
      )

      val prettyPrinter = new PrettyPrinter(120, 4)

      logbackFile.write(prettyPrinter.formatNodes(newLogback))
    }
  }
}
