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

package com.normation.rudder.services.nodes


import org.junit.runner.RunWith
import org.specs2.mutable._
import org.specs2.matcher._
import org.specs2.runner.JUnitRunner
import net.liftweb.common.Loggable
import com.normation.rudder.domain.queries.DitQueryData
import com.normation.rudder.domain.NodeDit
import com.normation.ldap.ldif.DefaultLDIFFileLogger
import com.normation.inventory.ldap.core.{InventoryDit,LDAPConstants}
import com.normation.rudder.repository.ldap.LDAPEntityMapper
import com.normation.ldap.listener.InMemoryDsConnectionProvider
import com.unboundid.ldap.sdk.DN
import org.specs2.specification.Fragments
import org.specs2.specification.Step
import net.liftweb.common._
import com.normation.ldap.sdk.RoLDAPConnection

@RunWith(classOf[JUnitRunner])
class TestQuickSearchService extends QuickSearchServiceSpec {

  //example test
  "test1: the search for 'node' in id" should {


    "yield one result for 'node1'" in {
      quickSearch.lookup("node1", 100) match {
        case eb:EmptyBox =>
          val e = eb ?~! "test1 failed"
          e.exceptionChain.foreach( t => logger.error(t) )
          failure(e.messageChain)
        case Full(res) => res must have size(1)
      }
    }

    /*
     * TODO, that test does not pass because in our test datas, we have node
     * in inventory branch, which does not have a corresponding node in
     * node branch. That is really a business error, because all accepted inventory
     * nodes in Rudder should have been registered in the "node" branch, but
     * it is not a reason to completly fails a quicksearch (all other results should
     * be returned).
     * So, quichsearch should be change to either:
     * - change the last sequence with a fold which ignore erroneous nodes
     * - change the second search to also filter with result node IDs
     *   from the first search
     *
     */

   "ignore superfluous server entries" in {

     quickSearch.lookup("node", 100) match {
       case eb:EmptyBox =>
         val e = eb ?~! "test1 failed"
         e.exceptionChain.foreach( t => logger.error(t) )
         failure(e.messageChain)
       case Full(res) => res must have size(8)
     }

   }
  "not matchsuperfluous server entries" in {

     quickSearch.lookup("node0_0", 100) match {
       case Full(res) => res must have size(0)
       case eb:EmptyBox =>
         val e = eb ?~"QuichSearch lookup failed"
         failure(e.messageChain)
     }

   }



  }

  "" should { "succeed" in success }

  "when entry is invalid" should {
    "return an empty sequence" in {
      quickSearch.lookup("", 100) must beEqualTo(Full(Seq()))
    }

  }

}

//a trait which handle all service init, etc
trait QuickSearchServiceSpec extends Specification with Loggable {

  /**
   * Stop the directory after all test.
   * A slower alternative would be to fully init each services before
   * each fragment, using scoped variable:
   * http://etorreborre.github.com/specs2/guide/org.specs2.guide.SpecStructure.html#Variables+isolation
   */
  override def map(fs: => Fragments) = fs ^ Step(stopLDAPServer)

  def stopLDAPServer = ldap.server.shutDown(true)

  //set-up the LDAP servers and required services
  private[this] val ldap = {
    val ldifLogger = new DefaultLDIFFileLogger("TestQueryProcessor","/tmp/normation/rudder/ldif")

    //init of in memory LDAP directory
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

    val bootstrapLDIFs = ("ldap/bootstrap.ldif" :: "ldap-data/inventory-sample-data.ldif" :: Nil) map { name =>
       this.getClass.getClassLoader.getResource(name).getPath
    }

    val ldap = InMemoryDsConnectionProvider.apply[RoLDAPConnection](
        baseDNs = "cn=rudder-configuration" :: Nil
      , schemaLDIFPaths = schemaLDIFs
      , bootstrapLDIFPaths = bootstrapLDIFs
      , ldifLogger
    )

    ldap
  }
  //end inMemory ds

  private[this] val inventoryDit = new InventoryDit(new DN("ou=Accepted Inventories,ou=Inventories,cn=rudder-configuration"),new DN("ou=Inventories,cn=rudder-configuration"),"test")
  final val nodeDit = new NodeDit(new DN("cn=rudder-configuration"))

  final val ldapMapper = new LDAPEntityMapper(
      rudderDit       = null
    , nodeDit         = nodeDit
    , inventoryDit    = inventoryDit
    , cmdbQueryParser = null
  )

  //the actual service to test
  final val quickSearch = new QuickSearchServiceImpl(ldap, nodeDit, inventoryDit, ldapMapper
    //nodeAttributes
    , Seq(LDAPConstants.A_NAME, LDAPConstants.A_NODE_UUID)
    //serverAttributes
    , Seq(
        LDAPConstants.A_HOSTNAME
      , LDAPConstants.A_LIST_OF_IP
      , LDAPConstants.A_OS_NAME
      , LDAPConstants.A_OS_FULL_NAME
      , LDAPConstants.A_OS_VERSION
      , LDAPConstants.A_OS_SERVICE_PACK
      , LDAPConstants.A_OS_KERNEL_VERSION
  ))


}