package com.normation.rudder.services.marshalling

import com.normation.BoxSpecMatcher
import com.normation.GitVersion
import com.normation.cfclerk.domain.Constraint
import com.normation.cfclerk.domain.InputVariableSpec
import com.normation.cfclerk.domain.PredefinedValuesVariableSpec
import com.normation.cfclerk.domain.ReportingLogic.WeightedReport
import com.normation.cfclerk.domain.SectionSpec
import com.normation.cfclerk.domain.TechniqueName
import com.normation.cfclerk.domain.TechniqueVersionHelper
import com.normation.cfclerk.domain.TextareaVType
import com.normation.cfclerk.xmlparsers.CfclerkXmlConstants
import com.normation.cfclerk.xmlparsers.SectionSpecParser
import com.normation.cfclerk.xmlparsers.VariableSpecParser
import com.normation.cfclerk.xmlwriters.SectionSpecWriterImpl
import com.normation.eventlog.EventActor
import com.normation.rudder.api.AccountToken
import com.normation.rudder.api.AclPath
import com.normation.rudder.api.ApiAccount
import com.normation.rudder.api.ApiAccountId
import com.normation.rudder.api.ApiAccountKind
import com.normation.rudder.api.ApiAccountName
import com.normation.rudder.api.ApiAclElement
import com.normation.rudder.api.ApiAuthorization
import com.normation.rudder.api.HttpAction
import com.normation.rudder.domain.nodes.NodeGroup
import com.normation.rudder.domain.nodes.NodeGroupCategory
import com.normation.rudder.domain.nodes.NodeGroupCategoryId
import com.normation.rudder.domain.nodes.NodeGroupId
import com.normation.rudder.domain.nodes.NodeGroupUid
import com.normation.rudder.domain.policies.Directive
import com.normation.rudder.domain.policies.DirectiveId
import com.normation.rudder.domain.policies.DirectiveUid
import com.normation.rudder.domain.policies.ModifyToDirectiveDiff
import com.normation.rudder.domain.policies.RuleId
import com.normation.rudder.domain.policies.SectionVal
import com.normation.rudder.domain.policies.Tags
import com.normation.rudder.domain.properties.GroupProperty
import com.normation.rudder.domain.queries.*
import com.normation.rudder.domain.queries.ResultTransformation.*
import com.normation.rudder.domain.workflows.ChangeRequestId
import com.normation.rudder.domain.workflows.ChangeRequestInfo
import com.normation.rudder.domain.workflows.ConfigurationChangeRequest
import com.normation.rudder.domain.workflows.DirectiveChange
import com.normation.rudder.domain.workflows.DirectiveChangeItem
import com.normation.rudder.domain.workflows.DirectiveChanges
import com.normation.rudder.domain.workflows.GlobalParameterChanges
import com.normation.rudder.domain.workflows.NodeGroupChanges
import com.normation.rudder.domain.workflows.RuleChanges
import com.normation.rudder.services.policies.TestNodeConfiguration
import com.normation.rudder.services.queries.CmdbQueryParser
import com.normation.rudder.tenants.SecurityTag
import com.normation.rudder.tenants.TenantAccessGrant
import com.normation.rudder.tenants.TenantId
import com.normation.utils.DateFormaterService
import java.time.Instant
import net.liftweb.common.Full
import org.junit.runner.RunWith
import org.specs2.mutable.Specification
import org.specs2.runner.JUnitRunner
import scala.collection.immutable.HashMap
import scala.xml.Elem
import zio.Chunk

/*
 * Test the cache behaviour
 */
@RunWith(classOf[JUnitRunner])
class TestXmlUnserialisation extends Specification with BoxSpecMatcher {

  val queryParser                      = CmdbQueryParser.jsonStrictParser(Map.empty)
  val directiveUnserialisation         = new DirectiveUnserialisationImpl
  val nodeGroupCategoryUnserialisation = new NodeGroupCategoryUnserialisationImpl
  val nodeGroupUnserialisation         = new NodeGroupUnserialisationImpl(queryParser)
  val ruleUnserialisation              = new RuleUnserialisationImpl
  val ruleCategoryUnserialisation      = new RuleCategoryUnserialisationImpl
  val globalParameterUnserialisation   = new GlobalParameterUnserialisationImpl

  val nodeGroupSerialisation = new NodeGroupSerialisationImpl("6")

  val variableSpecParser = new VariableSpecParser
  val sectionSpecParser  = new SectionSpecParser(variableSpecParser, validateReporting = false)

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

  // Simple directive that maps to the directive value
  val directiveXML: Elem = <directive fileFormat="6">
    <id>{directiveId}</id>
    <displayName>Test Directive name</displayName>
    <techniqueName>{techniqueName}</techniqueName>
    <techniqueVersion>1.0</techniqueVersion>
    <section name="sections">
      <var name="B93EEC04-0AC2-4A6B-850A-A1A45FF665BF">a</var>
      <var name="49539815-6EE3-462B-B553-30D55F341D3C">b</var>
      <var name="56EFCD1B-1B1E-4CAC-A7E0-911F640CC8D0">c</var>
      <var name="8575EF95-063A-4958-ABF5-126F6B2EECA4">d</var>
    </section>
    <shortDescription>see my description</shortDescription>
    <longDescription></longDescription>
    <priority>5</priority>
    <isEnabled>true</isEnabled>
    <isSystem>false</isSystem>
    <policyMode>default</policyMode>
    <tags/>
    <security><tenants><tenant id="zoneA"/></tenants></security>
  </directive>

  val directive: Directive = Directive(
    DirectiveId(DirectiveUid(directiveId)),
    TechniqueVersionHelper("1.0"),
    Map(
      "B93EEC04-0AC2-4A6B-850A-A1A45FF665BF" -> List("a"),
      "49539815-6EE3-462B-B553-30D55F341D3C" -> List("b"),
      "56EFCD1B-1B1E-4CAC-A7E0-911F640CC8D0" -> List("c"),
      "8575EF95-063A-4958-ABF5-126F6B2EECA4" -> List("d")
    ),
    "Test Directive name",
    "see my description",
    None,
    "",
    5,
    _isEnabled = true,
    isSystem = false,
    tags = Tags(Set()),
    security = Some(SecurityTag.ByTenants(Chunk(TenantId("zoneA"))))
  )

  val directiveSectionSpec: SectionSpec = {
    SectionSpec(
      name = CfclerkXmlConstants.SECTION_ROOT_NAME,
      children = List(
        SectionSpec(
          name = "Technique parameters",
          children = List(
            InputVariableSpec(
              "56EFCD1B-1B1E-4CAC-A7E0-911F640CC8D0",
              "Rudder version",
              None,
              "Rudder agent version to install",
              false,
              true,
              Constraint(TextareaVType()),
              None
            ),
            InputVariableSpec(
              "B93EEC04-0AC2-4A6B-850A-A1A45FF665BF",
              "Rudder repository",
              None,
              "Rudder repository to use, i.e. repository.rudder.io (public) or download.rudder.io (private)",
              false,
              true,
              Constraint(TextareaVType()),
              None
            ),
            InputVariableSpec(
              "8575EF95-063A-4958-ABF5-126F6B2EECA4",
              "Rudder licence",
              None,
              "Your company's Rudder licence name, or leave empty for public repository",
              false,
              true,
              Constraint(TextareaVType()),
              None
            ),
            InputVariableSpec(
              "49539815-6EE3-462B-B553-30D55F341D3C",
              "Licence password",
              None,
              "Your company's Rudder licence password or leave empty for public repository",
              false,
              true,
              Constraint(TextareaVType()),
              None
            )
          )
        ),
        SectionSpec(
          // parent section (block), without componentKey
          name = "10 - Node is Debian / Ubuntu family",
          isMultivalued = true,
          isComponent = true,
          reportingLogic = Some(WeightedReport),
          children = List(
            SectionSpec(
              name = "10-030 Rudder packages GPG signing key installed",
              isMultivalued = true,
              isComponent = true,
              componentKey = Some("expectedReportKey 10-030 Rudder packages GPG signing key installed"),
              children = List(
                PredefinedValuesVariableSpec(
                  "expectedReportKey 10-030 Rudder packages GPG signing key installed",
                  "Expected Report key names for component 10-030 Rudder packages GPG signing key installed",
                  None,
                  ("/etc/apt/trusted.gpg.d/rudder_release_key.gpg", List()),
                  constraint = Constraint(),
                  id = None
                )
              )
            )
          )
        )
      )
    )
  }

  val changeRequestSerialisation: ChangeRequestChangesSerialisationImpl = {
    val directiveSerialisation = new DirectiveSerialisationImpl("6")
    val sectionSpecWriter      = new SectionSpecWriterImpl
    new ChangeRequestChangesSerialisationImpl(
      "6",
      null,
      directiveSerialisation,
      null,
      null,
      null,
      sectionSpecWriter
    )
  }

  val changeRequest = {
    ConfigurationChangeRequest(
      ChangeRequestId(1),
      None,
      ChangeRequestInfo("directive change", "My directive change"),
      Map(
        DirectiveId(DirectiveUid(directiveId)) -> DirectiveChanges(
          DirectiveChange(
            Some((TechniqueName(techniqueName), directive, None)),
            DirectiveChangeItem(
              EventActor("test"),
              DateFormaterService.parseDate("2023-02-02T00:00:00Z").toOption.getOrElse(throw new Exception("invalid date")),
              Some(s"directive ${directiveId} change reason"),
              ModifyToDirectiveDiff(
                TechniqueName(techniqueName),
                directive,
                Some(directiveSectionSpec)
              )
            ),
            List.empty
          ),
          List.empty
        )
      ),
      Map.empty,
      Map.empty,
      Map.empty
    )
  }

  "when unserializing, we" should {
    "be able to correctly unserialize a given directive " in {
      val actual = directiveUnserialisation.unserialise(directiveXML)

      val expectedSectionVal = SectionVal(HashMap(), directive.parameters.view.mapValues(_.head).toMap)
      val expected           = (TechniqueName(techniqueName), directive, expectedSectionVal)

      actual must beRight(expected)
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

      changeRequestChangesUnserialisation.unserialise(change) must beRight
    }

    "be able to correctly unserialize idempotently" in {
      import com.softwaremill.quicklens.*
      // https://issues.rudder.io/issues/27974: "reporting" logic is not serialized, componentKey is not present,
      // but unserialization must still succeed.
      // 1. The unserialized change request has no reporting logic upon serialization, and therefore upon unserialization too.
      // 2. The directive has no parameters when serialized, this looks like a bug...
      val serialized   = changeRequestSerialisation.serialise(changeRequest)
      val actual       = changeRequestChangesUnserialisation.unserialise(serialized)
      val expectedPair = changeRequest.directives.head
        // 1.
        .modify(_._2.changes.firstChange.diff.when[ModifyToDirectiveDiff].rootSection.each.children)
        .using(_.modify(_.each.when[SectionSpec].reportingLogic).setTo(None))
        // 2.
        .modify(_._2.changes.initialState.each._2.parameters)
        .setTo(Map.empty)

      actual must beRight(
        beLike[
          (
              Map[DirectiveId, DirectiveChanges],
              Map[NodeGroupId, NodeGroupChanges],
              Map[RuleId, RuleChanges],
              Map[String, GlobalParameterChanges]
          )
        ] {
          case (directiveChange, _, _, _) =>
            directiveChange must havePair(expectedPair)
        }
      )
    }
  }

  "be able to correctly unserialize an ApiAccount with RW rights" in {
    val serialized = <apiAccount fileFormat="6" changeType="add">
      <id>c331c718-db0e-429e-b800-20b055ca6a67</id>
      <name>Test account with some acl scala 3</name>
      <token>
        v2:449967a9c3a1cf25b6333fa531626e1a9375accf889dea3063b8707debde9f033535082a31de85c34c71d8be9b228f38e6cde467d6f3ba423d99437899bfb1d6
      </token>
      <description/>
      <isEnabled>true</isEnabled>
      <creationDate>2025-05-06T13:59:59.613+02:00</creationDate>
      <tokenGenerationDate>2025-05-06T13:59:59.613+02:00</tokenGenerationDate>
      <tenants>*</tenants>
      <kind>public</kind>
      <authorization>rw</authorization>
      <expirationDate>2025-06-06T15:59:35.297+02:00</expirationDate>
      <lastAuthenticationDate>2025-05-07T00:11:22.345+02:00</lastAuthenticationDate>
    </apiAccount>

    val actual = new ApiAccountUnserialisationImpl().unserialise(serialized)
    val token  = AccountToken(None, Instant.parse("2025-05-06T13:59:59.613+02:00"))

    actual.map(_.copy(token = token)) must beEqualTo(
      Full(
        ApiAccount(
          id = ApiAccountId("c331c718-db0e-429e-b800-20b055ca6a67"),
          kind = ApiAccountKind.PublicApi(
            authorizations = ApiAuthorization.RW,
            expirationDate = Some(Instant.parse("2025-06-06T15:59:35.297+02:00"))
          ),
          name = ApiAccountName("Test account with some acl scala 3"),
          token = token,
          description = "",
          isEnabled = true,
          creationDate = Instant.parse("2025-05-06T13:59:59.613+02:00"),
          lastAuthenticationDate = Some(Instant.parse("2025-05-07T00:11:22.345+02:00")),
          tenants = TenantAccessGrant.All
        )
      )
    )
  }

  "be able to correctly unserialize an ApiAccount " in {
    val serialized = <apiAccount fileFormat="6" changeType="add">
      <id>c331c718-db0e-429e-b800-20b055ca6a67</id>
      <name>Test account with some acl scala 3</name>
      <token>
        v2:449967a9c3a1cf25b6333fa531626e1a9375accf889dea3063b8707debde9f033535082a31de85c34c71d8be9b228f38e6cde467d6f3ba423d99437899bfb1d6
      </token>
      <description/>
      <isEnabled>true</isEnabled>
      <creationDate>2025-05-06T13:59:59.613+02:00</creationDate>
      <tokenGenerationDate>2025-05-06T13:59:59.613+02:00</tokenGenerationDate>
      <tenants>*</tenants>
      <kind>public</kind>
      <authorization>
        <acl>
          <authz actions="get" path="archives/export"/>
          <authz actions="post" path="archives/import"/>
          <authz actions="get,delete,post" path="apiaccounts/*"/>
        </acl>
      </authorization>
      <expirationDate>2025-06-06T15:59:35.297+02:00</expirationDate>
      <lastAuthenticationDate>2025-05-07T00:11:22.345+02:00</lastAuthenticationDate>
    </apiAccount>

    val actual = new ApiAccountUnserialisationImpl().unserialise(serialized)
    val token  = AccountToken(None, Instant.parse("2025-05-06T13:59:59.613+02:00"))

    actual.map(_.copy(token = token)) must beEqualTo(
      Full(
        ApiAccount(
          id = ApiAccountId("c331c718-db0e-429e-b800-20b055ca6a67"),
          kind = ApiAccountKind.PublicApi(
            authorizations = ApiAuthorization.ACL(
              List(
                ApiAclElement(
                  actions = Set(HttpAction.GET, HttpAction.DELETE, HttpAction.POST),
                  path = AclPath.parse("apiaccounts/*").toOption.get
                ),
                ApiAclElement(actions = Set(HttpAction.GET), path = AclPath.parse("archives/export").toOption.get),
                ApiAclElement(actions = Set(HttpAction.POST), path = AclPath.parse("archives/import").toOption.get)
              )
            ),
            expirationDate = Some(Instant.parse("2025-06-06T15:59:35.297+02:00"))
          ),
          name = ApiAccountName("Test account with some acl scala 3"),
          token = token,
          description = "",
          isEnabled = true,
          creationDate = Instant.parse("2025-05-06T13:59:59.613+02:00"),
          lastAuthenticationDate = Some(Instant.parse("2025-05-07T00:11:22.345+02:00")),
          tenants = TenantAccessGrant.All
        )
      )
    )
  }

  "be able to correctly unserialize a pre-Rudder-8.3 ApiAccount " in {
    val serialized = <apiAccount fileFormat="6" changeType="add">
      <id>c331c718-db0e-429e-b800-20b055ca6a67</id>
      <name>Test account with some acl scala 3</name>
      <token>
        v2:449967a9c3a1cf25b6333fa531626e1a9375accf889dea3063b8707debde9f033535082a31de85c34c71d8be9b228f38e6cde467d6f3ba423d99437899bfb1d6
      </token>
      <description/>
      <isEnabled>true</isEnabled>
      <creationDate>2025-05-06T13:59:59.613+02:00</creationDate>
      <tokenGenerationDate>2025-05-06T13:59:59.613+02:00</tokenGenerationDate>
      <tenants>*</tenants>
      <kind>public</kind>
      <authorization>
        <acl>
          <authz action="get" path="archives/export"/>
          <authz action="post" path="archives/export"/>
          <authz action="delete" path="apiaccounts/*"/>
        </acl>
      </authorization>
      <expirationDate>2025-06-06T15:59:35.297+02:00</expirationDate>
      <lastAuthenticationDate>2025-05-07T00:11:22.345+02:00</lastAuthenticationDate>
    </apiAccount>

    val actual = new ApiAccountUnserialisationImpl().unserialise(serialized)
    val token  = AccountToken(None, Instant.parse("2025-05-06T13:59:59.613+02:00"))

    actual.map(_.copy(token = token)) must beEqualTo(
      Full(
        ApiAccount(
          id = ApiAccountId("c331c718-db0e-429e-b800-20b055ca6a67"),
          kind = ApiAccountKind.PublicApi(
            authorizations = ApiAuthorization.ACL(
              List(
                ApiAclElement(
                  actions = Set(HttpAction.DELETE),
                  path = AclPath.parse("apiaccounts/*").toOption.get
                ),
                ApiAclElement(
                  actions = Set(HttpAction.GET, HttpAction.POST),
                  path = AclPath.parse("archives/export").toOption.get
                )
              )
            ),
            expirationDate = Some(Instant.parse("2025-06-06T15:59:35.297+02:00"))
          ),
          name = ApiAccountName("Test account with some acl scala 3"),
          token = token,
          description = "",
          isEnabled = true,
          creationDate = Instant.parse("2025-05-06T13:59:59.613+02:00"),
          lastAuthenticationDate = Some(Instant.parse("2025-05-07T00:11:22.345+02:00")),
          tenants = TenantAccessGrant.All
        )
      )
    )
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
      Some(Query(QueryReturnType.NodeReturnType, CriterionComposition.And, Identity, List())),
      isDynamic = true,
      serverList = Set(),
      _isEnabled = true,
      security = None
    )

    val xml    = nodeGroupSerialisation.serialise(group)
    val group2 = nodeGroupUnserialisation.unserialise(xml)

    group2 must beRight(group)
  }

  "We should be able to unserialize a NodeGroupCategory" >> {
    val id = "GroupRoot"
    val dn = "Root of the group and group categories"
    val d  = "This is the root category for the groups (both dynamic and static) and group categories"

    val res = nodeGroupCategoryUnserialisation.unserialise(
      <nodeGroupCategory fileFormat="6">
        <id>{id}</id>
        <displayName>{dn}</displayName>
        <description>{d}</description>
        <isSystem>true</isSystem>
      </nodeGroupCategory>
    )

    res must beRight(NodeGroupCategory(NodeGroupCategoryId(id), dn, d, Nil, Nil, true, security = None))
  }

}
