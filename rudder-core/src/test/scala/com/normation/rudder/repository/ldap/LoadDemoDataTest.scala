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

package com.normation.rudder.repository.ldap

import org.junit.runner._
import org.specs2.mutable._
import org.specs2.runner._
import com.normation.ldap.listener.InMemoryDsConnectionProvider
import com.unboundid.ldap.sdk.{DN,ChangeType}
import com.normation.ldap.sdk.BuildFilter


/**
 * A simple test class to check that the demo data file is up to date 
 * with the schema (there may still be a desynchronization if both
 * demo-data, test data and test schema for UnboundID are not synchronized
 * with OpenLDAP Schema).
 */
@RunWith(classOf[JUnitRunner])
class LoadDemoDataTest extends Specification {
  
  val schemaLDIFs = (
      "00-core" :: 
      "01-pwpolicy" :: 
      "04-rfc2307bis" :: 
      "05-rfc4876" :: 
      "099-0-inventory" :: 
      "099-1-rudder"  :: 
      Nil
  ) map { name =>
    this.getClass.getClassLoader.getResource("ldap-data/schema/" + name + ".ldif").getPath
  }
  
  val baseDN = "cn=rudder-configuration"
  
    
  "The in memory LDAP directory" should {
    "correctly load and read back demo-entries" in {
      
      var i = 0
      
      val bootPaths = for (path <- "ldap/bootstrap.ldif" :: "ldap/init-policy-server.ldif" :: Nil ) yield {
        val p = this.getClass.getClassLoader.getResource(path).getPath
        val reader = new com.unboundid.ldif.LDIFReader(p)
        while(reader.readEntry != null) i += 1
        
        p
      }

      val ldap = InMemoryDsConnectionProvider(
          baseDNs = baseDN :: Nil
        , schemaLDIFPaths = schemaLDIFs
        , bootstrapLDIFPaths = bootPaths
      )
      
      //add demo entries
      val inut2 = {
        val reader = new com.unboundid.ldif.LDIFReader(
          this.getClass.getClassLoader.getResource("ldap/demo-data.ldif").getPath
        )
        var rec = reader.readChangeRecord
        while(rec != null) {
          rec.getChangeType match {
            case x if(x == ChangeType.ADD) => i += 1
            case x if(x == ChangeType.DELETE) => i += 1
            case _ => //nothing
          }
          rec.processChange(ldap.server)
          rec = reader.readChangeRecord
        }
      }

      
      ldap.server.countEntries === i
    }
    
    
    "correctly load and read back test-entries" in {
      val bootstrapLDIFs = ("ldap/bootstrap.ldif" :: "ldap-data/inventory-sample-data.ldif" :: Nil) map { name =>
        this.getClass.getClassLoader.getResource(name).getPath
      } 
       
      
      val numEntries = (0 /: bootstrapLDIFs) { case (x,path) =>
        val reader = new com.unboundid.ldif.LDIFReader(path)
        var i = 0
        while(reader.readEntry != null) i += 1
        i + x
      }
    
      val ldap = InMemoryDsConnectionProvider(
          baseDNs = baseDN :: Nil
        , schemaLDIFPaths = schemaLDIFs
        , bootstrapLDIFPaths = bootstrapLDIFs
      )
    
      ldap.server.countEntries === numEntries
    }

  }    
}