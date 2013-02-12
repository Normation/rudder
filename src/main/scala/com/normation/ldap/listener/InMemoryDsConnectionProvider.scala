/*
*************************************************************************************
* Copyright 2011 Normation SAS
*************************************************************************************
*
* Licensed under the Apache License, Version 2.0 (the "License");
* you may not use this file except in compliance with the License.
* You may obtain a copy of the License at
*
* http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.
*
*************************************************************************************
*/

package com.normation.ldap.listener

import com.unboundid.ldap.listener.{InMemoryDirectoryServerConfig,InMemoryDirectoryServer}
import com.unboundid.ldap.sdk.schema.Schema
import com.normation.ldap.ldif._
import com.normation.ldap.sdk._

/**
 * A class that provides a connection provider which use an
 * UnboundID in memory directory server.
 * The directory instance is created and started with the class instantiation.
 */
class InMemoryDsConnectionProvider(
    //config to use for that server
    config: InMemoryDirectoryServerConfig
    //an optional list of path to LDIF files to load
    //for example for bootstrap datas
  , bootstrapLDIFPaths : Seq[String] = Seq()
  , val ldifFileLogger:LDIFFileLogger = new DefaultLDIFFileLogger()
) extends LDAPConnectionProvider {

  /**
   * The actual In Memory server on which the connection
   * will lead.
   * It is exposed (public) to allow access to intersting
   * methods, like all the "assertEntry..." methods
   */
  val server = new InMemoryDirectoryServer(config)
  bootstrapLDIFPaths foreach { path => server.importFromLDIF(false, path) }
  server.startListening


  //////// implementation of LDAPConnectionProvider ////////
  private[this] var connection : Option[LDAPConnection] = None
  protected def getInternalConnection() : LDAPConnection = {
    def reset : LDAPConnection = {
      val con = new LDAPConnection(server.getConnection, ldifFileLogger)
      connection = Some(con)
      con
    }
    connection match {
      case None => reset
      case Some(con) => if(con.backed.isConnected) con else reset
    }
  }

  protected def releaseInternalConnection(con:LDAPConnection) : Unit = close
  protected def releaseDefuncInternalConnection(con:LDAPConnection) : Unit = close
  override def close : Unit = connection.foreach  { con => con.close() }

}

object InMemoryDsConnectionProvider {

  def apply(
      baseDNs:Seq[String]
      //A list of schema to use
    , schemaLDIFPaths : Seq[String] = Seq()
    , bootstrapLDIFPaths : Seq[String] = Seq()
    , ldifFileLogger:LDIFFileLogger = new DefaultLDIFFileLogger()
  ) = {
    /*
     * The configuration only allows one schema file. Just concatenate them all
     */

    val schema = Schema.getSchema(schemaLDIFPaths:_*)
    val config = new InMemoryDirectoryServerConfig(baseDNs:_*)
    config.setSchema(schema)
    new InMemoryDsConnectionProvider(config,bootstrapLDIFPaths,ldifFileLogger)
  }



}