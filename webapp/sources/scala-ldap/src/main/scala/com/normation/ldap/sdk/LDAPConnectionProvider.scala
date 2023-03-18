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

package com.normation.ldap.sdk

import com.normation.errors._
import com.normation.ldap.ldif.DefaultLDIFFileLogger
import com.normation.ldap.ldif.LDIFFileLogger
import com.normation.ldap.sdk.LDAPIOResult._
import com.normation.ldap.sdk.syntax._
import com.normation.zio.ZioRuntime
import com.unboundid.ldap.sdk.LDAPConnectionOptions
import com.unboundid.ldap.sdk.LDAPConnectionPool
import com.unboundid.ldap.sdk.LDAPException
import zio._
import zio.syntax._

/**
 * A LDAP connection manager.
 * Implementations of that trait manage
 * TLS authentication, connection pools, etc
 *
 * It is aimed to be used in a "loan pattern" way:
 * connectionProvider {
 *  con =>
 *   val a = con.get(...)
 *  b
 * }
 *
 * Or in for expression loop:
 *
 * for {
 *   con <- connectionProvider
 *   entry <- con.get(...)
 * } yield entry
 *
 */
// a class to have an error coerced to LdapError
final class InternalConnection[LDAP <: RoLDAPConnection](provider: LDAPConnectionProvider[LDAP]) {

  def flatMap[A](f: LDAP => LDAPIOResult[A]): LDAPIOResult[A] = {
    provider.withConLdap[A](con => f(con))
  }
}

trait LDAPConnectionProvider[LDAP <: RoLDAPConnection] {

  protected def newConnection:         ZIO[Any, LDAPRudderError, LDAP]
  protected def getInternalConnection: ZIO[Any, LDAPRudderError, LDAP]
  protected def releaseInternalConnection(con:       LDAP): UIO[Unit]
  protected def releaseDefuncInternalConnection(con: LDAP): UIO[Unit]

  /**
   * Use the LDAP connection provider to execute a method whose
   * return type is Unit.
   */
  def foreach(f: LDAP => Unit): IOResult[Unit] = {
    withConLdap[Unit](con => LDAPIOResult.attempt(f(con)).unit)
  }

  /**
   * map on connection provider
   */
  def map[A](f: LDAP => A): LDAPIOResult[A] = {
    withConLdap[A](con => LDAPIOResult.attempt(f(con)))
  }

  /**
   * Use the LDAP connection provider to execute a method
   * on the connection object with a return type
   * that is a ZIO ; deals with exception management
   *
   */
  def flatMap[E <: RudderError, A](f: LDAP => IO[E, A]): IOResult[A] = {
    withCon[E, A](con => f(con))
  }

  val internal = new InternalConnection[LDAP](this)

  /**
   * Cleanly close all the resources used by that
   * LDAP connection provider (open connection, pool, etc)
   */
  def close: UIO[Unit]

  /**
   * A description of the LDAP connection provider, to
   * display in log message (for example).
   * Implementations: DO NOT provide sensitive information here.
   */
  override def toString: String = toConnectionString
  def toConnectionString: String

  /*
   * Default internal implementation of the getConnection/apply
   * user method sequence with exception handling.
   */
  protected[sdk] def withCon[E <: RudderError, A](f: LDAP => IO[E, A]): IOResult[A]     = {
    ZIO.acquireReleaseWith(getInternalConnection)(releaseInternalConnection)(f(_))
  }
  protected[sdk] def withConLdap[A](f: LDAP => LDAPIOResult[A]):        LDAPIOResult[A] = {
    ZIO.acquireReleaseWith(getInternalConnection)(releaseInternalConnection)(f(_))
  }
}

/**
 * A simple trait that gives access to new authenticated
 * UnboundID connection.
 * That trait only take care of connection creation, it
 * does not handle how they are use (and close).
 *
 */
trait UnboundidConnectionProvider {
  // for performance reason, this can't be wrapped in ZIO
  def newUnboundidConnection: UnboundidLDAPConnection
  def toConnectionString:     String
}

object RudderLDAPConnectionOptions {
  def apply(useSchemaInfos: Boolean): LDAPConnectionOptions = {
    val options = new LDAPConnectionOptions
    options.setUseSchema(useSchemaInfos)
    // In Rudder, some entries can grow quite big, see: https://www.rudder-project.org/redmine/issues/13256
    // so we need to change max entry size to a big value (default to max available entry).
    // We don't want to change the property if it is not the default one (for ex if a system property was used
    // to change it)
    if (options.getMaxMessageSize() == 20971520) {
      options.setMaxMessageSize(Int.MaxValue)
    }
    options
  }
}

trait AnonymousConnection extends UnboundidConnectionProvider {

  def host:           String
  def port:           Int
  def useSchemaInfos: Boolean

  override def newUnboundidConnection = {
    new UnboundidLDAPConnection(RudderLDAPConnectionOptions(useSchemaInfos), host, port)
  }

  override def toConnectionString = s"anonymous@ldap://${host}:${port}"
}

/**
 * Default implementation for UnboundidConnectionProvider:
 * use a simple login/password authentication to the server.
 */
trait SimpleAuthConnection extends UnboundidConnectionProvider {
  def authDn:         String
  def authPw:         String
  def host:           String
  def port:           Int
  def useSchemaInfos: Boolean

  override def newUnboundidConnection = {
    new UnboundidLDAPConnection(RudderLDAPConnectionOptions(useSchemaInfos), host, port, authDn, authPw)
  }

  override def toConnectionString = s"$authDn:*****@ldap://${host}:${port}"
}

/**
 * Implementation of a LDAPConnectionProvider which has only one
 * connection to the server (no pool).
 */
trait OneConnectionProvider[LDAP <: RoLDAPConnection] extends LDAPConnectionProvider[LDAP] {
  self: UnboundidConnectionProvider =>

  def semaphore:      Semaphore
  def ldifFileLogger: LDIFFileLogger

  def connection: Ref[Option[LDAP]]

  override def close: UIO[Unit] = {
    semaphore.withPermit(for {
      c <- connection.get
      _ <- c.fold(ZIO.unit)(c => ZIO.succeed(c.close()))
      _ <- connection.set(None)
    } yield ())
  }

  protected def getInternalConnection:                               LDAPIOResult[LDAP] = {
    semaphore.withPermit(for {
      c <- connection.get
      n <- (c match {
             case None      => newConnection
             case Some(con) =>
               if (con.backed.isConnected) {
                 con.succeed
               } else {
                 releaseInternalConnection(con) *> newConnection
               }
           })
      _ <- connection.set(Some(n))
    } yield {
      n
    })
  }
  override protected def releaseInternalConnection(con: LDAP):       UIO[Unit]          = ZIO.unit
  override protected def releaseDefuncInternalConnection(con: LDAP): UIO[Unit]          = ZIO.unit
}

/**
 * Implementation of a LDAPConnectionProvider which manage a
 * pool of connection to the server
 */
trait PooledConnectionProvider[LDAP <: RoLDAPConnection] extends LDAPConnectionProvider[LDAP] {
  self: UnboundidConnectionProvider =>

  def poolSize:       Int
  def ldifFileLogger: LDIFFileLogger
  def poolname:       String

  // for performance reason, operation on pool can't be wrapped into ZIO
  protected lazy val pool = {
    try {
      val p = new LDAPConnectionPool(self.newUnboundidConnection, poolSize)
      p.setConnectionPoolName(poolname)
      p
    } catch {
      case ex: LDAPException =>
        val msg = s"Error during LDAP connection pool initialisation. Exception: " +
          s"${ex.getClass.getSimpleName}:${ex.getMessage}; Details: ${ex.getDiagnosticMessage}"
        LDAPConnectionLogger.error(msg)
        throw new Error(msg)
    }
  }

  override def close: UIO[Unit] = ZIO.succeed(pool.close)
  protected def getInternalConnection = newConnection
  protected def releaseInternalConnection(con: LDAP):       UIO[Unit] = {
    ZIO.succeed(pool.releaseConnection(con.backed))
  }
  protected def releaseDefuncInternalConnection(con: LDAP): UIO[Unit] = {
    ZIO.succeed(pool.releaseDefunctConnection(con.backed))
  }

}

/**
 * Default implementation for a anonymous connection provider,
 * with no pool management.
 */
class ROAnonymousConnectionProvider(
    override val host:           String = "localhost",
    override val port:           Int = 389,
    override val ldifFileLogger: LDIFFileLogger = new DefaultLDIFFileLogger(),
    override val useSchemaInfos: Boolean = false
) extends AnonymousConnection with OneConnectionProvider[RoLDAPConnection] {
  override val semaphore  = ZioRuntime.unsafeRun(Semaphore.make(1))
  override val connection = ZioRuntime.unsafeRun(Ref.make(Option.empty[RoLDAPConnection]))

  def newConnection = {
    LDAPIOResult.attempt(new RoLDAPConnection(newUnboundidConnection, ldifFileLogger))
  }
}

/**
 * Default implementation for a anonymous connection provider,
 * with no pool management.
 */
class RWAnonymousConnectionProvider(
    override val host:           String = "localhost",
    override val port:           Int = 389,
    override val ldifFileLogger: LDIFFileLogger = new DefaultLDIFFileLogger(),
    override val useSchemaInfos: Boolean = false
) extends AnonymousConnection with OneConnectionProvider[RwLDAPConnection] {
  override def semaphore  = ZioRuntime.unsafeRun(Semaphore.make(1))
  override val connection = ZioRuntime.unsafeRun(Ref.make(Option.empty[RwLDAPConnection]))

  def newConnection = {
    LDAPIOResult.attempt(new RwLDAPConnection(newUnboundidConnection, ldifFileLogger))
  }
}

/**
 * Pooled implementation for an anonymous
 * connection provider
 */
class ROPooledAnonymousConnectionProvider(
    override val host:           String = "localhost",
    override val port:           Int = 389,
    override val ldifFileLogger: LDIFFileLogger = new DefaultLDIFFileLogger(),
    override val useSchemaInfos: Boolean = false,
    override val poolSize:       Int = 2
) extends AnonymousConnection with PooledConnectionProvider[RoLDAPConnection] {
  override def poolname: String = s"rudder-anonymous-ro"
  def newConnection = {
    LDAPIOResult.attempt(new RoLDAPConnection(pool.getConnection, ldifFileLogger))
  }
}

/**
 * Pooled implementation for an anonymous
 * connection provider
 */
class RWPooledAnonymousConnectionProvider(
    override val host:           String = "localhost",
    override val port:           Int = 389,
    override val ldifFileLogger: LDIFFileLogger = new DefaultLDIFFileLogger(),
    override val useSchemaInfos: Boolean = false,
    override val poolSize:       Int = 2
) extends AnonymousConnection with PooledConnectionProvider[RwLDAPConnection] {
  override def poolname: String = s"rudder-anonymous-rw"
  def newConnection = {
    LDAPIOResult.attempt(new RwLDAPConnection(pool.getConnection, ldifFileLogger))
  }
}

/**
 * Default implementation for a connection provider:
 * a simple login/pass connection, with no pool
 * management.
 */
class ROSimpleAuthConnectionProvider(
    override val authDn:         String,
    override val authPw:         String,
    override val host:           String = "localhost",
    override val port:           Int = 389,
    override val ldifFileLogger: LDIFFileLogger = new DefaultLDIFFileLogger(),
    override val useSchemaInfos: Boolean = false
) extends SimpleAuthConnection with OneConnectionProvider[RoLDAPConnection] {
  override val semaphore  = ZioRuntime.unsafeRun(Semaphore.make(1))
  override val connection = ZioRuntime.unsafeRun(Ref.make(Option.empty[RoLDAPConnection]))

  def newConnection = {
    LDAPIOResult.attempt(new RoLDAPConnection(newUnboundidConnection, ldifFileLogger))
  }
}

/**
 * Default implementation for a connection provider:
 * a simple login/pass connection, with no pool
 * management.
 */
class RWSimpleAuthConnectionProvider(
    override val authDn:         String,
    override val authPw:         String,
    override val host:           String = "localhost",
    override val port:           Int = 389,
    override val ldifFileLogger: LDIFFileLogger = new DefaultLDIFFileLogger(),
    override val useSchemaInfos: Boolean = false
) extends SimpleAuthConnection with OneConnectionProvider[RwLDAPConnection] {
  override val semaphore  = ZioRuntime.unsafeRun(Semaphore.make(1))
  override val connection = ZioRuntime.unsafeRun(Ref.make(Option.empty[RwLDAPConnection]))

  def newConnection = {
    LDAPIOResult.attempt(new RwLDAPConnection(newUnboundidConnection, ldifFileLogger))
  }
}

/**
 * Pooled implementation for a connection provider
 * with a simple login/pass connection
 */
class ROPooledSimpleAuthConnectionProvider(
    override val authDn:         String,
    override val authPw:         String,
    override val host:           String = "localhost",
    override val port:           Int = 389,
    override val ldifFileLogger: LDIFFileLogger = new DefaultLDIFFileLogger(),
    override val useSchemaInfos: Boolean = false,
    override val poolSize:       Int = 2
) extends SimpleAuthConnection with PooledConnectionProvider[RoLDAPConnection] {
  override def poolname: String = s"rudder-authenticated-ro"
  def newConnection = {
    LDAPIOResult.attempt(new RoLDAPConnection(pool.getConnection, ldifFileLogger))
  }
}

/**
 * Pooled implementation for a connection provider
 * with a simple login/pass connection
 */
class RWPooledSimpleAuthConnectionProvider(
    override val authDn:         String,
    override val authPw:         String,
    override val host:           String = "localhost",
    override val port:           Int = 389,
    override val ldifFileLogger: LDIFFileLogger = new DefaultLDIFFileLogger(),
    override val useSchemaInfos: Boolean = false,
    override val poolSize:       Int = 2
) extends SimpleAuthConnection with PooledConnectionProvider[RwLDAPConnection] {

  override def poolname: String = s"rudder-authenticated-rw"

  def newConnection = {
    LDAPIOResult.attempt(new RwLDAPConnection(pool.getConnection, ldifFileLogger))
  }
}
