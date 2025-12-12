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

package com.normation.rudder.domain.reports

import com.normation.cfclerk.domain.ReportingLogic
import com.normation.cfclerk.domain.TechniqueVersion
import com.normation.inventory.domain.NodeId
import com.normation.rudder.domain.Constants
import com.normation.rudder.domain.policies.DirectiveId
import com.normation.rudder.domain.policies.GlobalPolicyMode
import com.normation.rudder.domain.policies.PolicyMode
import com.normation.rudder.domain.policies.PolicyModeOverrides
import com.normation.rudder.domain.policies.PolicyModeOverrides.Always
import com.normation.rudder.domain.policies.PolicyModeOverrides.Unoverridable
import com.normation.rudder.domain.policies.PolicyTypes
import com.normation.rudder.domain.policies.RuleId
import com.normation.rudder.reports.AgentRunInterval
import com.normation.rudder.reports.ComplianceMode
import com.normation.rudder.reports.ComplianceModeName
import com.normation.rudder.reports.GlobalComplianceMode
import com.normation.rudder.reports.ResolvedAgentRunInterval
import com.normation.rudder.services.policies.PolicyId
import net.liftweb.common.Box
import net.liftweb.common.Failure
import net.liftweb.common.Full
import org.joda.time.DateTime
import org.joda.time.Duration
import scala.annotation.nowarn
import zio.json.*
import zio.json.internal.Write

final case class NodeModeConfig(
    globalComplianceMode: ComplianceMode,
    globalAgentRun:       AgentRunInterval,
    nodeAgentRun:         Option[AgentRunInterval], // if Some and overrides = false, behave like none.

    globalPolicyMode: GlobalPolicyMode,
    nodePolicyMode:   Option[PolicyMode]
)

/*
 * A place where we store overridden directive. We keep what rule->directive
 * is overridden by what other rule->directive
 */
final case class OverriddenPolicy(
    policy:       PolicyId,
    overriddenBy: PolicyId
)

final case class NodeExpectedReports(
    nodeId:              NodeId,
    nodeConfigId:        NodeConfigId,
    beginDate:           DateTime,
    endDate:             Option[DateTime],
    modes:               NodeModeConfig,
    ruleExpectedReports: List[RuleExpectedReports],
    overrides:           List[OverriddenPolicy]
) {

  def configInfo: NodeConfigIdInfo = NodeConfigIdInfo(nodeConfigId, beginDate, endDate)

  // for now, nodes don't override compliance mode
  def complianceMode = modes.globalComplianceMode

  def policyMode: PolicyMode = PolicyMode.computeMode(modes.globalPolicyMode, modes.nodePolicyMode)

  def agentRun: ResolvedAgentRunInterval = {
    if (nodeId == Constants.ROOT_POLICY_SERVER_ID) {
      // special case. The root policy server always run each 5 minutes
      ResolvedAgentRunInterval(Duration.standardMinutes(5))
    } else {
      val run: Int = modes.nodeAgentRun
        .flatMap(x => if (x.overrides.getOrElse(false)) Some(x.interval) else None)
        .getOrElse(modes.globalAgentRun.interval)

      ResolvedAgentRunInterval(Duration.standardMinutes(run.toLong))
    }
  }
}

final case class RuleExpectedReports(
    ruleId:     RuleId,
    directives: List[DirectiveExpectedReports]
)

/**
 * A Directive may have several components
 */
final case class DirectiveExpectedReports(
    directiveId: DirectiveId,
    policyMode:  Option[PolicyMode],
    policyTypes: PolicyTypes,
    components:  List[ComponentExpectedReport]
)

/**
 * The Cardinality is per Component
 */
sealed trait ComponentExpectedReport {
  def componentName: String
}

final case class BlockExpectedReport(
    componentName:  String,
    reportingLogic: ReportingLogic,
    subComponents:  List[ComponentExpectedReport],
    id:             Option[String]
) extends ComponentExpectedReport

final case class ValueExpectedReport(
    componentName:    String,
    componentsValues: List[ExpectedValue]
) extends ComponentExpectedReport {
  /*
  /**
   * Get a normalized list of pair of (value, unexpandedvalue).
   * We have three case to consider:
   * - both source list have the same size => easy, just zip them
   * - the unexpandedvalues is empty: it may happen due to old version of
   *   rudder not having recorded them => too bad, use the expanded value
   *   in both case
   * - different size: why on hell do we have a data model authorizing that
   *   and the TODO is not addressed ?
   *   In that case, there is no good solution. We choose to:
   *   - remove unexpanded values if it's the longer list
   *   - complete unexpanded values with matching values in the other case.
   */
  def groupedComponentValues : Seq[(String, String)] = {
    if (componentsValues.size <= unexpandedComponentsValues.size) {
      componentsValues.zip(unexpandedComponentsValues)
    } else { // strictly more values than unexpanded
      val n = unexpandedComponentsValues.size
      val unmatchedValues = componentsValues.drop(n)
      componentsValues.take(n).zip(unexpandedComponentsValues) ++ unmatchedValues.zip(unmatchedValues)
    }
  }*/
}

sealed trait ExpectedValue {
  def value: String
}

case class ExpectedValueId(value: String, id: String)                 extends ExpectedValue
case class ExpectedValueMatch(value: String, unexpandedValue: String) extends ExpectedValue

final case class NodeConfigId(value: String) extends AnyVal

final case class NodeAndConfigId(
    nodeId:  NodeId,
    version: NodeConfigId
)

final case class NodeConfigVersions(
    nodeId:   NodeId, // the most recent version is the head
    // and the list can be empty

    versions: List[NodeConfigId]
)

final case class NodeConfigIdInfo(
    configId:  NodeConfigId,
    creation:  DateTime,
    endOfLife: Option[DateTime]
)

object ExpectedReportsSerialisation {
  /*
   * This object will be used for the JSON serialisation
   * to / from database
   */
  final case class JsonNodeExpectedReports private[reports] (
      modes:               NodeModeConfig,
      ruleExpectedReports: List[RuleExpectedReports],
      overrides:           List[OverriddenPolicy]
  )

  val v00: TechniqueVersion = TechniqueVersion
    .parse("0.0")
    .getOrElse(throw new IllegalArgumentException(s"Initialisation error for default technique version in overrides"))

  // common json codec
  implicit val codecPolicyMode:          JsonCodec[PolicyMode]          = {
    val encoderPolicyMode = JsonEncoder[String].contramap[PolicyMode](_.name)
    val decoderPolicyMode = JsonDecoder[String].mapOrFail(PolicyMode.parse(_).left.map(_.fullMsg))
    JsonCodec(encoderPolicyMode, decoderPolicyMode)
  }
  implicit val codecPolicyModeOverrides: JsonCodec[PolicyModeOverrides] = {
    implicit val enc: JsonEncoder[PolicyModeOverrides] = JsonEncoder.boolean.contramap(o => o == Always)
    implicit val dec: JsonDecoder[PolicyModeOverrides] = JsonDecoder.boolean.map(b => if (b) Always else Unoverridable)
    JsonCodec[PolicyModeOverrides](enc, dec)
  }
  implicit val codecGlobalPolicyMode:    JsonCodec[GlobalPolicyMode]    = DeriveJsonCodec.gen
  implicit val codecRuleId:              JsonCodec[RuleId]              = {
    implicit val encoderRuleId: JsonEncoder[RuleId] = JsonEncoder[String].contramap[RuleId](_.serialize)
    implicit val decoderRuleId: JsonDecoder[RuleId] = JsonDecoder[String].mapOrFail(RuleId.parse(_))
    JsonCodec(encoderRuleId, decoderRuleId)
  }
  implicit val codecDirectiveId:         JsonCodec[DirectiveId]         = {
    implicit val encoderDirectiveId: JsonEncoder[DirectiveId] = JsonEncoder[String].contramap[DirectiveId](_.serialize)
    implicit val decoderDirectiveId: JsonDecoder[DirectiveId] = JsonDecoder[String].mapOrFail(DirectiveId.parse(_))
    JsonCodec(encoderDirectiveId, decoderDirectiveId)
  }
  implicit val codecReportingLogic:      JsonCodec[ReportingLogic]      = {
    implicit val encoderReportingLogic: JsonEncoder[ReportingLogic] = JsonEncoder[String].contramap[ReportingLogic](_.value)
    implicit val decoderReportingLogic: JsonDecoder[ReportingLogic] =
      JsonDecoder[String].mapOrFail(s => ReportingLogic.parse(s).left.map(_.fullMsg))
    JsonCodec(encoderReportingLogic, decoderReportingLogic)
  }

  // a common trait for all each possible version of the Json mapped JsonNodeExpectedReports
  sealed trait JsonNodeExpectedReportV

  /*
   * Parsing logic:
   * - since migration from 7.0 is not supported anymore since a long time, we only try the 7.1 format.
   */
  implicit val decoderJsonNodeExpectedReportV: JsonDecoder[JsonNodeExpectedReportV] =
    Version7_1.decodeJsonNodeExpectedReports7_1.widen

  object Version7_1 {
    /*
     * Compatibility with expected reports version 7.1 and after
     * - use short name format
     * - only two kinds of value: couple of expanded/unexpanded, with reportid
     *   - for the couple case, we have following optimisations:
     *     - if same expanded/unexpanded, only write one
     *     - if couple is None/None, write zero
     * {
     *  "ms": {                       //modes
     *    "gpm": {                   // global policy mode
     *      "m": "enforce",          // mode
     *      "o": true                // override
     *    },
     *    "npm": "audit"             // node policy mode
     *    "gcm": "full-compliance",  // global compliance mode
     *    "gar": {                   // global agent run (interval)
     *      "i": 15,                 // interval
     *      "sm": 0,                 // start minute
     *      "sh": 0,                 // start hour
     *      "st": 4                  // splay time
     *    },
     *    "nar": {                   // node agent run (interval)
     *      ...                      // same as gar
     *    },
     *  },
     *  "rs": [                       // rules
     *    {
     *      "rid": "4bb75daa-a82f-445a-8e8e-af3e99608ffe",      // rule id
     *      "ds": [                                              // directives
     *        {
     *          "did": "73e069ea-de00-4b5d-a00e-012709b7b462",  // directive id
     *          "cs": [                                         // components
     *            {                                             // block
     *              "bid": "my main block",                     // block (component) id
     *              "rl" : "weighted"                           // reporting logic
     *              "scs": [                                    // sub components
     *                 {                                        // component
     *                    "vid": "Command execution",             // values (component) id
     *                    "vs": [
     *                      [],                                 // match value with None/None pair
     *                      [ "/bin/true" ],                    // match value with same unexpanded/expanded
     *                      [ "${file}", "/tmp" ],              // match value with different unexpanded/expanded
     *                      {                                   // report id value
     *                        "id": "37c57e98-328d-4cd2-8a71-33f2e449ba51",
     *                        "v": "${file}"
     *                      }
     *                    ]
     *            },
     *             ...
     *        ]
     *      },
     *      {
     *        "rid": "hasPolicyServer-root",
     *        "ds": [
     *          {
     *            "did": "common-hasPolicyServer-root",
     *            "s": true,                                   // system - only mandatory when true
     *            "cs": [ ...
     *     "os": [                                             // overrides
     *       { "p": {                                          // policy
     *          "rid": "a3a796b9-8499-4e0b-86c5-975fc5a13505",
     *          "did": "9b0dc972-f4dc-4aaa-bae6-1189cf9074b6"
     *       },{
     *         "ob": {                                         // overridden by
     *           "rid": "2278f76f-28d3-4326-8199-99561dd8c785",
     *           "did" "093a494b-1073-49e9-bb1e-3c128c7f6a42"
     *        }
     *     ]
     */

    final case class JsonAgentRun7_1(
        i: Int, // interval

        sm: Int, // start minute

        sh: Int, // strart hour

        st: Int // splay time
    ) {
      def transform(over: Option[Boolean] = None): AgentRunInterval = AgentRunInterval(over, i, sm, sh, st)
    }
    implicit class _JsonAgentRun7_1(x: AgentRunInterval) {
      def transform: JsonAgentRun7_1 = JsonAgentRun7_1(x.interval, x.startMinute, x.startHour, x.splaytime)
    }

    final case class JsonGlobalPolicyMode7_1(m: PolicyMode, o: PolicyModeOverrides) {
      def transform: GlobalPolicyMode = GlobalPolicyMode(m, o)
    }
    implicit class _JsonGlobalPolicyMode7_1(x: GlobalPolicyMode)                    {
      def transform: JsonGlobalPolicyMode7_1 = JsonGlobalPolicyMode7_1(x.mode, x.overridable)
    }

    final case class JsonModes7_1( // modes
        gpm: JsonGlobalPolicyMode7_1, // global policy mode

        npm: Option[PolicyMode], // node policy mode

        gcm: ComplianceModeName, // global compliance mode

        gar: JsonAgentRun7_1, // global agent run (interval)

        nar: Option[JsonAgentRun7_1] // node agent run (interval)
    ) {
      def transform: NodeModeConfig = {
        val overrideAgentRun = if (nar.isDefined) Some(true) else None
        NodeModeConfig(
          GlobalComplianceMode(gcm),
          gar.transform(),
          nar.map(_.transform(overrideAgentRun)),
          gpm.transform,
          npm
        )
      }
    }

    implicit class _JsonModes7_1(x: NodeModeConfig) {
      def transform: JsonModes7_1 = JsonModes7_1(
        x.globalPolicyMode.transform,
        x.nodePolicyMode,
        x.globalComplianceMode.mode,
        x.globalAgentRun.transform,
        x.nodeAgentRun.map(_.transform)
      )
    }

    final case class JsonPolicy7_1(rid: RuleId, did: DirectiveId)  {
      def transform: PolicyId = PolicyId(rid, did, v00)
    }
    implicit class _JsonPolicy7_1(x: PolicyId)                     {
      def transform: JsonPolicy7_1 = JsonPolicy7_1(x.ruleId, x.directiveId)
    }

    final case class JsonOverrides7_1(
        p:  JsonPolicy7_1,
        ob: JsonPolicy7_1
    ) {
      def transform: OverriddenPolicy = OverriddenPolicy(p.transform, ob.transform)
    }
    implicit class _JsonOverrides7_1(x: OverriddenPolicy)          {
      def transform: JsonOverrides7_1 = JsonOverrides7_1(x.policy.transform, x.overriddenBy.transform)
    }
    final case class JsonExpectedValueId7_1(id: String, v: String) {
      def transform: ExpectedValueId = ExpectedValueId(v, id)
    }
    implicit class _JsonExpectedValueId7_1(x: ExpectedValueId)     {
      def transform: JsonExpectedValueId7_1 = JsonExpectedValueId7_1(x.id, x.value)
    }

    sealed trait JsonComponentExpectedReport7_1                                {
      def transform: ComponentExpectedReport
    }
    implicit class _JsonComponentExpectedReport7_1(x: ComponentExpectedReport) {
      def transform: JsonComponentExpectedReport7_1 = x match {
        case a: ValueExpectedReport => a.transform
        case a: BlockExpectedReport => a.transform
      }
    }
    final case class JsonValueExpectedReport7_1(
        vid: String,
        vs:  List[Either[List[String], JsonExpectedValueId7_1]]
    ) extends JsonComponentExpectedReport7_1 {
      def transform: ValueExpectedReport = ValueExpectedReport(
        vid,
        vs.map {
          case Left(Nil)         => ExpectedValueMatch("None", "None")
          case Left(a :: Nil)    => ExpectedValueMatch(a, a)
          case Left(a :: b :: _) => ExpectedValueMatch(a, b)
          case Right(v)          => v.transform
        }
      )
    }
    implicit class _JsonValueExpectedReport7_1(x: ValueExpectedReport)         {
      def transform: JsonValueExpectedReport7_1 = JsonValueExpectedReport7_1(
        x.componentName,
        x.componentsValues.map {
          case ExpectedValueMatch(a, b) =>
            Left(
              if (a == b) {
                if (a == "None") Nil
                else a :: Nil
              } else a :: b :: Nil
            )
          case ExpectedValueId(v, id)   => Right(JsonExpectedValueId7_1(id, v))
        }
      )
    }
    final case class JsonBlockExpectedReport7_1(
        bid: String,
        rl:  ReportingLogic,
        scs: List[JsonComponentExpectedReport7_1],
        id:  Option[String]
    ) extends JsonComponentExpectedReport7_1 {
      def transform: BlockExpectedReport = BlockExpectedReport(bid, rl, scs.map(_.transform), id)
    }
    implicit class _JsonBlockExpectedReport7_1(x: BlockExpectedReport)         {
      def transform: JsonBlockExpectedReport7_1 =
        JsonBlockExpectedReport7_1(x.componentName, x.reportingLogic, x.subComponents.map(_.transform), x.id)
    }

    /*
     * In 8.2 we changed the s: Option[Boolean] for isSystem to t: Option[ComplianceTag]
     * We still want to be able to decode json with s, but never write it. This is done by
     * being sure that when we go from DirectiveExpectedReports to JsonDirectiveExpectedReports8_2,
     * we always let the s value to None.
     * The chosen strategy means that when there is neither s nor t present in json (ie the common
     * case of base policies), we always need to test both. The overhead seems negligible compared
     * to burden of having two decoders + orElse
     */
    final case class JsonDirectiveExpectedReports8_2(
        did: DirectiveId,
        pm:  Option[PolicyMode],
        // the old tag for "isSystem", that we can encounter when reading old json. Never write it.
        // If both it and t is present, t wins
        s:   Option[Boolean],
        t:   Option[PolicyTypes],
        cs:  List[JsonComponentExpectedReport7_1]
    ) {
      def transform: DirectiveExpectedReports = {
        val ct = {
          t match {
            case Some(value) => value
            case None        =>
              s match {
                case Some(true) => PolicyTypes.rudderSystem
                case _          => PolicyTypes.rudderBase
              }
          }
        }
        DirectiveExpectedReports(did, pm, ct, cs.map(_.transform))
      }
    }
    implicit class _JsonDirectiveExpectedReports8_2(x: DirectiveExpectedReports) {
      def transform: JsonDirectiveExpectedReports8_2 = {
        // optimisation: if policyTypes is exactly base, we skip it
        val t = if (x.policyTypes == PolicyTypes.rudderBase) None else Some(x.policyTypes)
        JsonDirectiveExpectedReports8_2(
          x.directiveId,
          x.policyMode,
          None,
          t,
          x.components.map(_.transform)
        )
      }
    }

    final case class JsonRuleExpectedReports7_1(
        rid: RuleId,
        ds:  List[JsonDirectiveExpectedReports8_2]
    ) {
      def transform: RuleExpectedReports = RuleExpectedReports(rid, ds.map(_.transform))
    }
    implicit class _JsonRuleExpectedReports7_1(x: RuleExpectedReports)           {
      def transform = JsonRuleExpectedReports7_1(x.ruleId, x.directives.map(_.transform))
    }

    final case class JsonNodeExpectedReports7_1(
        ms: JsonModes7_1,
        rs: List[JsonRuleExpectedReports7_1],
        os: List[JsonOverrides7_1]
    ) extends JsonNodeExpectedReportV {
      def transform: JsonNodeExpectedReports = JsonNodeExpectedReports(ms.transform, rs.map(_.transform), os.map(_.transform))
    }
    implicit class _JsonNodeExpecteReports7_1(x: JsonNodeExpectedReports)        {
      def transform =
        JsonNodeExpectedReports7_1(x.modes.transform, x.ruleExpectedReports.map(_.transform), x.overrides.map(_.transform))
    }

    ////////// json codec //////////
    ///// https://github.com/zio/zio-json/issues/622 force us to split codec into encoder & decoder

    implicit lazy val decodeJsonGlobalPolicyMode7_1: JsonDecoder[JsonGlobalPolicyMode7_1] = DeriveJsonDecoder.gen
    implicit lazy val encodeJsonGlobalPolicyMode7_1: JsonEncoder[JsonGlobalPolicyMode7_1] = DeriveJsonEncoder.gen
    implicit lazy val decodeJsonAgentRun7_1:         JsonDecoder[JsonAgentRun7_1]         = DeriveJsonDecoder.gen
    implicit lazy val encodeJsonAgentRun7_1:         JsonEncoder[JsonAgentRun7_1]         = DeriveJsonEncoder.gen
    implicit lazy val decodeJsonPolicy7_1:           JsonDecoder[JsonPolicy7_1]           = DeriveJsonDecoder.gen
    implicit lazy val encodeJsonPolicy7_1:           JsonEncoder[JsonPolicy7_1]           = DeriveJsonEncoder.gen
    implicit lazy val decodeJsonJsonOverrides7_1:    JsonDecoder[JsonOverrides7_1]        = DeriveJsonDecoder.gen
    implicit lazy val encodeJsonJsonOverrides7_1:    JsonEncoder[JsonOverrides7_1]        = DeriveJsonEncoder.gen
    implicit lazy val decodeJsonModes7_1:            JsonDecoder[JsonModes7_1]            = DeriveJsonDecoder.gen
    implicit lazy val encodeJsonModes7_1:            JsonEncoder[JsonModes7_1]            = DeriveJsonEncoder.gen
    implicit lazy val decodeJsonExpectedValueId7_1:  JsonDecoder[JsonExpectedValueId7_1]  = DeriveJsonDecoder.gen
    implicit lazy val encodeJsonExpectedValueId7_1:  JsonEncoder[JsonExpectedValueId7_1]  = DeriveJsonEncoder.gen

    implicit lazy val decodeJsonEitherValue: JsonDecoder[Either[List[String], JsonExpectedValueId7_1]] = {

      // invariance is complicated
      def toRight(x: JsonExpectedValueId7_1): Either[List[String], JsonExpectedValueId7_1] = Right(x)
      def toLeft(x:  List[String]):           Either[List[String], JsonExpectedValueId7_1] = Left(x)

      JsonDecoder[List[String]].map(toLeft).orElse(decodeJsonExpectedValueId7_1.map(toRight))
    }
    implicit lazy val encodeJsonEitherValue: JsonEncoder[Either[List[String], JsonExpectedValueId7_1]] = {
      new JsonEncoder[Either[List[String], JsonExpectedValueId7_1]] {
        override def unsafeEncode(a: Either[List[String], JsonExpectedValueId7_1], indent: Option[Int], out: Write): Unit = {
          a match {
            case Left(x)  => JsonEncoder[List[String]].unsafeEncode(x, indent, out)
            case Right(x) => encodeJsonExpectedValueId7_1.unsafeEncode(x, indent, out)
          }
        }
      }
    }

    implicit lazy val decodeJsonValueExpectedReport7_1:      JsonDecoder[JsonValueExpectedReport7_1]      = DeriveJsonDecoder.gen
    implicit lazy val encodeJsonValueExpectedReport7_1:      JsonEncoder[JsonValueExpectedReport7_1]      = DeriveJsonEncoder.gen
    implicit lazy val decodeJsonBlockExpectedReport7_1:      JsonDecoder[JsonBlockExpectedReport7_1]      = DeriveJsonDecoder.gen
    implicit lazy val encodeJsonBlockExpectedReport7_1:      JsonEncoder[JsonBlockExpectedReport7_1]      = DeriveJsonEncoder.gen
    implicit lazy val decodeJsonComponentExpectedReports7_1: JsonDecoder[JsonComponentExpectedReport7_1]  = {
      decodeJsonValueExpectedReport7_1.orElse(
        decodeJsonBlockExpectedReport7_1.widen
      )
    }
    implicit lazy val encodeJsonComponentExpectedReports7_1: JsonEncoder[JsonComponentExpectedReport7_1]  = {
      // order is important: leaf first, else with recurring part first, we stackoverflow
      new JsonEncoder[JsonComponentExpectedReport7_1] {
        override def unsafeEncode(a: JsonComponentExpectedReport7_1, indent: Option[Int], out: Write): Unit = {
          a match {
            case x: JsonValueExpectedReport7_1 =>
              JsonEncoder[JsonValueExpectedReport7_1].unsafeEncode(x, indent, out)
            case x: JsonBlockExpectedReport7_1 =>
              JsonEncoder[JsonBlockExpectedReport7_1].unsafeEncode(x, indent, out)
          }
        }
      }
    }
    implicit lazy val decodeJsonDirectiveExpectedReports8_2: JsonDecoder[JsonDirectiveExpectedReports8_2] = DeriveJsonDecoder.gen
    implicit lazy val encodeJsonDirectiveExpectedReports8_2: JsonEncoder[JsonDirectiveExpectedReports8_2] = DeriveJsonEncoder.gen
    implicit lazy val decodeJsonRuleExpectedReports7_1:      JsonDecoder[JsonRuleExpectedReports7_1]      = DeriveJsonDecoder.gen
    implicit lazy val encodeJsonRuleExpectedReports7_1:      JsonEncoder[JsonRuleExpectedReports7_1]      = DeriveJsonEncoder.gen
    implicit lazy val decodeJsonNodeExpectedReports7_1:      JsonDecoder[JsonNodeExpectedReports7_1]      = DeriveJsonDecoder.gen
    implicit lazy val encodeJsonNodeExpectedReports7_1:      JsonEncoder[JsonNodeExpectedReports7_1]      = DeriveJsonEncoder.gen
  }

  def parseJsonNodeExpectedReports(s: String): Box[JsonNodeExpectedReports] = {
    s.fromJson[JsonNodeExpectedReportV] match {
      case Left(value)                                     =>
        /*
         * Here, we want to try to report a relevant error. If the problem was in
         * version 7_0, the fallback will fail with an irrelevant problem.
         * So in that case, we look if error is ".modes(missing)" and redo a pure Version7_1
         * parsing to let the user know
         */
        value match {
          case ".modes(missing)" =>
            import Version7_1.*
            s.fromJson[JsonNodeExpectedReports7_1] match {
              case Left(value)  => Failure(value)
              case Right(value) => Full(value.transform) // should not happen
            }
          case v                 => Failure(v)
        }
      case Right(v: Version7_1.JsonNodeExpectedReports7_1) =>
        Full(v.transform)
    }
  }

  /*
   * We always serialise to 7.1 format
   */
  implicit class JNodeToJson(val n: JsonNodeExpectedReports) extends AnyVal {
    import Version7_1.*
    private def toJson7_1 = n.transform
    def toJson            = toJson7_1.toJsonPretty
    def toCompactJson     = toJson7_1.toJson
  }

  implicit class NodeToJson(val n: NodeExpectedReports) extends AnyVal {
    import Version7_1.*
    private def toJson7_1 = {
      JsonNodeExpectedReports(n.modes, n.ruleExpectedReports, n.overrides).transform
    }
    def toJson            = toJson7_1.toJsonPretty
    def toCompactJson     = toJson7_1.toJson
  }
}

object NodeConfigIdSerializer {

  import net.liftweb.json.*
  import org.joda.time.format.ISODateTimeFormat

  // date are ISO format
  private val isoDateTime = ISODateTimeFormat.dateTime

  /*
   * In the database, we only keep creation time.
   * Interval are build with the previous/next.
   *
   * The format is :
   * { "configId1":"creationDate1", "configId2":"creationDate2", ... }
   */

  def serialize(ids: Vector[NodeConfigIdInfo]): String = {
    import net.liftweb.json.JsonDSL.*

    // be careful, we can have several time the same id with different creation date
    // we want an array of { begin : id }
    val m: JValue = JArray(ids.toList.sortBy(_.creation.getMillis).map {
      case NodeConfigIdInfo(NodeConfigId(id), creation, _) =>
        (creation.toString(isoDateTime) -> id): JObject
    })

    compactRender(m)
  }

  /*
   * from a JSON object: { "id1":"date1", "id2":"date2", ...}, get the list of
   * components values Ids.
   * May return an empty object
   */
  def unserialize(ids: String): Vector[NodeConfigIdInfo] = {

    if (null == ids || ids.trim == "") Vector()
    else {
      implicit val formats = DefaultFormats
      val configs          = parse(ids)
        // avoid Compiler synthesis of Manifest and OptManifest is deprecated
        .extractOrElse[List[Map[String, String]]](List())
        .flatMap {
          case map =>
            try {
              Some(map.map { case (date, id) => (NodeConfigId(id), isoDateTime.parseDateTime(date)) })
            } catch {
              case e: Exception => None
            }
        }
        .flatten
        .sortBy(_._2.getMillis): @nowarn("cat=deprecation")

      // build interval
      configs match {
        case Nil      => Vector()
        case x :: Nil => Vector(NodeConfigIdInfo(x._1, x._2, None))
        case t        =>
          t.sliding(2)
            .map {
              // we know the size of the list is 2
              case _ :: Nil | Nil =>
                throw new IllegalArgumentException("An impossible state was reached, please contact the dev about it!")
              case x :: y :: t    => NodeConfigIdInfo(x._1, x._2, Some(y._2))
            }
            .toVector :+ {
            val x = t.last
            NodeConfigIdInfo(x._1, x._2, None)
          }
      }
    }
  }
}
