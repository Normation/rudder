/*
 *************************************************************************************
 * Copyright 2025 Normation SAS
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

package bootstrap.liftweb.checks.endconfig.migration

import better.files.File
import org.junit.runner.*
import org.specs2.matcher.ContentMatchers
import org.specs2.mutable.*
import org.specs2.runner.*
import org.specs2.specification.AfterAll
import scala.xml.PrettyPrinter

/**
 * A simple test class to check that the demo data file is up to date
 * with the schema (there may still be a desynchronization if both
 * demo-data, test data and test schema for UnboundID are not synchronized
 * with OpenLDAP Schema).
 */
@RunWith(classOf[JUnitRunner])
class TestMigrateLogbackXml extends Specification with ContentMatchers with AfterAll {

  val xml    = {
    <configuration scan="true" scanPeriod="5 seconds">
      <appender name="STDOUT" class="ch.qos.logback.core.ConsoleAppender">
        <encoder class="ch.qos.logback.classic.encoder.PatternLayoutEncoder">
          <Pattern>%d{{[yyyy-MM-dd HH:mm:ssZ]}} %-5level %logger - %msg%n%ex{{full}}</Pattern>
        </encoder>
      </appender>
      <!-- test -->
      <property name="APILOG_DIR" value="/var/log/rudder/api"/>

      <property name="OPSLOG_DIR" value="/var/log/rudder/core"/>

      <property name="REPORT_DIR" value="/var/log/rudder/compliance"/>

      <!--
    A file log appender for exploitation logs about Rest API Calls
   -->
      <appender name="APILOG" class="ch.qos.logback.core.rolling.RollingFileAppender">
        <file>${{APILOG_DIR}}/api.log</file>
        <append>true</append>

        <rollingPolicy class="ch.qos.logback.core.rolling.TimeBasedRollingPolicy">
          <fileNamePattern>${{OPSLOG_DIR}}/api-%d{{yyyy-MM-dd}}.log.gz</fileNamePattern>
          <maxHistory>30</maxHistory>
        </rollingPolicy>

        <encoder>
          <pattern>%d{{MMM dd HH:mm:ss}} ${{HOSTNAME}} rudder[%logger]: [%level] %msg%n</pattern>
    </encoder>
    </appender>

    <!--
    A file log appender for exploitation logs about reports, outputing in a file
    The message format will be looking like syslog message:
    Jun 27 13:02:53 orchestrateur-3 rudder[report]: [warn] here come the message

    'report' is expected to be the ops logger name.
   -->
      <appender name="OPSLOG" class="ch.qos.logback.core.rolling.RollingFileAppender">
        <file>${{OPSLOG_DIR}}/rudder-webapp.log</file>
        <append>true</append>
        <rollingPolicy class="ch.qos.logback.core.rolling.TimeBasedRollingPolicy">
          <fileNamePattern>${{OPSLOG_DIR}}/rudder-webapp-%d{{yyyy-MM-dd}}.log.gz</fileNamePattern>
          <maxHistory>30</maxHistory>
        </rollingPolicy>
        <encoder>
          <pattern>%d{{MMM dd HH:mm:ss}} ${{HOSTNAME}} rudder[%logger]: [%level] %msg%n</pattern>
    </encoder>
    </appender>
      <root level="info">
        <appender-ref ref="STDOUT"/>
      </root>

      <logger name="policy.generation" level="info" additivity="false">
        <appender-ref ref="OPSLOG"/>
        <appender-ref ref="STDOUT"/>
      </logger>

      <logger name="policy.generation.expected_reports" level="info" additivity="false">
        <appender-ref ref="OPSLOG"/>
        <appender-ref ref="STDOUT"/>
      </logger>
    </configuration>
  }
  val result = {
    <configuration scan="true" scanPeriod="5 seconds">
    <property name="WEBAPP_DIR" value="/var/log/rudder/webapp"/>
    <property name="APILOG_DIR" value="${WEBAPP_DIR}/api"/>
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
    <!-- test -->
        <property name="REPORT_DIR" value="/var/log/rudder/compliance"/>
    <!--
    A file log appender for exploitation logs about Rest API Calls
   -->
    <!--
    A file log appender for exploitation logs about reports, outputing in a file
    The message format will be looking like syslog message:
    Jun 27 13:02:53 orchestrateur-3 rudder[report]: [warn] here come the message

    'report' is expected to be the ops logger name.
   -->
      <root level="info">
        <appender-ref ref="STDOUT"/>
      </root>
      <logger name="policy.generation" level="info" additivity="false">
        <appender-ref ref="OPSLOG"/>
        <appender-ref ref="STDOUT"/>
      </logger>
      <logger
      name="policy.generation.expected_reports" level="info" additivity="false">
        <appender-ref ref="OPSLOG"/>
        <appender-ref ref="STDOUT"/>
      </logger>
    </configuration>
  }

  val migration     = new FixedPathLoggerMigration()
  val prettyPrinter = new PrettyPrinter(120, 4)

  val srcDir: File = File("src/test/resources/logback-samples")

  val tmpDir = File("/tmp/logback-samples")
  srcDir.copyTo(tmpDir)

  val LOGBACK_CONFIGURATION_FILE = "logback.configurationFile"

  override def afterAll(): Unit = tmpDir.delete()

  sequential

  "Properly migrate xml logback file" >> {
    prettyPrinter.formatNodes(migration.migrateLogback(xml)) must beEqualTo(prettyPrinter.format(result))
  }

  "Properly migrate 8.2 logbackFile" >> {

    val logback8_2_migrated = tmpDir / "logback-8.2-migrated.xml"
    val previous_content    = logback8_2_migrated.contentAsString
    val logback8_2          = tmpDir / "logback-8.2.xml"
    System.setProperty(LOGBACK_CONFIGURATION_FILE, logback8_2.pathAsString)

    migration.checks()

    val content = logback8_2.contentAsString

    content must beEqualTo(previous_content)
  }
  "may be ran twice without breaking the logback.xml file" >> {

    val logback8_2_migrated = tmpDir / "logback-8.2-migrated.xml"
    val previous_content    = logback8_2_migrated.contentAsString
    val logback8_2          = tmpDir / "logback-8.2.xml"
    System.setProperty(LOGBACK_CONFIGURATION_FILE, logback8_2.pathAsString)
    migration.checks()
    migration.checks()

    val content = logback8_2.contentAsString

    content must beEqualTo(previous_content)
  }

}
