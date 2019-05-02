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

package com.normation.rudder.services.queries

import org.junit._
import org.junit.Assert._
import org.junit.runner.RunWith
import org.junit.runners.BlockJUnit4ClassRunner
import com.normation.rudder.repository.ldap.LDAPEntityMapper
import com.normation.rudder.domain.queries._
import net.liftweb.common._
import com.normation.rudder.domain._
import com.unboundid.ldap.sdk.DN
import com.normation.ldap.ldif._
import com.normation.ldap.listener.InMemoryDsConnectionProvider
import com.normation.ldap.sdk._
import com.normation.inventory.ldap.core._
import com.normation.inventory.domain.NodeId
import com.normation.utils.HashcodeCaching
import com.normation.rudder.services.nodes.NaiveNodeInfoServiceCachedImpl

import com.normation.zio._
import com.normation.errors._

/*
 * Test query parsing.
 *
 * These test doesn't test JSON syntax error, as we rely on
 * a JSON parser for that part.
 */

@RunWith(classOf[BlockJUnit4ClassRunner])
class TestQueryProcessor extends Loggable {

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
  val ldap = InMemoryDsConnectionProvider[RoLDAPConnection](
      baseDNs = "cn=rudder-configuration" :: Nil
    , schemaLDIFPaths = schemaLDIFs
    , bootstrapLDIFPaths = bootstrapLDIFs
    , ldifLogger
  )
  //end inMemory ds

  val DIT = new InventoryDit(new DN("ou=Accepted Inventories,ou=Inventories,cn=rudder-configuration"),new DN("ou=Inventories,cn=rudder-configuration"),"test")

  val removedDIT = new InventoryDit(new DN("ou=Removed Inventories,ou=Inventories,cn=rudder-configuration"),new DN("ou=Inventories,cn=rudder-configuration"),"test")
  val pendingDIT = new InventoryDit(new DN("ou=Pending Inventories,ou=Inventories,cn=rudder-configuration"),new DN("ou=Inventories,cn=rudder-configuration"),"test")
  val ditService = new InventoryDitServiceImpl(pendingDIT, DIT, removedDIT)
  val nodeDit = new NodeDit(new DN("cn=rudder-configuration"))
  val rudderDit = new RudderDit(new DN("ou=Rudder, cn=rudder-configuration"))

  val ditQueryData = new DitQueryData(DIT, nodeDit, rudderDit, () => Left(Unconsistancy("For test, no subgroup")))

  val inventoryMapper = new InventoryMapper(ditService, pendingDIT, DIT, removedDIT)
  val ldapMapper = new LDAPEntityMapper(rudderDit, nodeDit, DIT, null, inventoryMapper)
  val internalLDAPQueryProcessor = new InternalLDAPQueryProcessor(ldap,DIT,nodeDit,ditQueryData,ldapMapper)

  val nodeInfoService = new NaiveNodeInfoServiceCachedImpl(ldap, nodeDit, DIT, removedDIT, pendingDIT, ldapMapper, inventoryMapper)

  val queryProcessor = new AcceptedNodesLDAPQueryProcessor(
      nodeDit,
      DIT,
      internalLDAPQueryProcessor,
      nodeInfoService
  )

  val parser = new CmdbQueryParser with
    DefaultStringQueryParser with
    JsonQueryLexer {
    override val criterionObjects = Map[String,ObjectCriterion]() ++ ditQueryData.criteriaMap
  }

  case class TestQuery(name:String,query:Query,awaited:Seq[NodeId]) extends HashcodeCaching


  // when one need to debug search, you can just uncomment that to set log-level to trace
  //val l: ch.qos.logback.classic.Logger = org.slf4j.LoggerFactory.getLogger("com.normation.rudder.services.queries").asInstanceOf[ch.qos.logback.classic.Logger]
  //l.setLevel(ch.qos.logback.classic.Level.TRACE)


  val s = Seq(
    new NodeId("node0"),new NodeId("node1"),new NodeId("node2"),
    new NodeId("node3"),new NodeId("node4"),new NodeId("node5"),
    new NodeId("node6"),new NodeId("node7")
  )

  val sr = NodeId("root") +: s

  @Test def ensureNodeLoaded(): Unit = {
    //just check that we correctly loaded demo data in serve
    val s = (for {
      con <- ldap
      res <- con.search("cn=rudder-configuration", Sub, BuildFilter.ALL)
    } yield {
      res.size
    }).runNow

    val expected = 42+37  //bootstrap + inventory-sample
    assert(expected == s, s"Not found the expected number of entries in test LDAP directory [expected: ${expected}, found: ${s}], perhaps the demo entries where not correctly loaded")
  }

  @Test def basicQueriesOnId(): Unit = {

    /* find back all server */
    val q0 = TestQuery(
      "q0",
      parser("""
      {  "select":"node", "where":[
        { "objectType":"node"   , "attribute":"nodeId"  , "comparator":"exists" }
      ] }
      """).openOrThrowException("For tests"),
      s)

    /* find back server 1 and 5 by id */
    val q1 = TestQuery(
      "q1",
      parser("""
      { "select":"node", "composition":"or", "where":[
         { "objectType":"node"   , "attribute":"nodeId"  , "comparator":"eq", "value":"node1" }
       , { "objectType":"node"   , "attribute":"nodeId"  , "comparator":"eq", "value":"node5" }
      ] }
      """).openOrThrowException("For tests"),
      s(1) :: s(5) :: Nil)

    /* find back neither server 1 and 5 by id because of the and */
    val q2 = TestQuery(
      "q2",
      parser("""
      {  "select":"node", "composition":"and", "where":[
        { "objectType":"node"   , "attribute":"nodeId"  , "comparator":"eq", "value":"node1" }
        { "objectType":"node"   , "attribute":"nodeId"  , "comparator":"eq", "value":"node5" }
      ] }
      """).openOrThrowException("For tests"),
      Nil)

    testQueries( q0 :: q1 :: q2 :: Nil)
  }

  @Test def basicQueriesOnOneNodeParameter(): Unit = {
    // only two servers have RAM: server1(RAM) = 10000000, server2(RAM) = 1

    val q2_0 = TestQuery(
      "q2_0",
      parser("""
      {  "select":"node", "where":[
        { "objectType":"node", "attribute":"ram", "comparator":"gt", "value":"1" }
      ] }
      """).openOrThrowException("For tests"),
      s(1) :: Nil)

    val q2_0_ = TestQuery("q2_0_", query = q2_0.query.copy(composition = Or), q2_0.awaited)

    val q2_1 = TestQuery(
      "q2_1",
      parser("""
      {  "select":"node", "where":[
        { "objectType":"node", "attribute":"ram", "comparator":"gteq", "value":"1" }
      ] }
      """).openOrThrowException("For tests"),
      s(1) :: s(2) :: Nil)

    val q2_1_ = TestQuery("q2_1_", query = q2_1.query.copy(composition = Or), q2_1.awaited)

    val q2_2 = TestQuery(
      "q2_2",
      parser("""
      {  "select":"node", "where":[
        { "objectType":"node", "attribute":"ram", "comparator":"lteq", "value":"1" }
      ] }
      """).openOrThrowException("For tests"),
      s(2) :: Nil)

    val q2_2_ = TestQuery("q2_2_", query = q2_2.query.copy(composition = Or), q2_2.awaited)

    // group of group, with or/and composition
    val q3 = TestQuery(
      "q3",
      parser("""
      {  "select":"node", "where":[
        { "objectType":"group", "attribute":"nodeGroupId", "comparator":"eq", "value":"test-group-node1" }
      ] }
      """).openOrThrowException("For tests"),
      s(1) :: Nil)

    testQueries(q2_0 :: q2_0_ :: q2_1 :: q2_1_ :: q2_2 :: q2_2_ :: q3 :: Nil)
  }

  // group of group, with or/and composition
  @Test def groupOfgroups(): Unit = {
    val q1 = TestQuery(
      "q1",
      parser("""
      {  "select":"node", "where":[
        { "objectType":"group", "attribute":"nodeGroupId", "comparator":"eq", "value":"test-group-node1" }
      ] }
      """).openOrThrowException("For tests"),
      s(1) :: Nil)

    val q2 = TestQuery(
      "q2",
      parser("""
      {  "select":"node", "composition":"or", "where":[
        { "objectType":"group", "attribute":"nodeGroupId", "comparator":"eq", "value":"test-group-node1" }
      , { "objectType":"group", "attribute":"nodeGroupId", "comparator":"eq", "value":"test-group-node2" }
      ] }
      """).openOrThrowException("For tests"),
      s(1) :: s(2) :: Nil)

    val q3 = TestQuery(
      "q3",
      parser("""
      {  "select":"node", "where":[
        { "objectType":"group", "attribute":"nodeGroupId", "comparator":"eq", "value":"test-group-node1" }
      , { "objectType":"node"   , "attribute":"nodeId"  , "comparator":"eq", "value":"node1" }
      ] }
      """).openOrThrowException("For tests"),
      s(1) :: Nil)

    val q4 = TestQuery(
      "q4",
      parser("""
      {  "select":"node", "where":[
        { "objectType":"group", "attribute":"nodeGroupId", "comparator":"eq", "value":"test-group-node1" }
      , { "objectType":"group", "attribute":"nodeGroupId", "comparator":"eq", "value":"test-group-node12" }
      ] }
      """).openOrThrowException("For tests"),
      s(1) :: Nil)

    val q5 = TestQuery(
      "q5",
      parser("""
      {  "select":"node", "where":[
        { "objectType":"group", "attribute":"nodeGroupId", "comparator":"eq", "value":"test-group-node12" }
      , { "objectType":"group", "attribute":"nodeGroupId", "comparator":"eq", "value":"test-group-node23" }
      ] }
      """).openOrThrowException("For tests"),
      s(2) :: Nil)

    testQueries(q1 :: q2 ::q3 :: q4 :: q5 :: Nil)
  }

  @Test def machineComponentQueries(): Unit = {
    val q3 = TestQuery(
      "q3",
      parser("""
      { "select":"node", "where":[
      { "objectType":"biosPhysicalElement", "attribute":"softwareVersion", "comparator":"eq", "value":"6.00" }
      ] }
      """).openOrThrowException("For tests"),
      s(6) :: s(7) :: Nil)

    testQueries(q3 :: Nil)
  }

  @Test def softwareQueries(): Unit = {

    val q1 = TestQuery(
      "q1",
      parser("""
      { "select":"node", "where":[
        { "objectType":"software", "attribute":"softwareVersion", "comparator":"eq", "value":"1.0.0" }
      ] }
      """).openOrThrowException("For tests"),
      s(2) :: s(7) :: Nil)

    val q2 = TestQuery(
      "q2",
      parser("""
      { "select":"node", "where":[
        { "objectType":"software", "attribute":"softwareVersion", "comparator":"eq", "value":"2.0-rc" }
      ] }
      """).openOrThrowException("For tests"),
      s(2) :: Nil)

    testQueries(q1 :: q2 :: Nil)
  }

  @Test def logicalElementQueries(): Unit = {

    val q1 = TestQuery(
      "q1",
      parser("""
      { "select":"node", "where":[
        { "objectType":"fileSystemLogicalElement", "attribute":"fileSystemFreeSpace", "comparator":"gteq", "value":"1" }
      ] }
      """).openOrThrowException("For tests"),
      s(3) :: s(7) :: Nil)

    val q2 = TestQuery(
      "q2",
      parser("""
      { "select":"node", "where":[
        { "objectType":"fileSystemLogicalElement", "attribute":"fileSystemFreeSpace", "comparator":"gteq", "value":"100" }
      ] }
      """).openOrThrowException("For tests"),
      s(3) :: Nil)

    testQueries(q1 :: q2 :: Nil)
  }

  @Test def networkInterfaceElementQueries(): Unit = {

    val q1 = TestQuery(
      "q1",
      parser("""
      { "select":"node", "where":[
        { "objectType":"networkInterfaceLogicalElement", "attribute":"networkInterfaceGateway", "comparator":"regex", "value":".*192.168.*" }
      ] }
      """).openOrThrowException("For tests"),
      s(1) :: s(2) :: Nil)

    val q2 = TestQuery(
      "q2",
      parser("""
      { "select":"node", "where":[
        { "objectType":"networkInterfaceLogicalElement", "attribute":"ipHostNumber", "comparator":"regex", "value":".*192.168.*" }
      ] }
      """).openOrThrowException("For tests"),
      s(1) :: s(2) :: s(3) :: Nil)


    testQueries(q1 :: q2 :: Nil)
  }

  @Test def regexQueries(): Unit = {

    //regex and "subqueries" for logical elements should not be contradictory
    //here, we have to *only* search for logical elements with the regex
    //and cn is both on node and logical elements
    val q0 = TestQuery(
      "q0",
      parser("""
      {  "select":"node", "composition":"or" , "where":[
        , { "objectType":"fileSystemLogicalElement", "attribute":"description", "comparator":"regex", "value":"matchOnM[e]" }
      ] }
      """).openOrThrowException("For tests"),
      s(3) :: Nil)

    //on node
    val q1 = TestQuery(
      "q1",
      parser("""
      {  "select":"node", "where":[
          { "objectType":"node" , "attribute":"ram"    , "comparator":"regex", "value":"[0-9]{9}" }
        , { "objectType":"node" , "attribute":"osKernelVersion" , "comparator":"regex", "value":"[0-9.-]+-(gen)eric" }
        , { "objectType":"node" , "attribute":"nodeId" , "comparator":"regex", "value":"[nN]ode[01]" }
      ] }
      """).openOrThrowException("For tests"),
      s(1) :: Nil)

    val q1_ = TestQuery("q1_", query = q1.query.copy(composition = Or), s(0) :: s(1) :: Nil)

    //on node software, machine, machine element, node element
    val q2 = TestQuery(
      "q2",
      parser("""
      {  "select":"nodeAndPolicyServer", "where":[
          { "objectType":"node" , "attribute":"nodeId" , "comparator":"regex", "value":"[nN]ode[017]" }
        , { "objectType":"software", "attribute":"cn", "comparator":"regex"   , "value":"Software [0-9]" }
        , { "objectType":"machine", "attribute":"machineId", "comparator":"regex" , "value":"machine[0-2]"  }
        , { "objectType":"fileSystemLogicalElement", "attribute":"fileSystemFreeSpace", "comparator":"regex", "value":"[01]{2}" }
        , { "objectType":"biosPhysicalElement", "attribute":"softwareVersion", "comparator":"regex", "value":"[6.0]+" }
      ] }
      """).openOrThrowException("For tests"),
      s(7) :: Nil)

    val q2_ = TestQuery("q2_", query = q2.query.copy(composition = Or),
        (s(0) :: s(1) :: s(7) :: //nodeId
        s(2) :: s(7) :: //software
        s(4) :: s(5) :: s(6) :: s(7) :: //machine
        s(2) :: sr(0) :: // free space
        s(2) :: //bios
        Nil).distinct)

    //on node and or for regex
    val q3 = TestQuery(
      "q3",
      parser("""
      {  "select":"node",  "composition":"or", "where":[
          { "objectType":"node" , "attribute":"nodeId" , "comparator":"regex", "value":"[nN]ode[01]" }
        , { "objectType":"node" , "attribute":"nodeId" , "comparator":"regex", "value":"[nN]ode[12]" }
      ] }
      """).openOrThrowException("For tests"),
      s(0) :: s(1) :: s(2) :: Nil)

    //on node and or for regex, testing #3340
    val q3_2 = TestQuery(
      "q3_2",
      parser("""
      {  "select":"node",  "composition":"or", "where":[
          { "objectType":"node" , "attribute":"nodeId" , "comparator":"regex", "value":"[nN]ode[01]" }
        , { "objectType":"node" , "attribute":"nodeId" , "comparator":"eq"   , "value":"node5" }
        , { "objectType":"software", "attribute":"softwareVersion", "comparator":"eq", "value":"1.0.0" }
      ] }
      """).openOrThrowException("For tests"),
      s(0) :: s(1) :: s(2) :: s(5) :: s(7) :: Nil)

    //same as q3 with and
    val q4 = TestQuery(
      "q3",
      parser("""
      {  "select":"node",  "composition":"and", "where":[
          { "objectType":"node" , "attribute":"nodeId" , "comparator":"regex", "value":"[nN]ode[01]" }
        , { "objectType":"node" , "attribute":"nodeId" , "comparator":"regex", "value":"[nN]ode[12]" }
      ] }
      """).openOrThrowException("For tests"),
      s(1) :: Nil)

    val q5 = TestQuery(
      "q5",
      parser("""
      {  "select":"nodeAndPolicyServer","composition":"or",  "where":[
        , { "objectType":"fileSystemLogicalElement" , "attribute":"mountPoint" , "comparator":"regex", "value":"[/]" }
      ] }
      """).openOrThrowException("For tests"),
      s(3) :: s(7) :: sr(0) ::  Nil)

    //test regex for "not containing word", see http://stackoverflow.com/questions/406230/regular-expression-to-match-string-not-containing-a-word
    //here, we don't want to have node0 or node1
    val q6 = TestQuery(
      "q5",
      parser("""
      {  "select":"node", "where":[
          { "objectType":"node" , "attribute":"nodeId" , "comparator":"regex", "value":"((?!node0|node1).)*" }
      ] }
      """).openOrThrowException("For tests"),
      s.tail.tail)

    //same as q5, but with "not regex"
    val q7 = TestQuery(
      "q6",
      parser("""
      {  "select":"node", "where":[
          { "objectType":"node" , "attribute":"nodeId" , "comparator":"notRegex", "value":"node0" }
      ] }
      """).openOrThrowException("For tests"),
      s.tail)

    //same as q5 on IP, to test with escaping
    //192.168.56.101 is for node3
    val q8 = TestQuery(
      "q7",
      parser("""
      {  "select":"node", "where":[
          { "objectType":"node" , "attribute":"ipHostNumber" , "comparator":"notRegex", "value":"192.168.56.101" }
      ] }
      """).openOrThrowException("For tests"),
      s.filterNot( _ == s(1)) )

    //typical use case for server on internal/dmz/both: want intenal (but not both)
    //that test a match regex and not regex
    val q9 = TestQuery(
      "q8",
      parser("""
      {  "select":"node", "where":[
          { "objectType":"node" , "attribute":"ipHostNumber" , "comparator":"regex", "value":"127.0.0.*" }
        , { "objectType":"node" , "attribute":"ipHostNumber" , "comparator":"notRegex", "value":"192.168.56.10[23]" }
      ] }
      """).openOrThrowException("For tests"),
      Seq(s(1), s(4)) )
      //s0,5,6,7,8 not ok because no 127.0.0.1
      //s1 ok because not in "not regex" pattern
      //s2,s3 not ok because in the "not regex" pattern
      //s4 ok because only 127.0.0.1

    testQueries(q0  :: q1 :: q1_ :: q2 :: q2_ :: q3 :: q3_2 :: q4 :: q5 :: q6 :: q7 :: q8 :: q9 :: Nil)
  }

  @Test def dateQueries(): Unit = {
    //the node inventory date is 15/05/2013

    def q(name: String, comp: String, day: Int, expects: Seq[NodeId] ) = TestQuery(
        name
      , parser("""
          {  "select":"node", "where":[
            { "objectType":"node", "attribute":"inventoryDate", "comparator":"%s"   , "value":"%s/05/2013" }
          ] }
          """.format(comp, day)).openOrThrowException("For tests")
      , expects
    )

    def query(name: String, comp: String, day: Int, valid: Boolean) = q(name, comp, day, { if(valid) s(0) :: Nil else Nil })

    val q12 = q("q12", "notEq", 15, s.filterNot( _ == s(0)))
    val q13 = q("q13", "notEq", 14, s )
    val q14 = q("q14", "notEq", 16, s )

    testQueries(
         query("q1", "eq", 15, valid = true)
      :: query("q2", "eq", 14, valid = false)
      :: query("q3", "eq", 16, valid = false)
      :: query("q4", "gteq", 15, valid = true)
      :: query("q5", "gteq", 16, valid = false)
      :: query("q6", "lteq", 15, valid = true)
      :: query("q7", "lteq", 14, valid = false)
      :: query("q8", "lt", 15, valid = false)
      :: query("q9", "lt", 16, valid = true)
      :: query("q10", "gt", 15, valid = false)
      :: query("q11", "gt", 14, valid = true)
      :: q12 :: q13 :: q14
      :: Nil
    )
  }

  @Test def policyServerQueriesOnId(): Unit = {

    val q0 = TestQuery(
      "q0",
      parser("""
      {  "select":"nodeAndPolicyServer", "where":[
        { "objectType":"node"   , "attribute":"nodeId"  , "comparator":"exists" }
      ] }
      """).openOrThrowException("For tests"),
      sr)

    val q1 = TestQuery(
      "q1",
      parser("""
      {  "select":"nodeAndPolicyServer", "composition":"or", "where":[
        { "objectType":"node"   , "attribute":"nodeId"  , "comparator":"exists" }
      ] }
      """).openOrThrowException("For tests"),
      sr)

    testQueries( q0 :: q1 :: Nil)
  }

  @Test def agentTypeQueries: Unit = {

    val allCfengine = TestQuery(
      "allCfengine",
      parser("""
      {  "select":"nodeAndPolicyServer", "where":[
        { "objectType":"node" , "attribute":"agentName"  , "comparator":"eq", "value":"cfengine" }
      ] }
      """).openOrThrowException("For tests"),
      sr(0) :: sr(1) :: sr(2) :: sr(3) :: sr(4) :: sr(5) :: sr(7)  :: sr(8) :: Nil)

    val community = TestQuery(
      "community",
      parser("""
      {  "select":"nodeAndPolicyServer", "composition":"or", "where":[
        { "objectType":"node"   , "attribute":"agentName"  , "comparator":"eq", "value":"community" }
      ] }
      """).openOrThrowException("For tests"),
      sr(0) :: sr(2) :: sr(4) :: sr(5) :: sr(7) :: sr(8) :: Nil)

    val nova = TestQuery(
      "nova",
      parser("""
      {  "select":"nodeAndPolicyServer", "composition":"or", "where":[
        { "objectType":"node" , "attribute":"agentName"  , "comparator":"eq", "value":"nova" }
      ] }
      """).openOrThrowException("For tests"),
      sr(1) :: sr(3) :: Nil)

    val dsc = TestQuery(
      "dsc",
      parser("""
      {  "select":"nodeAndPolicyServer", "composition":"or", "where":[
        { "objectType":"node", "attribute":"agentName"  , "comparator":"eq", "value":"dsc" }
      ] }
      """).openOrThrowException("For tests"),
      sr(6) :: Nil)

    val notCfengine = TestQuery(
      "notCfengine",
      parser("""
      {  "select":"nodeAndPolicyServer", "composition":"or", "where":[
        { "objectType":"node", "attribute":"agentName"  , "comparator":"notEq", "value":"cfengine" }
      ] }
      """).openOrThrowException("For tests"),
      sr(6) :: Nil)

    testQueries( allCfengine :: community :: nova :: dsc :: notCfengine :: Nil)
  }
  /**
   * Test environment variable
   */
  @Test def nodeJsonFixedKeyQueries(): Unit = {

    val q1 = TestQuery(
      "q1",
      parser("""
      {"select":"node","composition":"And","where":[
        {"objectType":"process","attribute":"started","comparator":"eq","value":"2015-01-21 17:24"}
      ]}
      """).openOrThrowException("For tests"),
      s(1) :: Nil)

    val q2 = TestQuery(
      "q2",
      parser("""
      {"select":"node","composition":"And","where":[
        {"objectType":"process","attribute":"commandName","comparator":"regex","value":".*vtmp.*"}
      ]}
      """).openOrThrowException("For tests"),
      s(1) :: Nil)

    testQueries(q1 :: q2 :: Nil)
  }

  /**
   * Test environment variable and nodeProperty
   */
  @Test def nodeNameValueQueries(): Unit = {

    val q1 = TestQuery(
      "q1",
      parser("""
      {"select":"node","composition":"And","where":[
        {"objectType":"environmentVariable","attribute":"name.value","comparator":"eq","value":"SHELL=/bin/sh"}
      ]}
      """).openOrThrowException("For tests"),
      s(1) :: Nil)

    val q2 = TestQuery(
      "q2",
      parser("""
      {"select":"node","composition":"And","where":[
        {"objectType":"environmentVariable","attribute":"name.value","comparator":"regex","value":".+=/.*/rudder.*"}
      ]}
      """).openOrThrowException("For tests"),
      s(2) :: s(3) :: Nil)

    testQueries(q1 :: q2 :: Nil)
  }

  @Test def nodeStateQueries(): Unit = {

    val q1 = TestQuery(
      "q1",
      parser("""
      {  "select":"node", "where":[
        { "objectType":"node", "attribute":"state", "comparator":"eq", "value":"initializing" }
      ] }
      """).openOrThrowException("For tests"),
      s(7) :: Nil)

    val q2 = TestQuery(
      "q1",
      parser("""
      {  "select":"node", "where":[
        { "objectType":"node", "attribute":"state", "comparator":"eq", "value":"enabled" }
      ] }
      """).openOrThrowException("For tests"),
      s(0) :: s(1) :: s(2) :: s(3) :: s(4) :: s(5) :: s(6) :: Nil)

    testQueries(q1 :: q2 :: Nil)
  }


  @Test def nodeProperties(): Unit = {
    val q1 = TestQuery(
      "q1",
      parser("""
      { "select":"node", "where":[
        { "objectType":"serializedNodeProperty", "attribute":"name.value", "comparator":"eq", "value":"foo=bar" }
      ] }
      """).openOrThrowException("For tests"),
      s(1) :: Nil)

    val q2 = TestQuery(
      "q2",
      parser("""
      { "select":"node", "where":[
        { "objectType":"serializedNodeProperty", "attribute":"name.value", "comparator":"regex", "value":"foo?=.*ar" }
      ] }
      """).openOrThrowException("For tests"),
      s(1) :: Nil)

    val q3 = TestQuery(
      "q3",
      parser("""
      { "select":"node", "where":[
        { "objectType":"serializedNodeProperty", "attribute":"name.value", "comparator":"hasKey", "value":"datacenter" }
      ] }
      """).openOrThrowException("For tests"),
      s(1) :: s(2) :: s(3) :: Nil) // s1 is in inventory custom property, s2 & s3 in node properties

    // same as "haskey"
    val q4 = TestQuery(
      "q4",
      parser("""
      { "select":"node", "where":[
        { "objectType":"serializedNodeProperty", "attribute":"name.value", "comparator":"regex", "value":"datacenter=.*" }
      ] }
      """).openOrThrowException("For tests"),
      s(1) :: s(2) :: s(3) :: Nil)

    // kind of matching sub-keys
    val q5 = TestQuery(
      "q5",
      parser("""
      { "select":"node", "where":[
        { "objectType":"serializedNodeProperty", "attribute":"name.value", "comparator":"regex", "value":"datacenter=.*\"id\":1234,.*" }
      ] }
      """).openOrThrowException("For tests"),
      s(2) :: Nil)

    // matching unquoted number
    val q6 = TestQuery(
      "q6",
      parser("""
      { "select":"node", "where":[
        { "objectType":"serializedNodeProperty", "attribute":"name.value", "comparator":"regex", "value":"number=42" }
      ] }
      """).openOrThrowException("For tests"),
      s(3) :: s(4) :: Nil)

    // matching provider, but the user data only
    val q7 = TestQuery(
      "q7",
      parser("""
      { "select":"node", "where":[
        { "objectType":"serializedNodeProperty", "attribute":"name.value", "comparator":"regex", "value":"datacenter=.*provider.*" }
      ] }
      """).openOrThrowException("For tests"),
      s(3) :: Nil)

    // the properties are in inventory
    val q8 = TestQuery(
      "q8",
      parser("""
      { "select":"node", "where":[
        { "objectType":"serializedNodeProperty", "attribute":"name.value", "comparator":"regex", "value":"datacenter=.*Paris.*" }
      ] }
      """).openOrThrowException("For tests"),
      s(1) :: Nil)

    testQueries(q1 :: q2 :: q3 :: q4 :: q5 ::  q6 :: q7 :: q8 :: Nil)
  }

  /*
   * When using JsonPath on node properties to know if the node should be return or not, what we are
   * actually looking for is is the resulting JSON select query is empty or not. If empty, the node
   * does not have the property and is not in the group.
   * It means that we are not forced to matches *leaves* or only one elements.
   *
   * Test will be focused on node 5 and 6.
   *
   */
  @Test def nodePropertiesJsonPath(): Unit = {
    val q1 = TestQuery(
      "q1", // select nodes with user.accepted = true
      parser("""
      { "select":"node", "where":[
        { "objectType":"serializedNodeProperty", "attribute":"name.value", "comparator":"jsonSelect", "value":"user:$.[?(@.accepted==true)]" }
      ] }
      """).openOrThrowException("For tests"),
      s(5) :: Nil)

    val q2 = TestQuery(
      "q2", // city is exactly New York
      parser("""
      { "select":"node", "where":[
        { "objectType":"serializedNodeProperty", "attribute":"name.value", "comparator":"jsonSelect", "value":"user:$.personal.address[?(@.city=='New York')]" }
      ] }
      """).openOrThrowException("For tests"),
      s(5) :: Nil)

    val q3 = TestQuery(
      "q3", // state has a value (whatever it is)
      parser("""
      { "select":"node", "where":[
        { "objectType":"serializedNodeProperty", "attribute":"name.value", "comparator":"jsonSelect", "value":"user:$.personal.address.city" }
      ] }
      """).openOrThrowException("For tests"),
      s(5) :: s(6) :: Nil)

    val q4 = TestQuery(
      "q4", // phone number like .*256-.*
      parser("""
      { "select":"node", "where":[
        { "objectType":"serializedNodeProperty", "attribute":"name.value", "comparator":"jsonSelect", "value":"user:$.personal.phones[?(@.number=~/.*123.*/)]" }
      ] }
      """
      ).openOrThrowException("For tests"),
      s(6) :: Nil)

    val q5 = TestQuery(
      "q5", // state is in [NY, CA, LA, TX]
      parser("""
      { "select":"node", "where":[
        { "objectType":"serializedNodeProperty", "attribute":"name.value", "comparator":"jsonSelect", "value":"user:$.personal.address[?(@.state in ['NY', 'CA', 'LA', 'TX'])]" }
      ] }
      """
      ).openOrThrowException("For tests"),
      s(5) :: s(6) :: Nil)

    testQueries(q1 :: q2 :: q3 :: q4 :: q5 :: Nil)
  }

  @Test def nodePropertiesFailingReq(): Unit = {
    def forceParse(q: String) = parser(q).openOrThrowException("Parsing the request must be ok for that test")
    // Failing request, see #10570
    val failingRegexRequests =
      """
      { "select":"node", "where":[
        { "objectType":"serializedNodeProperty", "attribute":"name.value", "comparator":"regex", "value":"f{o}o" }
      ] }""" ::
      // if there is no "=", we fails
      """
      { "select":"node", "where":[
        { "objectType":"serializedNodeProperty", "attribute":"name.value", "comparator":"eq", "value":"foo" }
      ] }
      """ :: Nil

    val results = failingRegexRequests.map(q => (q, queryProcessor.process(forceParse(q))))
    results.foreach { r =>
      assertTrue(s"Regex Query with wrong data for node properties should fail: ${r._1}", r._2.isInstanceOf[Failure])
    }
  }

  @Test def unsortedQueries(): Unit = {
    val q1 = TestQuery(
      "q1",
      parser("""
      {  "select":"node", "where":[
        { "objectType":"software", "attribute":"cn", "comparator":"eq"   , "value":"aalib-libs.i586" },
        { "objectType":"machine", "attribute":"cn", "comparator":"exists"  },
        { "objectType":"node"   , "attribute":"ram"  , "comparator":"gt", "value":"1000" }
      ] }
      """).openOrThrowException("For tests"),
      Nil)

      testQueries(q1 :: Nil)
  }

  private def testQueries(queries:Seq[TestQuery]) : Unit = {
    queries foreach { q =>
      logger.debug("Processing: " + q.name)
      testQueryResultProcessor(q.name,q.query,q.awaited)
    }

  }

  private def testQueryResultProcessor(name:String,query:Query, nodes:Seq[NodeId]) = {
      val ids = nodes.sortBy( _.value )
      val found = queryProcessor.process(query).openOrThrowException("For tests").map { _.id }.sortBy( _.value )
      //also test with requiring only the expected node to check consistancy
      //(that should not change anything)

      assertEquals("[%s]Duplicate entries in result: %s".format(name,found),
          found.size.toLong,found.distinct.size.toLong)
      assertEquals("[%s]Size differ between awaited and found entry set  (process) Found: %s Wants: %s".
          format(name,found,ids),ids.size.toLong,found.size.toLong)
      assertTrue("[%s]Entries differ between awaited and found entry set (process) Found: %s Wants: %s".
          format(name,found,ids),found.forall { f => ids.exists( f == _) })

      logger.debug("Testing with expected entries")
      val foundWithLimit = (internalLDAPQueryProcessor.internalQueryProcessor(query, limitToNodeIds = Some(ids)).runNow.entries.map { entry =>
        NodeId(entry("nodeId").get)
      }).distinct.sortBy( _.value )
      assertEquals("[%s]Size differ between awaited entry and found entry set when setting expected entries (process)\n Found: %s\n Wants: %s".
          format(name,foundWithLimit,ids),ids.size.toLong,foundWithLimit.size.toLong)
  }

  @After def after(): Unit = {
    ldap.server.shutDown(true)
  }
}
