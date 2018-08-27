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
trait LDAPConnectionProvider[LDAP <: RoLDAPConnection] extends Loggable {
  import com.unboundid.ldap.sdk.{LDAPSearchException,LDAPException,SearchResult}

  protected def getInternalConnection() : LDAP
  protected def releaseInternalConnection(con:LDAP) : Unit
  protected def releaseDefuncInternalConnection(con:LDAP) : Unit
  protected def newConnection : LDAP

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
  def foreach(f: LDAP => Unit) : Box[Nothing] = {
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
  def map[A](f: LDAP => A) : Box[A] = {
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
  def flatMap[A](f: LDAP => Box[A]) : Box[A] = {
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
  protected def withCon[A](f: LDAP => Box[A]) : Box[A] = {
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

object RudderLDAPConnectionOptions {
  def apply(useSchemaInfos : Boolean): LDAPConnectionOptions = {
    val options = new LDAPConnectionOptions
    options.setUseSchema(useSchemaInfos)
    // In Rudder, some entries can grow quite big, see: https://www.rudder-project.org/redmine/issues/13256
    // so we need to change max entry size to a big value (default to max available entry).
    // We don't want to change the property if it is not the default one (for ex if a system property was used
    // to change it)
    if(options.getMaxMessageSize() == 20971520) {
      options.setMaxMessageSize(Int.MaxValue)
    }
    options
  }
}

trait AnonymousConnection extends UnboundidConnectionProvider {

  def host : String
  def port : Int
  def useSchemaInfos : Boolean

  override def newUnboundidConnection = {
    new UnboundidLDAPConnection(RudderLDAPConnectionOptions(useSchemaInfos),host,port)
  }

  override def toConnectionString = s"anonymous@ldap://${host}:${port}"

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
    new UnboundidLDAPConnection(RudderLDAPConnectionOptions(useSchemaInfos),host,port,authDn,authPw)
  }

  override def toConnectionString = s"$authDn:*****@ldap://${host}:${port}"
}


/**
 * Implementation of a LDAPConnectionProvider which has only one
 * connection to the server (no pool).
 */
trait OneConnectionProvider[LDAP <: RoLDAPConnection] extends LDAPConnectionProvider[LDAP] {
  self:UnboundidConnectionProvider =>

  def ldifFileLogger:LDIFFileLogger

  private var connection : Option[LDAP] = None

  override def close : Unit = this.synchronized {
    connection.foreach( _.close() )
    connection = None
  }

//  private def newConnection() = {
//    new LDAPConnection(self.newUnboundidConnection,ldifFileLogger)
//  }

  protected def getInternalConnection() = this.synchronized {
    connection match {
      case None => connection = Some(newConnection); connection.get
      case Some(con) => if(con.backed.isConnected) con else {
        releaseInternalConnection(con)
        newConnection
      }
    }
  }
  protected def releaseInternalConnection(con:LDAP) : Unit = {}
  protected def releaseDefuncInternalConnection(con:LDAP) : Unit = {}

}

/**
 * Implementation of a LDAPConnectionProvider which manage a
 * pool of connection to the server
 */
trait PooledConnectionProvider[LDAP <: RoLDAPConnection] extends LDAPConnectionProvider[LDAP] {
  self:UnboundidConnectionProvider =>

  def poolSize : Int
  def ldifFileLogger:LDIFFileLogger

  protected val pool = {
    new LDAPConnectionPool(self.newUnboundidConnection, poolSize)
  }

  override def close : Unit = pool.close

  protected def getInternalConnection() = newConnection //new LDAPConnection(pool.getConnection,ldifFileLogger)
  protected def releaseInternalConnection(con:LDAP) : Unit = {
    pool.releaseConnection(con.backed)
  }
  protected def releaseDefuncInternalConnection(con:LDAP) : Unit = {
    pool.releaseDefunctConnection(con.backed)
  }

}


/**
 * Default implementation for a anonymous connection provider,
 * with no pool management.
 */
class ROAnonymousConnectionProvider(
  override val host : String = "localhost",
  override val port : Int = 389,
  override val ldifFileLogger:LDIFFileLogger = new DefaultLDIFFileLogger(),
  override val useSchemaInfos : Boolean = false
) extends AnonymousConnection with OneConnectionProvider[RoLDAPConnection] {
  def newConnection = new RoLDAPConnection(newUnboundidConnection,ldifFileLogger)
}

/**
 * Default implementation for a anonymous connection provider,
 * with no pool management.
 */
class RWAnonymousConnectionProvider(
  override val host : String = "localhost",
  override val port : Int = 389,
  override val ldifFileLogger:LDIFFileLogger = new DefaultLDIFFileLogger(),
  override val useSchemaInfos : Boolean = false
) extends AnonymousConnection with OneConnectionProvider[RwLDAPConnection] {
  def newConnection = new RwLDAPConnection(newUnboundidConnection,ldifFileLogger)
}


/**
 * Pooled implementation for an anonymous
 * connection provider
 */
class ROPooledAnonymousConnectionProvider(
  override val host : String = "localhost",
  override val port : Int = 389,
  override val ldifFileLogger:LDIFFileLogger = new DefaultLDIFFileLogger(),
  override val useSchemaInfos : Boolean = false,
  override val poolSize : Int = 2
) extends AnonymousConnection with PooledConnectionProvider[RoLDAPConnection] {
  def newConnection = new RoLDAPConnection(pool.getConnection,ldifFileLogger)
}

/**
 * Pooled implementation for an anonymous
 * connection provider
 */
class RWPooledAnonymousConnectionProvider(
  override val host : String = "localhost",
  override val port : Int = 389,
  override val ldifFileLogger:LDIFFileLogger = new DefaultLDIFFileLogger(),
  override val useSchemaInfos : Boolean = false,
  override val poolSize : Int = 2
) extends AnonymousConnection with PooledConnectionProvider[RwLDAPConnection] {
  def newConnection = new RwLDAPConnection(pool.getConnection,ldifFileLogger)
}

/**
 * Default implementation for a connection provider:
 * a simple login/pass connection, with no pool
 * management.
 */
class ROSimpleAuthConnectionProvider(
  override val authDn : String,
  override val authPw : String,
  override val host : String = "localhost",
  override val port : Int = 389,
  override val ldifFileLogger:LDIFFileLogger = new DefaultLDIFFileLogger(),
  override val useSchemaInfos : Boolean = false
) extends SimpleAuthConnection with OneConnectionProvider[RoLDAPConnection] {
  def newConnection = new RoLDAPConnection(newUnboundidConnection,ldifFileLogger)
}

/**
 * Default implementation for a connection provider:
 * a simple login/pass connection, with no pool
 * management.
 */
class RWSimpleAuthConnectionProvider(
  override val authDn : String,
  override val authPw : String,
  override val host : String = "localhost",
  override val port : Int = 389,
  override val ldifFileLogger:LDIFFileLogger = new DefaultLDIFFileLogger(),
  override val useSchemaInfos : Boolean = false
) extends SimpleAuthConnection with OneConnectionProvider[RwLDAPConnection]{
  def newConnection = new RwLDAPConnection(newUnboundidConnection,ldifFileLogger)
}

/**
 * Pooled implementation for a connection provider
 * with a simple login/pass connection
 */
class ROPooledSimpleAuthConnectionProvider(
  override val authDn : String,
  override val authPw : String,
  override val host : String = "localhost",
  override val port : Int = 389,
  override val ldifFileLogger:LDIFFileLogger = new DefaultLDIFFileLogger(),
  override val useSchemaInfos : Boolean = false,
  override val poolSize : Int = 2
) extends SimpleAuthConnection with PooledConnectionProvider[RoLDAPConnection] {
  def newConnection = new RoLDAPConnection(pool.getConnection,ldifFileLogger)
}

/**
 * Pooled implementation for a connection provider
 * with a simple login/pass connection
 */
class RWPooledSimpleAuthConnectionProvider(
  override val authDn : String,
  override val authPw : String,
  override val host : String = "localhost",
  override val port : Int = 389,
  override val ldifFileLogger:LDIFFileLogger = new DefaultLDIFFileLogger(),
  override val useSchemaInfos : Boolean = false,
  override val poolSize : Int = 2
) extends SimpleAuthConnection with PooledConnectionProvider[RwLDAPConnection]{
  def newConnection = new RwLDAPConnection(pool.getConnection,ldifFileLogger)
}
