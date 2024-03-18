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

import com.normation.ldap.ldif.*
import com.normation.ldap.sdk.*
import com.normation.ldap.sdk.syntax.UnboundidLDAPConnection
import com.normation.zio.*
import com.unboundid.ldap.listener.InMemoryDirectoryServer
import com.unboundid.ldap.listener.InMemoryDirectoryServerConfig
import com.unboundid.ldap.sdk.schema.Schema
import zio.*

/**
 * A class that provides a connection provider which use an
 * UnboundID in memory directory server.
 * The directory instance is created and started with the class instantiation.
 */
class InMemoryDsConnectionProvider[CON <: RoLDAPConnection](
    // config to use for that server
    config:             InMemoryDirectoryServerConfig, // an optional list of path to LDIF files to load
    // for example for bootstrap datas

    bootstrapLDIFPaths: Seq[String] = Seq(),
    val ldifFileLogger: LDIFFileLogger = new DummyLDIFFileLogger()
) extends LDAPConnectionProvider[CON] with OneConnectionProvider[CON] with UnboundidConnectionProvider {

  /**
   * The actual In Memory server on which the connection
   * will lead.
   * It is exposed (public) to allow access to intersting
   * methods, like all the "assertEntry..." methods
   */
  val server = new InMemoryDirectoryServer(config)
  bootstrapLDIFPaths foreach { path => server.importFromLDIF(false, path) }
  server.startListening

  override def toConnectionString: String           = "in-memory-ldap-connection"
  override def semaphore:          Semaphore        = ZioRuntime.unsafeRun(Semaphore.make(1))
  override val connection:         Ref[Option[CON]] = ZioRuntime.unsafeRun(Ref.make(Option.empty[CON]))

  override def newUnboundidConnection: UnboundidLDAPConnection = server.getConnection

  def newConnection: ZIO[Any, LDAPRudderError, CON] = {
    LDAPIOResult.attempt(new RwLDAPConnection(newUnboundidConnection, ldifFileLogger).asInstanceOf[CON])
  }
}

object InMemoryDsConnectionProvider {

  def apply[CON <: RoLDAPConnection](
      baseDNs: Seq[String], // A list of schema to use

      schemaLDIFPaths:    Seq[String] = Seq(),
      bootstrapLDIFPaths: Seq[String] = Seq(),
      ldifFileLogger:     LDIFFileLogger = new DefaultLDIFFileLogger()
  ): InMemoryDsConnectionProvider[CON] = {
    /*
     * The configuration only allows one schema file. Just concatenate them all
     */

    val schema = Schema.getSchema(schemaLDIFPaths*)
    val config = new InMemoryDirectoryServerConfig(baseDNs*)
    config.setSchema(schema)
    new InMemoryDsConnectionProvider[CON](config, bootstrapLDIFPaths, ldifFileLogger)
  }

}
