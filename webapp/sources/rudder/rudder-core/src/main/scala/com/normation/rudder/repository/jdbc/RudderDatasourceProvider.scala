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

package com.normation.rudder.repository.jdbc

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import java.io.Closeable
import javax.sql.DataSource
import net.liftweb.common.Loggable
import scala.concurrent.duration.Duration
import scala.concurrent.duration.DurationInt

/**
 * A wrapper around defaut data source provider to allow for several
 * databases connections, and still offer the multi-threading capabilities
 *
 * Switch to HikaryCP seems it seems to be the new kid in the bloc
 * http://blog.trustiv.co.uk/2014/06/battle-connection-pools
 */
class RudderDatasourceProvider(
    driver:               String,
    url:                  String,
    username:             String,
    password:             String,
    maxPoolSize:          Int,
    getConnectionTimeout: Duration = 250.millis
) extends Loggable {
  Class.forName(driver)

  val config = new HikariConfig()
  config.setJdbcUrl(url)

  config.setUsername(username)
  config.setPassword(password)
  config.setMaximumPoolSize(if (maxPoolSize < 1) 1 else maxPoolSize)
  config.setAutoCommit(false)

  config.setConnectionTimeout(getConnectionTimeout.toMillis) // in milliseconds

  // since we use JDBC4 driver, we MUST NOT set `setConnectionTestQuery("SELECT 1")`
  // more over, it causes problems, see: https://issues.rudder.io/issues/14789

  lazy val datasource: DataSource & Closeable = {
    try {

      val pool = new HikariDataSource(config)

      /* try to get the connection */
      val connection = pool.getConnection()
      connection.close()

      pool
    } catch {
      case e: Exception =>
        logger.error("Could not initialise the access to the database")
        throw e
    }
  }

}
