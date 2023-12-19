package com.normation.rudder.services.marshalling

import com.normation.BoxSpecMatcher
import com.normation.GitVersion
import com.normation.cfclerk.domain.TechniqueName
import com.normation.cfclerk.domain.TechniqueVersionHelper
import com.normation.cfclerk.xmlparsers.SectionSpecParser
import com.normation.cfclerk.xmlparsers.VariableSpecParser
import com.normation.rudder.domain.nodes.NodeGroup
import com.normation.rudder.domain.nodes.NodeGroupId
import com.normation.rudder.domain.nodes.NodeGroupUid
import com.normation.rudder.domain.policies.Directive
import com.normation.rudder.domain.policies.DirectiveId
import com.normation.rudder.domain.policies.DirectiveUid
import com.normation.rudder.domain.policies.SectionVal
import com.normation.rudder.domain.policies.Tags
import com.normation.rudder.domain.properties.GroupProperty
import com.normation.rudder.domain.queries._
import com.normation.rudder.domain.queries.ObjectCriterion
import com.normation.rudder.domain.queries.ResultTransformation._
import com.normation.rudder.services.policies.TestNodeConfiguration
import com.normation.rudder.services.queries.CmdbQueryParser
import com.normation.rudder.services.queries.DefaultStringQueryParser
import com.normation.rudder.services.queries.JsonQueryLexer
import net.liftweb.common.Empty
import net.liftweb.common.Failure
import net.liftweb.common.Full
import org.junit.runner.RunWith
import org.specs2.mutable.Specification
import org.specs2.runner.JUnitRunner
import scala.xml.Elem

/*
 * Test the cache behaviour
 */
@RunWith(classOf[JUnitRunner])
class TestXmlUnserialisation extends Specification with BoxSpecMatcher {

  val queryParser: CmdbQueryParser with DefaultStringQueryParser with JsonQueryLexer = new CmdbQueryParser
    with DefaultStringQueryParser with JsonQueryLexer {
    override val criterionObjects = Map[String, ObjectCriterion]()
  }
  val directiveUnserialisation = new DirectiveUnserialisationImpl
  val nodeGroupCategoryUnserialisation = new NodeGroupCategoryUnserialisationImpl
  val nodeGroupUnserialisation         = new NodeGroupUnserialisationImpl(queryParser)
  val ruleUnserialisation              = new RuleUnserialisationImpl
  val ruleCategoryUnserialisation      = new RuleCategoryUnserialisationImpl
  val globalParameterUnserialisation   = new GlobalParameterUnserialisationImpl

  val nodeGroupSerialisation = new NodeGroupSerialisationImpl("6")

  val variableSpecParser = new VariableSpecParser
  val sectionSpecParser  = new SectionSpecParser(variableSpecParser)

  val testNodeConfiguration = new TestNodeConfiguration("")

  val changeRequestChangesUnserialisation = new ChangeRequestChangesUnserialisationImpl(
    nodeGroupUnserialisation,
    directiveUnserialisation,
    ruleUnserialisation,
    globalParameterUnserialisation,
    testNodeConfiguration.techniqueRepository,
    sectionSpecParser
  )

  val techniqueName = "TEST_Technique"
  val directiveId   = "1234567-aaaa-bbbb-cccc-ddddddddddd"

  val directiveXML: Elem = <directive fileFormat="6">
    <id>{directiveId}</id>
    <displayName>Test Directive name</displayName>
    <techniqueName>{techniqueName}</techniqueName>
    <techniqueVersion>1.0</techniqueVersion>
    <section name="sections"/>
    <shortDescription>see my description</shortDescription>
    <longDescription></longDescription>
    <priority>5</priority>
    <isEnabled>true</isEnabled>
    <isSystem>false</isSystem>
    <policyMode>default</policyMode>
    <tags/>
  </directive>

  val directive: Directive = Directive(
    DirectiveId(DirectiveUid(directiveId)),
    TechniqueVersionHelper("1.0"),
    Map(),
    "Test Directive name",
    "see my description",
    None,
    "",
    5,
    true,
    false,
    Tags(Set())
  )

  "when unserializing, we" should {
    "be able to correctly unserialize a directive " in {
      val unserialized = directiveUnserialisation.unserialise(directiveXML)

      val expected = (TechniqueName(techniqueName), directive, SectionVal(Map(), Map()))

      unserialized mustFullEq (expected)
    }

    "be able to correctly unserialize a change request" in {
      val change = <changeRequest fileFormat="6"><groups/>
        <directives>
          <directive id={directiveId}>
            <initialState>
              {directiveXML}
            </initialState>
            <firstChange>
              <change>
                <actor>test</actor>
                <date>2021-10-07T11:50:27.495+02:00</date>
                <reason>obsolete</reason>
                <diff action="delete">
                  {directiveXML}
                  </diff>
              </change>
            </firstChange>
            <nextChanges/>
          </directive>
        </directives>
        <rules/>
        <globalParameters/>
      </changeRequest>

      changeRequestChangesUnserialisation.unserialise(change) match {
        case f: Failure =>
          val msg = s"I wasn't expecting the failure: ${f.messageChain}"
          f.rootExceptionCause.foreach(ex => ex.printStackTrace())
          ko(msg)
        case Empty => ko(s"Unexpected Empty!")
        case Full(_) => ok("unserialization was a success")
      }
    }
  }

  "group property ser/unser should be identity" >> {
    def toProps(map: Map[String, String]) = map.map {
      case (k, v) =>
        GroupProperty
          .parse(k, GitVersion.DEFAULT_REV, v, None, None)
          .fold(
            err => throw new IllegalArgumentException("Error in test: " + err.fullMsg),
            res => res
          )
    }.toList

    val group = NodeGroup(
      NodeGroupId(NodeGroupUid("group")),
      "group",
      "",
      toProps(
        Map(
          "arr" -> "[1,2]",
          "obj" -> """{"a":"b", "i":"j1", "x":{"y1":"z"}, "z":[2]}""",
          "str" -> "some string",
          "xml" -> "hack:<img src=\"https://hackme.net/h.jpg\"/>"
        )
      ),
      Some(Query(NodeReturnType, And, Identity, List())),
      true,
      Set(),
      true
    )

    val xml    = nodeGroupSerialisation.serialise(group)
    val group2 = nodeGroupUnserialisation.unserialise(xml)

    group2 must beEqualTo(Full(group))
  }

}
