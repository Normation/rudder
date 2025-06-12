package com.normation.rudder.domain.reports

/*
 *************************************************************************************
 * Copyright 2024 Normation SAS
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

import com.normation.cfclerk.domain.*
import com.normation.cfclerk.domain.ReportingLogic.*
import com.normation.inventory.domain.*
import com.normation.rudder.domain.policies.*
import com.normation.rudder.domain.reports.JsonPostgresqlSerialization.*
import com.normation.rudder.domain.reports.ReportType.*
import com.normation.rudder.domain.reports.RunAnalysisKind.*
import com.normation.utils.DateFormaterService
import org.joda.time.DateTime
import org.junit.runner.RunWith
import org.specs2.mutable.*
import org.specs2.runner.*
import zio.json.*

/**
 * Test properties about agent type, especially regarding serialisation
 */
@RunWith(classOf[JUnitRunner])
class JsonPostresqlSerializationTest extends Specification {

  implicit class ForgeGet[A](x: Either[String, A]) {
    def forceGet: A = {
      x match {
        case Left(err) => throw new RuntimeException(err)
        case Right(v)  => v
      }
    }
  }

  implicit class ToId(s: String) {
    def rid: RuleId         = RuleId(RuleUid(s))
    def did: DirectiveId    = DirectiveId(DirectiveUid(s))
    def tid: TechniqueId    = TechniqueId(TechniqueName(s), TechniqueVersion.V1_0)
    def pt:  PolicyTypeName = PolicyTypeName(s)
    def cid: NodeConfigId   = NodeConfigId(s)
    def nid: NodeId         = NodeId(s)
  }

  def runInfo(
      kind:                RunAnalysisKind,
      expectedConfigId:    Option[NodeConfigId] = None,
      expectedConfigStart: Option[DateTime] = None,
      expirationDateTime:  Option[DateTime] = None,
      expiredSince:        Option[DateTime] = None,
      lastRunDateTime:     Option[DateTime] = None,
      lastRunConfigId:     Option[NodeConfigId] = None,
      lastRunExpiration:   Option[DateTime] = None
  ) = RunAnalysis(
    kind,
    expectedConfigId,
    expectedConfigStart,
    expirationDateTime,
    expiredSince,
    lastRunDateTime,
    lastRunConfigId,
    lastRunExpiration
  )

  def buildReport(runAnalysis: RunAnalysis, reports: Map[PolicyTypeName, AggregatedStatusReport])(implicit
      nid: NodeId
  ): NodeStatusReport =
    NodeStatusReport(nid, runAnalysis, RunComplianceInfo.OK, reports)

  /*
   * Create a reports of:
   * tagName -> (
   *   ruleId, lastRun, configId, expiration,
   *   (directive -> (optOverridingRule, [components]))
   * )
   */
  def reports(
      rs: Map[String, Seq[
        (String, Option[DateTime], Option[NodeConfigId], DateTime, Map[String, (Option[String], List[ComponentStatusReport])])
      ]]
  )(implicit nid: NodeId): Map[PolicyTypeName, AggregatedStatusReport] = {
    rs.map {
      case (pt, rs) =>
        (
          pt.pt,
          AggregatedStatusReport(rs.map {
            case (rid, art, cid, exp, ds) =>
              RuleNodeStatusReport(
                nid,
                rid.rid,
                pt.pt,
                art,
                cid,
                ds.map {
                  case (id, (ov, x)) => (id.did, DirectiveStatusReport(id.did, PolicyTypes.fromTypes(pt.pt), ov.map(_.rid), x))
                },
                exp
              )
          })
        )
    }
  }

//  val expired: DateTime = DateTime.now.minusMinutes(5)
//  val stillOk: DateTime = DateTime.now.plusMinutes(5)

  def block(cn: String, reportingLogic: ReportingLogic, sub: List[ComponentStatusReport]): ComponentStatusReport = {
    BlockStatusReport(
      cn,
      reportingLogic,
      sub
    )
  }
  def comp(cn: String, values: List[ComponentValueStatusReport]):                          ComponentStatusReport =
    ValueStatusReport(cn, s"exp-${cn}", values)

  def value(cn: String, msgs: MessageStatusReport*): ComponentValueStatusReport = {
    ComponentValueStatusReport(
      cn,
      s"expected-${cn}",
      s"reportId-${cn}",
      msgs.toList
    )
  }

  def res(tpe: ReportType, msg: String): MessageStatusReport = {
    MessageStatusReport(
      tpe,
      msg.strip() match {
        case "" => None
        case _  => Some(msg)
      }
    )
  }

  val date0 = new DateTime(0)
  val tConfig0Start: Option[DateTime]     = DateFormaterService.parseDate("2024-01-01T01:00:00Z").toOption
  val tConfig0End:   Option[DateTime]     = None
  val lastRun0:      Option[DateTime]     = DateFormaterService.parseDate("2024-01-05T05:05:00Z").toOption
  val config0:       Option[NodeConfigId] = Some(NodeConfigId("config0_0"))

  val exp: DateTime = DateFormaterService.parseDate("2024-01-12T03:03:03Z").getOrElse(throw new RuntimeException("bad date"))

  val run0: RunAnalysis = runInfo(
    ComputeCompliance,
    expectedConfigStart = tConfig0Start,
    lastRunDateTime = lastRun0,
    expectedConfigId = config0,
    lastRunConfigId = config0
  )

  implicit val n0: NodeId           = "n0".nid
  val nsr1:        NodeStatusReport = buildReport(
    run0,
    reports(
      Map(
        "system" -> List(
          (
            "rule0",
            lastRun0,
            config0,
            exp,
            Map(
              "directive0" -> (None, List(
                block(
                  "block0",
                  WeightedReport,
                  List(
                    block(
                      "block1",
                      WorstReportWeightedOne,
                      List(
                        comp(
                          "component1",
                          List(
                            value("check1", res(EnforceSuccess, "check 1#1 is valid"), res(EnforceSuccess, "check 1#2 is valid")),
                            value("check2", res(EnforceError, "check 2 failed"))
                          )
                        ),
                        comp("component2", List(value("check3", res(AuditCompliant, "check 3 is compliant"))))
                      )
                    ),
                    block(
                      "block2",
                      WeightedReport,
                      List(
                        comp("component3", List(value("check2.1", res(EnforceRepaired, "check 2.1 is repaired")))),
                        comp("component4", List(value("check2.2", res(EnforceRepaired, "check 2.2 is repaired"))))
                      )
                    )
                  )
                )
              )),
              "directive1" -> (None, List(
                block(
                  "block3",
                  FocusReport("check4"),
                  List(
                    comp(
                      "component5",
                      List(
                        value("check4", res(EnforceRepaired, "check 4 is repaired")),
                        value("check5", res(EnforceNotApplicable, "check 5 is N/A")),
                        value("check6", res(EnforceError, "check 6 is in error"))
                      )
                    )
                  )
                )
              ))
            )
          )
        ),
        "user"   -> List(("rule1", lastRun0, config0, exp, Map("directive2" -> (Some("rule2"), List()))))
      )
    )
  )

  def toJson(r:   NodeStatusReport): String            = JNodeStatusReport.from(r).toJsonPretty
  def fromJson(s: String):           JNodeStatusReport = s.fromJson[JNodeStatusReport].forceGet
  // trim each lines
  def clean(s:    String):           String            = s.linesIterator.map(_.strip()).mkString("\n")

  "A simple run serialization must work" >> {
    clean(toJson(nsr1)) === clean(ExpectedJson.test1)
  }

  // compare the "J" object to have case class all the way down
  "A simple run deserialization must work" >> {
    fromJson(ExpectedJson.test1) === JNodeStatusReport.from(nsr1)
  }

  "Old Rudder 8.2 serialization of ReportType must be readable" >> {
    fromJson(ExpectedJson.rudder82ReportType) === JNodeStatusReport.from(nsr1)
  }

}

object ExpectedJson {

  val test1 = {
    """{
      |  "nid" : "n0",
      |  "ri" : {
      |    "k" : "ComputeCompliance",
      |    "ecid" : "config0_0",
      |    "ecs" : "2024-01-01T01:00:00Z",
      |    "rt" : "2024-01-05T05:05:00Z",
      |    "rid" : "config0_0"
      |  },
      |  "si" : {
      |    "OK" : {}
      |  },
      |  "rs" : [
      |    ["system", {
      |      "rnsrs" : [
      |        {
      |          "nid" : "n0",
      |          "rid" : "rule0",
      |          "ct" : "system",
      |          "art" : "2024-01-05T05:05:00Z",
      |          "cid" : "config0_0",
      |          "exp" : "2024-01-12T03:03:03Z",
      |          "dsrs" : [
      |            {
      |              "did" : "directive0",
      |              "pts" : [
      |                "system"
      |              ],
      |              "csrs" : [
      |                {
      |                  "bsr" : {
      |                    "cn" : "block0",
      |                    "rl" : "weighted",
      |                    "csrs" : [
      |                      {
      |                        "bsr" : {
      |                          "cn" : "block2",
      |                          "rl" : "weighted",
      |                          "csrs" : [
      |                            {
      |                              "vsr" : {
      |                                "cn" : "component3",
      |                                "ecn" : "exp-component3",
      |                                "cvsrs" : [
      |                                  {
      |                                    "cn" : "check2.1",
      |                                    "ecn" : "expected-check2.1",
      |                                    "rtid" : "reportId-check2.1",
      |                                    "msrs" : [
      |                                      {
      |                                        "rt" : "EnforceRepaired",
      |                                        "m" : "check 2.1 is repaired"
      |                                      }
      |                                    ]
      |                                  }
      |                                ]
      |                              }
      |                            },
      |                            {
      |                              "vsr" : {
      |                                "cn" : "component4",
      |                                "ecn" : "exp-component4",
      |                                "cvsrs" : [
      |                                  {
      |                                    "cn" : "check2.2",
      |                                    "ecn" : "expected-check2.2",
      |                                    "rtid" : "reportId-check2.2",
      |                                    "msrs" : [
      |                                      {
      |                                        "rt" : "EnforceRepaired",
      |                                        "m" : "check 2.2 is repaired"
      |                                      }
      |                                    ]
      |                                  }
      |                                ]
      |                              }
      |                            }
      |                          ]
      |                        }
      |                      },
      |                      {
      |                        "bsr" : {
      |                          "cn" : "block1",
      |                          "rl" : "worst-case-weighted-one",
      |                          "csrs" : [
      |                            {
      |                              "vsr" : {
      |                                "cn" : "component2",
      |                                "ecn" : "exp-component2",
      |                                "cvsrs" : [
      |                                  {
      |                                    "cn" : "check3",
      |                                    "ecn" : "expected-check3",
      |                                    "rtid" : "reportId-check3",
      |                                    "msrs" : [
      |                                      {
      |                                        "rt" : "AuditCompliant",
      |                                        "m" : "check 3 is compliant"
      |                                      }
      |                                    ]
      |                                  }
      |                                ]
      |                              }
      |                            },
      |                            {
      |                              "vsr" : {
      |                                "cn" : "component1",
      |                                "ecn" : "exp-component1",
      |                                "cvsrs" : [
      |                                  {
      |                                    "cn" : "check2",
      |                                    "ecn" : "expected-check2",
      |                                    "rtid" : "reportId-check2",
      |                                    "msrs" : [
      |                                      {
      |                                        "rt" : "EnforceError",
      |                                        "m" : "check 2 failed"
      |                                      }
      |                                    ]
      |                                  },
      |                                  {
      |                                    "cn" : "check1",
      |                                    "ecn" : "expected-check1",
      |                                    "rtid" : "reportId-check1",
      |                                    "msrs" : [
      |                                      {
      |                                        "rt" : "EnforceSuccess",
      |                                        "m" : "check 1#1 is valid"
      |                                      },
      |                                      {
      |                                        "rt" : "EnforceSuccess",
      |                                        "m" : "check 1#2 is valid"
      |                                      }
      |                                    ]
      |                                  }
      |                                ]
      |                              }
      |                            }
      |                          ]
      |                        }
      |                      }
      |                    ]
      |                  }
      |                }
      |              ]
      |            },
      |            {
      |              "did" : "directive1",
      |              "pts" : [
      |                "system"
      |              ],
      |              "csrs" : [
      |                {
      |                  "bsr" : {
      |                    "cn" : "block3",
      |                    "rl" : "focus:check4",
      |                    "csrs" : [
      |                      {
      |                        "vsr" : {
      |                          "cn" : "component5",
      |                          "ecn" : "exp-component5",
      |                          "cvsrs" : [
      |                            {
      |                              "cn" : "check6",
      |                              "ecn" : "expected-check6",
      |                              "rtid" : "reportId-check6",
      |                              "msrs" : [
      |                                {
      |                                  "rt" : "EnforceError",
      |                                  "m" : "check 6 is in error"
      |                                }
      |                              ]
      |                            },
      |                            {
      |                              "cn" : "check5",
      |                              "ecn" : "expected-check5",
      |                              "rtid" : "reportId-check5",
      |                              "msrs" : [
      |                                {
      |                                  "rt" : "EnforceNotApplicable",
      |                                  "m" : "check 5 is N/A"
      |                                }
      |                              ]
      |                            },
      |                            {
      |                              "cn" : "check4",
      |                              "ecn" : "expected-check4",
      |                              "rtid" : "reportId-check4",
      |                              "msrs" : [
      |                                {
      |                                  "rt" : "EnforceRepaired",
      |                                  "m" : "check 4 is repaired"
      |                                }
      |                              ]
      |                            }
      |                          ]
      |                        }
      |                      }
      |                    ]
      |                  }
      |                }
      |              ]
      |            }
      |          ]
      |        }
      |      ]
      |    }],
      |    ["user", {
      |       "rnsrs" : [
      |         {
      |           "nid" : "n0",
      |           "rid" : "rule1",
      |           "ct" : "user",
      |           "art" : "2024-01-05T05:05:00Z",
      |           "cid" : "config0_0",
      |           "exp" : "2024-01-12T03:03:03Z",
      |           "dsrs" : [
      |             {
      |               "did" : "directive2",
      |               "pts" : [
      |                 "user"
      |               ],
      |               "o" : "rule2",
      |               "csrs" : []
      |             }
      |           ]
      |         }
      |       ]
      |    }]
      |  ]
      |}
      |""".stripMargin
  }

  val rudder82ReportType = {
    """{
      |  "nid" : "n0",
      |  "ri" : {
      |    "k" : "ComputeCompliance",
      |    "ecid" : "config0_0",
      |    "ecs" : "2024-01-01T01:00:00Z",
      |    "rt" : "2024-01-05T05:05:00Z",
      |    "rid" : "config0_0"
      |  },
      |  "si" : {
      |    "OK" : {}
      |  },
      |  "rs" : [
      |    ["system", {
      |      "rnsrs" : [
      |        {
      |          "nid" : "n0",
      |          "rid" : "rule0",
      |          "ct" : "system",
      |          "art" : "2024-01-05T05:05:00Z",
      |          "cid" : "config0_0",
      |          "exp" : "2024-01-12T03:03:03Z",
      |          "dsrs" : [
      |            {
      |              "did" : "directive0",
      |              "pts" : [
      |                "system"
      |              ],
      |              "csrs" : [
      |                {
      |                  "bsr" : {
      |                    "cn" : "block0",
      |                    "rl" : "weighted",
      |                    "csrs" : [
      |                      {
      |                        "bsr" : {
      |                          "cn" : "block2",
      |                          "rl" : "weighted",
      |                          "csrs" : [
      |                            {
      |                              "vsr" : {
      |                                "cn" : "component3",
      |                                "ecn" : "exp-component3",
      |                                "cvsrs" : [
      |                                  {
      |                                    "cn" : "check2.1",
      |                                    "ecn" : "expected-check2.1",
      |                                    "rtid" : "reportId-check2.1",
      |                                    "msrs" : [
      |                                      {
      |                                        "rt" : "EnforceRepaired",
      |                                        "m" : "check 2.1 is repaired"
      |                                      }
      |                                    ]
      |                                  }
      |                                ]
      |                              }
      |                            },
      |                            {
      |                              "vsr" : {
      |                                "cn" : "component4",
      |                                "ecn" : "exp-component4",
      |                                "cvsrs" : [
      |                                  {
      |                                    "cn" : "check2.2",
      |                                    "ecn" : "expected-check2.2",
      |                                    "rtid" : "reportId-check2.2",
      |                                    "msrs" : [
      |                                      {
      |                                        "rt" : "EnforceRepaired",
      |                                        "m" : "check 2.2 is repaired"
      |                                      }
      |                                    ]
      |                                  }
      |                                ]
      |                              }
      |                            }
      |                          ]
      |                        }
      |                      },
      |                      {
      |                        "bsr" : {
      |                          "cn" : "block1",
      |                          "rl" : "worst-case-weighted-one",
      |                          "csrs" : [
      |                            {
      |                              "vsr" : {
      |                                "cn" : "component2",
      |                                "ecn" : "exp-component2",
      |                                "cvsrs" : [
      |                                  {
      |                                    "cn" : "check3",
      |                                    "ecn" : "expected-check3",
      |                                    "rtid" : "reportId-check3",
      |                                    "msrs" : [
      |                                      {
      |                                        "rt" : "AuditCompliant",
      |                                        "m" : "check 3 is compliant"
      |                                      }
      |                                    ]
      |                                  }
      |                                ]
      |                              }
      |                            },
      |                            {
      |                              "vsr" : {
      |                                "cn" : "component1",
      |                                "ecn" : "exp-component1",
      |                                "cvsrs" : [
      |                                  {
      |                                    "cn" : "check2",
      |                                    "ecn" : "expected-check2",
      |                                    "rtid" : "reportId-check2",
      |                                    "msrs" : [
      |                                      {
      |                                        "rt" : "EnforceError",
      |                                        "m" : "check 2 failed"
      |                                      }
      |                                    ]
      |                                  },
      |                                  {
      |                                    "cn" : "check1",
      |                                    "ecn" : "expected-check1",
      |                                    "rtid" : "reportId-check1",
      |                                    "msrs" : [
      |                                      {
      |                                        "rt" : "EnforceSuccess",
      |                                        "m" : "check 1#1 is valid"
      |                                      },
      |                                      {
      |                                        "rt" : "EnforceSuccess",
      |                                        "m" : "check 1#2 is valid"
      |                                      }
      |                                    ]
      |                                  }
      |                                ]
      |                              }
      |                            }
      |                          ]
      |                        }
      |                      }
      |                    ]
      |                  }
      |                }
      |              ]
      |            },
      |            {
      |              "did" : "directive1",
      |              "pts" : [
      |                "system"
      |              ],
      |              "csrs" : [
      |                {
      |                  "bsr" : {
      |                    "cn" : "block3",
      |                    "rl" : "focus:check4",
      |                    "csrs" : [
      |                      {
      |                        "vsr" : {
      |                          "cn" : "component5",
      |                          "ecn" : "exp-component5",
      |                          "cvsrs" : [
      |                            {
      |                              "cn" : "check6",
      |                              "ecn" : "expected-check6",
      |                              "rtid" : "reportId-check6",
      |                              "msrs" : [
      |                                {
      |                                  "rt" : "EnforceError",
      |                                  "m" : "check 6 is in error"
      |                                }
      |                              ]
      |                            },
      |                            {
      |                              "cn" : "check5",
      |                              "ecn" : "expected-check5",
      |                              "rtid" : "reportId-check5",
      |                              "msrs" : [
      |                                {
      |                                  "rt" : "EnforceNotApplicable",
      |                                  "m" : "check 5 is N/A"
      |                                }
      |                              ]
      |                            },
      |                            {
      |                              "cn" : "check4",
      |                              "ecn" : "expected-check4",
      |                              "rtid" : "reportId-check4",
      |                              "msrs" : [
      |                                {
      |                                  "rt" : "EnforceRepaired",
      |                                  "m" : "check 4 is repaired"
      |                                }
      |                              ]
      |                            }
      |                          ]
      |                        }
      |                      }
      |                    ]
      |                  }
      |                }
      |              ]
      |            }
      |          ]
      |        }
      |      ]
      |    }],
      |    ["user", {
      |       "rnsrs" : [
      |         {
      |           "nid" : "n0",
      |           "rid" : "rule1",
      |           "ct" : "user",
      |           "art" : "2024-01-05T05:05:00Z",
      |           "cid" : "config0_0",
      |           "exp" : "2024-01-12T03:03:03Z",
      |           "dsrs" : [
      |             {
      |               "did" : "directive2",
      |               "pts" : [
      |                 "user"
      |               ],
      |               "o" : "rule2",
      |               "csrs" : []
      |             }
      |           ]
      |         }
      |       ]
      |    }]
      |  ]
      |}
      |""".stripMargin
  }

}
