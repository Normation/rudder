/*
*************************************************************************************
* Copyright 2018 Normation SAS
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

package com.normation.rudder.db

import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import java.util.Properties

import com.normation.rudder.db.Doobie.{DateTimeMeta, slf4jDoobieLogger}
import com.normation.ldap.sdk.BuildFilter
import com.normation.ldap.sdk.ROPooledSimpleAuthConnectionProvider
import com.normation.ldap.sdk.RWPooledSimpleAuthConnectionProvider
import com.normation.rudder.domain.NodeDit
import com.normation.rudder.domain.RudderDit
import com.normation.rudder.domain.reports.ComplianceLevel
import com.normation.rudder.repository.jdbc.RudderDatasourceProvider
import doobie.imports._
import scalaz._
import scalaz.Scalaz._
import doobie.postgres._
import doobie.postgres.imports._
import com.unboundid.ldap.sdk.DN
import net.liftweb.common.Box
import net.liftweb.common.EmptyBox
import net.liftweb.common.Full
import org.joda.time.DateTime

import scala.util.Random


/*
 * Allow to generate false compliance for testing purpose
 */
object GenerateCompliance {



  lazy val properties = {
    val p = new Properties()
    val in = new ByteArrayInputStream(
      """ldap.host=localhost
        |ldap.port=1389
        |ldap.authdn=cn=manager,cn=rudder-configuration
        |ldap.authpw=secret
        |ldap.rudder.base=ou=Rudder, cn=rudder-configuration
        |ldap.node.base=cn=rudder-configuration
        |rudder.jdbc.driver=org.postgresql.Driver
        |rudder.jdbc.url=jdbc:postgresql://localhost:15432/rudder
        |rudder.jdbc.username=rudder
        |rudder.jdbc.password=Normation
        |rudder.jdbc.maxPoolSize=25
      """.stripMargin.getBytes(StandardCharsets.UTF_8))
    p.load(in)
    in.close
    p
  }

  // init DB and repositories
  lazy val dataSource = {
    val config = new RudderDatasourceProvider(
        properties.getProperty("rudder.jdbc.driver")
      , properties.getProperty("rudder.jdbc.url")
      , properties.getProperty("rudder.jdbc.username")
      , properties.getProperty("rudder.jdbc.password")
      , properties.getProperty("rudder.jdbc.maxPoolSize").toInt
    )
    config.datasource
  }

  lazy val doobie = new Doobie(dataSource)
  import doobie._

  lazy val roLdap =
    new ROPooledSimpleAuthConnectionProvider(
      properties.getProperty("ldap.authdn")
    , properties.getProperty("ldap.authpw")
    , properties.getProperty("ldap.host")
    , properties.getProperty("ldap.port").toInt
    , poolSize = 2
  )
  lazy val rwLdap =
    new RWPooledSimpleAuthConnectionProvider(
      properties.getProperty("ldap.authdn")
    , properties.getProperty("ldap.authpw")
    , properties.getProperty("ldap.host")
    , properties.getProperty("ldap.port").toInt
    , poolSize = 2
    )

  lazy val rudderDit = new RudderDit(new DN(properties.getProperty("ldap.rudder.base")))
  lazy val nodesDit  = new NodeDit  (new DN(properties.getProperty("ldap.node.base")))

  final case class Rule(ruleId: String, directivesIds: Seq[String])

  def getBox[T](b: Box[T], msg: String) = b match {
    case eb: EmptyBox =>
      val e = eb ?~! msg
      System.err.println(e.messageChain)
      e.rootExceptionCause.foreach(ex => ex.printStackTrace())
      throw new RuntimeException("Irrecoverable error")
    case Full(x)  => x
  }

  // node config are a set of (rule ids -> {directive ids])
  final case class NodeConfig(rules: Seq[Rule])


  /*
   * Given a set of rules and directive, generate a random node compliance
   */
  def newNodeConfig(availableRules: Seq[Rule]): NodeConfig = {

    // get a random rule from the list.
    // Return the chosen rule and the list without it
    def getOne(rules: Seq[Rule]): (Rule, Seq[Rule]) = {
      val choose = Random.nextInt(rules.size)
      val (a, b) = rules.splitAt(choose)
      (b.head, a ++ b.tail)
    }

    def recGetOne(available: Seq[Rule], chosen: Seq[Rule], remains: Int): NodeConfig = {
      if(remains <= 0) {
        NodeConfig(chosen)
      } else {
        val (r, others) = getOne(available)
        recGetOne(others, chosen :+ r, remains - 1)
      }
    }

    // nulber of rules: between 1 and 5
    val maxRules = Math.min(availableRules.size, 5)
    val nbRules = Random.nextInt(maxRules) + 1

    recGetOne(availableRules, Nil, nbRules)
  }

  def newNodeCompliance(nodeId: String, time: DateTime, config: NodeConfig): RunCompliance = {
    def getComplianceLevel() = ComplianceLevel(
        pending            = Random.nextInt(20) + 1
      , success            = Random.nextInt(20) + 1
      , repaired           = Random.nextInt(20) + 1
      , error              = Random.nextInt(20) + 1
      , unexpected         = Random.nextInt(20) + 1
      , missing            = Random.nextInt(20) + 1
      , noAnswer           = Random.nextInt(20) + 1
      , notApplicable      = Random.nextInt(20) + 1
      , reportsDisabled    = Random.nextInt(20) + 1
      , compliant          = Random.nextInt(20) + 1
      , auditNotApplicable = Random.nextInt(20) + 1
      , nonCompliant       = Random.nextInt(20) + 1
      , auditError         = Random.nextInt(20) + 1
      , badPolicyMode      = Random.nextInt(20) + 1
    )
    RunCompliance(nodeId, time, config.rules.map { r =>
      val directives = r.directivesIds.map(d => DirectiveCompliance(d, getComplianceLevel()))
      RuleCompliance(
          r.ruleId
        , ComplianceLevel.sum(directives.map(_.compliance))
        , directives
      )
    })
  }


  final case class RunCompliance(
      nodeId : String
    , runtime: DateTime
    , rules  : Seq[RuleCompliance]
  )

  final case class RuleCompliance(
      ruleId    : String
    , compliance: ComplianceLevel
    , directives: Seq[DirectiveCompliance]
  )

  final case class DirectiveCompliance(
      directiveId: String
    , compliance : ComplianceLevel
  )


  def saveRunCompliances(runs: Seq[RunCompliance]) = {

    /*
     * Array of composite type are not supported by JDBC driver which only see
     * them as array of string (congrats).
     * So we will just write it down.
     *
     * The simplest syntax to get correct quoting is:
     * insert into nodecompliancecomposite (nodeid, runtimestamp, details) values (
     *   'some-node-id', '2018-05-02T00:00:00.002+02:00',
     *   array[ROW('ruleid','directiveid',array[1,2,3,4])::compliance]
     * );
     *
     */

    def rules(rules: Seq[RuleCompliance]) = {
      def comp(c: ComplianceLevel) = fr"array[${c.pending}, ${c.success}, ${c.repaired}, ${c.error}, ${c.unexpected}, ${c.missing}, ${c.noAnswer}, ${c.notApplicable}, ${c.reportsDisabled}, ${c.compliant}, ${c.auditNotApplicable}, ${c.nonCompliant}, ${c.auditError}, ${c.badPolicyMode}]"

      //build array:
      //array[ROW('rid','did',array[1,2,3])::compliance, ROW('rid','did',array[1,2,3])::compliance]
      fr"array[" ++ rules.flatMap(rule => rule.directives.map(d => fr"ROW(${rule.ruleId}, ${d.directiveId}, " ++ comp(d.compliance) ++ fr")::compliance")).reduce(_ ++ fr", " ++ _) ++ fr"]"
    }

    def query(run: RunCompliance) = {
      (fr"insert into nodecompliancecomposite (nodeid, runtimestamp, details) values (${run.nodeId}, ${run.runtime}, " ++ rules(run.rules) ++ fr")").update
    }

    runs.toList.traverse(r => query(r).run).transact(xa).unsafePerformSync
  }


  /*
   * As jdbc is not able to transport Array of compiste object, we are
   * going to unfold it. For that, we need that request:
   *
   * ----
   * with compliance as (select nodeid, runtimestamp, (r).* from (select nodeid, runtimestamp, unnest(details) AS r from nodecompliancecomposite) as x) select * from compliance;
   * ----
   * where compliance is the table with rows [nodeid, runtimestamp, ruleid, directiveid, compliancelevel::array[Int]]
   *
   * Return compliance by node, for rule, in interval
   */
  type RES = (String, DateTime, String, String, Array[Int])
  def getCompliance(startDate: DateTime, endDate: DateTime, ruleId: Option[String], nodeId: Option[String]): Vector[RES] = {

    val base = fr"""with compliance as (
                      select nodeid, runtimestamp, (r).* from (
                        select nodeid, runtimestamp, unnest(details) AS r from nodecompliancecomposite
                      ) as x)
                    select * from compliance where runtimestamp >= ${startDate} and runtimestamp <= ${endDate}
      """

    val optNode = nodeId.fold(Fragment.empty)((id: String) => fr"and nodeid = ${id}")
    val optRule = ruleId.fold(Fragment.empty)((id: String) => fr"and ruleid = ${id}")


    val query = (base ++ optNode ++ optRule).query[RES]

    query.vector.transact(xa).unsafePerformSync

  }

  def main(args: Array[String]): Unit = {
    import BuildFilter._

    val rules = getBox(for {
      ldap  <- roLdap
    } yield {
      ldap.searchOne(rudderDit.RULES.dn, AND(IS("rule"), EQ("isEnabled", "TRUE"), NOT(EQ("isSystem", "TRUE"))), "ruleId", "directiveId").map(e => Rule(e("ruleId").getOrElse(""), e.valuesFor("directiveID").toSeq.sorted)).filter(x => x.ruleId.nonEmpty && x.directivesIds.nonEmpty)
    }, "Error when recovering the set of user defined rules")

    // only nodeIds
    val nodeIds = getBox(for {
      ldap  <- roLdap
    } yield {
      ldap.searchOne(nodesDit.NODES.dn, IS("rudderNode"), "nodeId").map(e => e("nodeId").getOrElse("")).filter(x => x.nonEmpty)
    }, "Error when recovering the set of nodes")

    //println(rules.mkString("\n"))
    //println(nodeIds.mkString("\n"))

    val now = DateTime.now

    val compliances =
      for {
        i <- 0 until nodeIds.size
        j <- 100 to 0 by -1
      } yield {
        newNodeCompliance(nodeIds(i), now.minusMinutes(5*j).minusMillis(Random.nextInt(1000)), newNodeConfig(rules))
      }

//    var i = 0
//    compliances.grouped(1000).foreach { slice =>
//      println("now at " + i*1000)
//      saveRunCompliances(slice)
//      i += 1
//    }
//
//    println("saved, now query!")
    val t0 = DateTime.now
    val res = getCompliance(now.minusDays(2), now, None, None)
    println(s"found ${res.size}")
    println(s"done in ${DateTime.now.getMillis - t0.getMillis} ms")
  }
}
