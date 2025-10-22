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

import com.normation.rudder.db.DBCommon
import org.junit.runner.RunWith
import org.specs2.runner.JUnitRunner

/**
 * Test on database.
 */
@RunWith(classOf[JUnitRunner])
class ExpectedReportsTest extends DBCommon {

  "test" should {
    "be rewrote" in { true == true }
  }

//  //clean data base
//  def cleanTables() = {
//    val qs = sql"delete from expectedReports" :: sql"delete from expectedReportsNodes" :: Nil
//    qs.traverse( _.update.run ).transact(xa).unsafeRunSync
//  }
//
//  val pgIn = new PostgresqlInClause(2)
//  lazy val findReports = new FindExpectedReportsJdbcRepository(doobie, pgIn)
//  lazy val expectedReportsRepo = new UpdateExpectedReportsJdbcRepository(doobie, pgIn)
//  lazy val updateExpectedService = new ExpectedReportsUpdateImpl(expectedReportsRepo)
//
//
//  sequential
//
//  implicit def toReport(t:(DateTime,String, String, String, Int, String, String, DateTime, String, String)) = {
//    implicit def toRuleId(s:String) = RuleId(s)
//    implicit def toDirectiveId(s: String) = DirectiveId(s)
//    implicit def toNodeId(s: String) = NodeId(s)
//
//    Reports(t._1, t._2, t._3,t._4,t._5,t._6,t._7,t._8,t._9,t._10)
//  }
//
//  implicit def toNodeConfigIds(seq:Seq[(String, String)]) = seq.map(x => NodeAndConfigId(NodeId(x._1), NodeConfigId(x._2)))
//  implicit def toMapNodeConfig(seq:Seq[(String, String)]) = seq.map(x => (NodeId(x._1), Some(NodeConfigId(x._2)))).toMap
//
//  val run1 = DateTime.now.minusMinutes(5*5).withMillisOfSecond(123) //check that millis are actually used
//  val run2 = DateTime.now.minusMinutes(5*4)
//  val run3 = DateTime.now.minusMinutes(5*3)
//

//  def compareSlickER(report: DB.ExpectedReports[Long], expected: DB.ExpectedReports[Long]) = {
//    report.pkId === expected.pkId and
//    report.nodeJoinKey === expected.nodeJoinKey and
//    report.serial === expected.serial and
//    report.directiveId === expected.directiveId and
//    report.component === expected.component and
//    report.cardinality === expected.cardinality and
//    report.componentsValues === expected.componentsValues and
//    report.unexpandedComponentsValues === expected.unexpandedComponentsValues and
//    report.endDate === expected.endDate
//  }
//
//  "Finding nodes" should {
//
//    val strangeVersions = List(" abc" , "def " , "\nghi\t").map(NodeConfigId(_)).reverse //ghi is the most recent
  // note: in version, [a,b,c] means "c" is the most recent versions
  // in the unzserialized object, the most recent version is the HEAD of the list.
  // note: spaces are trimmed in version
//    val expectedReportsNodes: List[DB.ExpectedReportsNodes] = List(
//        DB.ExpectedReportsNodes(1, "n0", List())
//      , DB.ExpectedReportsNodes(1, "n1", NodeConfigVersionsSerializer.serialize(strangeVersions).toList.map(_.asInstanceOf[String]))
//      , DB.ExpectedReportsNodes(2, "n0", List("cba"))
//      , DB.ExpectedReportsNodes(3, "n2", List("xz"))
//      , DB.ExpectedReportsNodes(4, "n1", List("pqr", "mno"))
//    )

//    def getNodes(nodeJoinKeys : Set[Int]) : Box[Map[Int, Map[NodeId, List[NodeConfigId]]]] = {
//      if(nodeJoinKeys.isEmpty) Full(Map())
//      else (for {
//        x <- query[(Int, NodeConfigVersions)](s"""
//               select nodejoinkey, nodeid, nodeconfigids from expectedreportsnodes
//               where nodejoinkey in ${nodeJoinKeys.mkString("(", ",", ")")}
//             """).vector
//      } yield {
//        x.groupBy(_._2.nodeId).mapValues { seq => //seq cannot be empty due to groupBy
//          //merge version together based on nodejoin values
//          (seq.reduce[(Int, NodeConfigVersions)] { case ( (maxK, versions), (newK, newConfigVersions) ) =>
//            if(maxK >= newK) {
//              (maxK, versions.copy(versions = versions.versions ::: newConfigVersions.versions))
//            } else {
//              (newK, versions.copy(versions = newConfigVersions.versions ::: versions.versions))
//            }
//          })
//        }.values.groupBy(_._1).mapValues(_.map{case(_, NodeConfigVersions(id,v)) => (id,v)}.toMap)
//      }).transact(xa).either.attempt.unsafeRunSync
//    }

//    step {
//      DB.insertExpectedReportsNode(expectedReportsNodes).transact(xa).unsafeRunSync
//    }
//
//    "get back what was inserted" in {
//      DB.getExpectedReportsNode().transact(xa).unsafeRunSync must contain(exactly(expectedReportsNodes:_*))
//    }

//    "get in the same way" in {
//      val i = 1
//      //here, we get the Postgres string representation of ARRAYs
//      val res = sql"""select nodeid, nodeconfigids from expectedreportsnodes where nodeJoinKey = ${i}""".query[(String, String)].vector.transact(xa).unsafeRunSync
//
//      //the most recent must be in head of the array
//      res must contain(exactly( ("n0", "{}"), ("n1", "{ghi,def,abc}")  ))
//    }
//
//    "find the last reports for nodejoinkey" in {
//      val result = getNodes(Set(1)).openOrThrowException("Test failed with exception")
//      result.values.map( _.map { case (x,y) => NodeConfigVersions(x,y) } ).flatten.toSeq must contain(exactly(
//          NodeConfigVersions(NodeId("n0"), List())
//          //the order of values is important, as head is most recent
//        , NodeConfigVersions(NodeId("n1"), List("ghi", "def", "abc").map(NodeConfigId(_)))
//      ))
//    }
//
//    "correctly sort version for a node and several nodejoinkey" in {
//      val result = getNodes(Set(1,4)).openOrThrowException("Test failed with exception")
//      result.values.map( _.map { case (x,y) => NodeConfigVersions(x,y) } ).flatten.toSeq must contain(exactly(
//          NodeConfigVersions(NodeId("n0"), List())
//          //the order of values is important, as head is most recent
//        , NodeConfigVersions(NodeId("n1"), List("pqr","mno","ghi", "def", "abc").map(NodeConfigId(_)))
//      ))
//    }
//  }

  /*
   * Testing updates
   */
//  "Updating from a clean expected reports table" should {
//    step {
//      cleanTables
//      //reset nodeJoinKey sequence to 100
//      val qs = sql"alter sequence ruleversionid restart with 100" ::
//               sql"alter sequence ruleserialid restart with 100" :: Nil
//      qs.traverse( _.update.run ).transact(xa).unsafeRunSync
//
//    }
//    val r1 = RuleId("r1")
//    val serial = 42
//    val nodeConfigIds = List( ("n1", "n1_v1"), ("n2", "n2_v1") )
//    val c1 = ComponentExpectedReport("c1", 1, List("c1_v1"), List())
//    val d1 = DirectiveExpectedReports(DirectiveId("d1"), None, false, List(c1))
//    val directiveExpectedReports = List(d1)

//    val expected = DB.ExpectedReports[Long](100, 100, r1, serial, d1.directiveId
//      , c1.componentName, c1.cardinality, ComponentsValuesSerialiser.serializeComponents(c1.componentsValues)
//      , "[]", DateTime.now(DateTimeZone.UTC), None
//    )

//    "the first time, just insert" in {
//      val genTime = DateTime.now
//      val inserted = saveExpectedReports(r1, serial, genTime, directiveExpectedReports, nodeConfigIds, NodeConfigData.directives)

//      val reports = DB.getExpectedReports().transact(xa).unsafeRunSync
//      val nodes   = DB.getExpectedReportsNode().transact(xa).unsafeRunSync
//      val directiveOnNodes = List(DirectivesOnNodes(100, nodeConfigIds, directiveExpectedReports))

//      compareER(inserted.openOrThrowException("Test failed"), OldRuleExpectedReports(r1, serial, directiveOnNodes, genTime, None)) and
//      reports.size === 1 and compareSlickER(reports(0), expected) and
//      nodes.size === 2 and (nodes must contain(exactly(
//          DB.ExpectedReportsNodes(100, "n1", List("n1_v1"))
//        , DB.ExpectedReportsNodes(100, "n2", List("n2_v1"))
//      )))
//    }

//    "saving the same exactly, nothing change" in {
//      val genTime = DateTime.now

//      val inserted = expectedReportsRepo.saveExpectedReports(r1, serial, genTime, directiveExpectedReports, nodeConfigIds, NodeConfigData.directives)

//      val reports = DB.getExpectedReports().transact(xa).unsafeRunSync
//      val nodes   = DB.getExpectedReportsNode().transact(xa).unsafeRunSync
//
//      inserted.isInstanceOf[Failure] and
//      reports.size === 1 and compareSlickER(reports(0), expected) and
//      nodes.size === 2 and (nodes must contain(exactly(
//          DB.ExpectedReportsNodes(100, "n1", List("n1_v1"))
//        , DB.ExpectedReportsNodes(100, "n2", List("n2_v1"))
//      )))
//    }
//
//  }

  /*
   * Test full updates and overrides of unique techniques
   */
//  "Using the top update entry point" should {
//    step {
//      cleanTables
//      //reset nodeJoinKey sequence to 100
//      val qs = sql"alter sequence ruleversionid restart with 100" ::
//               sql"alter sequence ruleserialid restart with 100" :: Nil
//      qs.traverse( _.update.run ).transact(xa).unsafeRunSync
//
//    }
//
//    /*
//     * We want only one rule, with two directive, based on one
//     * unique Technique. There is also two node, one with both
//     * directive, the other with only one.
//     */
//
//    val fooSpec = InputVariableSpec("foo", "")
//    val tech = Technique(
//        TechniqueId(TechniqueName("tech"), TechniqueVersion("1.0"))
//      , "tech"
//      , "description"
//      , List()
//      , List()
//      , List()
//      , TrackerVariableSpec()
//      , SectionSpec("root", isComponent = true, componentKey = Some("foo"), children = List(fooSpec))
//      , None
//      , isMultiInstance = false
//    )
//
//    def buildDirective(id: String, priority: Int) = {
//      val vars = Map("foo" -> fooSpec.toVariable(List(id + "_value")))
//
//      ExpandedUnboundBoundedPolicyDraft(
//          tech
//        , DirectiveId(id)
//        , priority
//        , tech.trackerVariableSpec.toVariable()
//        , vars, vars
//      )
//    }
//    //d1 is more prioritary than d2
//    val d1 = buildDirective("d1", 0)
//    val d2 = buildDirective("d2", 5)
//
//    val n1 = NodeAndConfigId(NodeId("n1"), NodeConfigId("n1_v0"))
//    val n2 = NodeAndConfigId(NodeId("n2"), NodeConfigId("n2_v0"))
//    val r1 = RuleId("r1")
//    val serial = 42
//
//    val rule = ExpandedRuleVal(
//        r1, serial
//      , Map(n1 -> List(d1, d2) , n2 -> List(d2))
//    )
//
//    //and so, on n1, we have an override:
//    val n1_overrides = UniqueOverrides(n1.nodeId, r1, DirectiveId("d2"), PolicyId(r1, DirectiveId("d1")))
//
//    /*
//     * And now, for the expectations
//     */
//    val genTime = DateTime.now
//    val d1_exp = DirectiveExpectedReports(DirectiveId("d1"), None, List(ComponentExpectedReport("root", 1, List("d1_value"), List("d1_value"))))
//    val d2_exp = DirectiveExpectedReports(DirectiveId("d2"), None, List(ComponentExpectedReport("root", 1, List("d2_value"), List("d2_value"))))
//    val expectedRule = OldRuleExpectedReports(r1, serial, List(
//        DirectivesOnNodes(100, Map(n1.nodeId -> Some(n1.version)), List(d1_exp) )
//      , DirectivesOnNodes(101, Map(n2.nodeId -> Some(n2.version)), List(d2_exp) )
//    ), genTime, None)
//
//    val expected = {
//      val c1 = d1_exp.components(0)
//      DB.ExpectedReports[Long](100, 100, r1, serial, d1.directiveId
//      , c1.componentName, c1.cardinality, ComponentsValuesSerialiser.serializeComponents(c1.componentsValues)
//      , """["d1_value"]""", DateTime.now(DateTimeZone.UTC), None
//      )
//    }

//    "Check that we have the expected info in base" in {
//      val inserted = updateExpectedService.updateExpectedReports(
//          List(rule)
//        , List()
//        , Map(n1.nodeId -> n1.version, n2.nodeId -> n2.version)
//        , genTime, Set(n1_overrides)
//        , NodeConfigData.directives
//        , GlobalPolicyMode(PolicyMode.Audit, PolicyModeOverrides.Always)
//        , Map(n1.nodeId -> None, n2.nodeId -> None)
//      )
//
//      val reports = DB.getExpectedReports().transact(xa).unsafeRunSync
//      val nodes   = DB.getExpectedReportsNode().transact(xa).unsafeRunSync
//
//      compareER(inserted.openOrThrowException("Test failed")(0), expectedRule) and
//      reports.size === 2 and compareSlickER(reports(0), expected) and
//      nodes.size === 2 and (nodes must contain(exactly(
//          DB.ExpectedReportsNodes(100, "n1", List("n1_v0"))
//        , DB.ExpectedReportsNodes(101, "n2", List("n2_v0"))
//      )))
//    }
//  }

}
