package com.normation.rudder.services.marshalling

import com.normation.BoxSpecMatcher
import com.normation.GitVersion
import com.normation.cfclerk.domain.TechniqueName
import com.normation.cfclerk.domain.TechniqueVersionHelper
import com.normation.cfclerk.xmlparsers.SectionSpecParser
import com.normation.cfclerk.xmlparsers.VariableSpecParser
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
import com.normation.rudder.domain.policies.SectionVal
import com.normation.rudder.domain.policies.Tags
import com.normation.rudder.domain.properties.GroupProperty
import com.normation.rudder.domain.queries.*
import com.normation.rudder.domain.queries.ResultTransformation.*
import com.normation.rudder.facts.nodes.NodeSecurityContext
import com.normation.rudder.services.policies.TestNodeConfiguration
import com.normation.rudder.services.queries.CmdbQueryParser
import java.time.Instant
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

  val queryParser                      = CmdbQueryParser.jsonStrictParser(Map.empty)
  val directiveUnserialisation         = new DirectiveUnserialisationImpl
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
    _isEnabled = true,
    isSystem = false,
    tags = Tags(Set())
  )

  "when unserializing, we" should {
    "be able to correctly unserialize a directive " in {
      val unserialized = directiveUnserialisation.unserialise(directiveXML)

      val expected = (TechniqueName(techniqueName), directive, SectionVal(Map(), Map()))

      unserialized must beRight(expected)
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
          tenants = NodeSecurityContext.All
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
          tenants = NodeSecurityContext.All
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
          tenants = NodeSecurityContext.All
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
      _isEnabled = true
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

    res must beRight(NodeGroupCategory(NodeGroupCategoryId(id), dn, d, Nil, Nil, true))
  }

}
