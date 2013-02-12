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

import com.normation.ldap.ldif.{LDIFFileLogger,DefaultLDIFFileLogger}
import com.unboundid.ldap.sdk.{LDAPConnectionOptions,LDAPConnectionPool}
import net.liftweb.common._


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
trait LDAPConnectionProvider extends Loggable {
  import com.unboundid.ldap.sdk.{LDAPSearchException,LDAPException,SearchResult}

  protected def getInternalConnection() : LDAPConnection
  protected def releaseInternalConnection(con:LDAPConnection) : Unit
  protected def releaseDefuncInternalConnection(con:LDAPConnection) : Unit

  /**
   * Use the LDAP connection provider to execute a method whose
   * return type is Unit.
   *
   * Ex :
   * LDAPConnectionProvider { LDAPConnection =>
   *   val entries = LDAPConnection.searchOne("dc=example,dc=org")
   *   entries foreach { e =>
   *     println(e.dn.toString)
   *   }
   * }
   */
  def foreach(f: LDAPConnection => Unit) : Box[Nothing] = {
    withCon[Nothing] { con =>
      f(con)
      Empty
    }
  }

  /**
   * Use the LDAP connection provider to execute a method
   * on the provided connection object with a return type
   * DIFFERENT FROM Box[T]
   *
   * Ex: Retrieve the list of cn in a box, manage errors
   *
   * val names = {
   *   LDAPConnectionProvider { LDAPConnection =>
   *     LDAPConnection.searchOne("dc=example,dc=org").map(e => e.cn.getOrElse("no name"))
   *   } match {
   *     case f@Failure(_,_,_) => return f
   *     case Empty => Seq[String]()
   *     case Full(seq) => seq
   *   }
   * }
   */
  def map[A](f: LDAPConnection => A) : Box[A] = {
    withCon[A] { con =>
      Full(f(con))
    }
  }

  /**
   * Use the LDAP connection provider to execute a method
   * on the connection object with a return type
   * that is a box ; deals with exception management
   *
   * Ex: Get one entry (or a default one), manage errors
   *
   * val entry = {
   *   LDAPConnectionProvider { LDAPConnection =>
   *     LDAPConnection.get("dc=example,dc=org")
   *   } match {
   *     case f@Failure(_,_,_) => return f
   *     case Empty => defaultEntry
   *     case Full(e) => e
   *   }
   * }
   */
  def flatMap[A](f: LDAPConnection => Box[A]) : Box[A] = {
    withCon[A] { con =>
      f(con)
    }
  }

  /**
   * Cleanly close all the resources used by that
   * LDAP connection provider (open connection, pool, etc)
   */
  def close: Unit

  /**
   * A description of the LDAP connection provider, to
   * display in log message (for example).
   * Implementations: DO NOT provide sensitive information here.
   */
  override def toString : String = getInternalConnection.toString

  /**
   * Default internal implementation of the getConnection/apply
   * user method sequence with exception handling.
   * @param f
   *   the user method to execute
   * @return
   *   boxed version of the user method result, with exception
   *   transformed into Failure.
   */
  protected def withCon[A](f: LDAPConnection => Box[A]) : Box[A] = {
    val con = try {
       getInternalConnection
    } catch {
      case e:LDAPException => {
        logger.error("Can't get a new LDAP connection",e)
        return Failure("Can't get a new LDAP connection",Full(e),Empty)
      }
    }

    try {
      val res = f(con)
      releaseInternalConnection(con)
      res
    } catch {
      case e:LDAPException => {
        logger.error("Can't execute LDAP request",e)
        releaseDefuncInternalConnection(con)
        Failure("Can't execute LDAP request",Full(e),Empty)
      }
    }
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
  def newUnboundidConnection : UnboundidLDAPConnection
  def toConnectionString : String
}


trait AnonymousConnection extends UnboundidConnectionProvider {

  def host : String
  def port : Int
  def useSchemaInfos : Boolean

  override def newUnboundidConnection = {
    val options = new LDAPConnectionOptions
    options.setUseSchema(useSchemaInfos)
    new UnboundidLDAPConnection(options,host,port)
  }

  override def toConnectionString = "anonymous@ldap://%s:%s".format(host,port)

}

/**
 * Default implementation for UnboundidConnectionProvider:
 * use a simple login/password authentication to the server.
 */
trait SimpleAuthConnection extends UnboundidConnectionProvider {
  def authDn : String
  def authPw : String
  def host : String
  def port : Int
  def useSchemaInfos : Boolean

  override def newUnboundidConnection = {
    val options = new LDAPConnectionOptions
    options.setUseSchema(useSchemaInfos)
    new UnboundidLDAPConnection(options,host,port,authDn,authPw)
  }

  override def toConnectionString = "%s:*****@ldap://%s:%s".format(authDn,host,port)
}


/**
 * Implementation of a LDAPConnectionProvider which has only one
 * connection to the server (no pool).
 */
trait OneConnectionProvider extends LDAPConnectionProvider {
  self:UnboundidConnectionProvider =>

  def ldifFileLogger:LDIFFileLogger

  private var connection : Option[LDAPConnection] = None

  override def close : Unit = this.synchronized {
    connection.foreach( _.close() )
    connection = None
  }

  private def newConnection() = {
    new LDAPConnection(self.newUnboundidConnection,ldifFileLogger)
  }

  protected def getInternalConnection() = this.synchronized {
    connection match {
      case None => connection = Some(newConnection); connection.get
      case Some(con) => if(con.backed.isConnected) con else newConnection
    }
  }
  protected def releaseInternalConnection(con:LDAPConnection) : Unit = {}
  protected def releaseDefuncInternalConnection(con:LDAPConnection) : Unit = {}

}

/**
 * Implementation of a LDAPConnectionProvider which manage a
 * pool of connection to the server
 */
trait PooledConnectionProvider extends LDAPConnectionProvider {
  self:UnboundidConnectionProvider =>

  def poolSize : Int
  def ldifFileLogger:LDIFFileLogger

  private val pool = {
    new LDAPConnectionPool(self.newUnboundidConnection, poolSize)
  }

  override def close : Unit = pool.close

  protected def getInternalConnection() = new LDAPConnection(pool.getConnection,ldifFileLogger)
  protected def releaseInternalConnection(con:LDAPConnection) : Unit = {
    pool.releaseConnection(con.backed)
  }
  protected def releaseDefuncInternalConnection(con:LDAPConnection) : Unit = {
    pool.releaseDefunctConnection(con.backed)
  }

}


/**
 * Default implementation for a anonymous connection provider,
 * with no pool management.
 */
class AnonymousConnectionProvider(
  override val host : String = "localhost",
  override val port : Int = 389,
  override val ldifFileLogger:LDIFFileLogger = new DefaultLDIFFileLogger(),
  override val useSchemaInfos : Boolean = false
) extends AnonymousConnection with OneConnectionProvider

/**
 * Pooled implementation for an anonymous
 * connection provider
 */
class PooledAnonymousConnectionProvider(
  override val host : String = "localhost",
  override val port : Int = 389,
  override val ldifFileLogger:LDIFFileLogger = new DefaultLDIFFileLogger(),
  override val useSchemaInfos : Boolean = false,
  override val poolSize : Int = 2
) extends AnonymousConnection with PooledConnectionProvider

/**
 * Default implementation for a connection provider:
 * a simple login/pass connection, with no pool
 * management.
 */
class SimpleAuthConnectionProvider(
  override val authDn : String,
  override val authPw : String,
  override val host : String = "localhost",
  override val port : Int = 389,
  override val ldifFileLogger:LDIFFileLogger = new DefaultLDIFFileLogger(),
  override val useSchemaInfos : Boolean = false
) extends SimpleAuthConnection with OneConnectionProvider

/**
 * Pooled implementation for a connection provider
 * with a simple login/pass connection
 */
class PooledSimpleAuthConnectionProvider(
  override val authDn : String,
  override val authPw : String,
  override val host : String = "localhost",
  override val port : Int = 389,
  override val ldifFileLogger:LDIFFileLogger = new DefaultLDIFFileLogger(),
  override val useSchemaInfos : Boolean = false,
  override val poolSize : Int = 2
) extends SimpleAuthConnection with PooledConnectionProvider

