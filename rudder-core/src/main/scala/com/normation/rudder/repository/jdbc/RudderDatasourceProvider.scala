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

package com.normation.rudder.repository.jdbc


import javax.sql.DataSource
import net.liftweb.common.Loggable
import java.io.Closeable
import com.jolbox.bonecp.BoneCPConfig
import com.jolbox.bonecp.BoneCP
import com.jolbox.bonecp.BoneCPDataSource

/**
 * A wrapper around defaut data source provider to allow for several
 * databases connections, and still offer the multi-threading capabilities
 *
 * Switch to HikaryCP seems it seems to be the new kid in the bloc
 * http://blog.trustiv.co.uk/2014/06/battle-connection-pools
 */
class RudderDatasourceProvider(
    driver     : String
  , url        : String
  , username   : String
  , password   : String
  , maxPoolSize: Int
) extends Loggable {
  Class.forName(driver)

  val minPoolSize = 1
  val config = new BoneCPConfig()
  config.setJdbcUrl(url)

  config.setUsername(username)
  config.setPassword(password)
  config.setMinConnectionsPerPartition(minPoolSize)
  config.setMaxConnectionsPerPartition(if(maxPoolSize < minPoolSize) minPoolSize else maxPoolSize)
  config.setPartitionCount(1)


  //set parameters to test for dead connection
  //not sure we need that, since we use JDBC4 driver, see:
  //https://github.com/brettwooldridge/HikariCP => in page, text "connectionTestQuery"
  config.setConnectionTestStatement("SELECT 1")


  lazy val datasource: DataSource with Closeable = try {

    val pool = new BoneCPDataSource(config)

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