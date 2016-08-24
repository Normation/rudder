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


import org.squeryl.Session
import org.squeryl.adapters.PostgreSqlAdapter
import javax.sql.DataSource
import net.liftweb.common.Loggable
import java.sql.SQLException

/**
 * A wrapper around the Squeryl default implementation to allow for several
 * databases connections, and still offer the multi-threading capabilities
 */
class SquerylConnectionProvider(
    dataSource : DataSource) extends Loggable {


   def getConnection() : java.sql.Connection = {
     dataSource.getConnection()
   }


   /* This must be done only if select are made, otherwise all update and insert will be lost ! */
   def ourSession[A](a : => A) : A = {
    val c = getConnection
    val s = Session.create(c, new PostgreSqlAdapter)


    if(c.getAutoCommit)
      c.setAutoCommit(false)

    var txOk = false
    try {
      val res =try {
        s.bindToCurrentThread
        a

      }
      finally {
        s.unbindFromCurrentThread
        s.cleanup
      }

      txOk = true
      res
    }
    finally {
      try {
        // Don't commit nor rollback, simply close for return safely in the pool
        c.close()
      }
      catch {
        case e:SQLException => {
          if(txOk) throw e // if an exception occured b4 the commit/rollback we don't want to obscure the original exception
        }
      }
      try{c.close}
      catch {
        case e:SQLException => {
          if(txOk) throw e // if an exception occured b4 the close we don't want to obscure the original exception
        }
      }
     }
   }

   /* This has to be used when insert or update are done*/
   def ourTransaction[A](a : => A) : A = {
    val c = getConnection
    val s = Session.create(c, new PostgreSqlAdapter)


    if(c.getAutoCommit)
      c.setAutoCommit(false)

    var txOk = false
    try {
      val res =try {
        s.bindToCurrentThread
        a

      }
      finally {
        s.unbindFromCurrentThread
        s.cleanup
      }

      txOk = true
      res
    }
    finally {
      try {
        if(txOk)
          c.commit
        else {
          c.rollback
        }
      }
      catch {
        case e:SQLException => {
          if(txOk) throw e // if an exception occured b4 the commit/rollback we don't want to obscure the original exception
        }
      }
      try{c.close}
      catch {
        case e:SQLException => {
          if(txOk) throw e // if an exception occured b4 the close we don't want to obscure the original exception
        }
      }
    }
  }

}