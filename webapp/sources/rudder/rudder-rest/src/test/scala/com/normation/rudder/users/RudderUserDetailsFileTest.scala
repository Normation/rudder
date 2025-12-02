package com.normation.rudder.users

import com.normation.XmlSpecMatcher
import org.junit.runner.RunWith
import org.specs2.mutable.Specification
import org.specs2.runner.JUnitRunner

@RunWith(classOf[JUnitRunner])
class RudderUserDetailsFileTest extends Specification with XmlSpecMatcher {

  // content of the XML file, with unsafe-hashes removed
  val userXml = <authentication  unsafe-hashes="true" hash="sha-1" case-sensitivity="true">
    <user name="user1" password="1234" permissions="user" tenants="zoneA" />
    <user name="user2" password="a94a8fe5ccb19ba61c4c0873d391e987982fbbd3" permissions="read_only" />
  </authentication>

  val expectedXml = <authentication hash="plop" case-sensitivity="true">
    <user name="user1" password="1234" permissions="user" tenants="zoneA" />
    <user name="user2" password="a94a8fe5ccb19ba61c4c0873d391e987982fbbd3" permissions="read_only" />
  </authentication>

  val exec = UserManagementIO.replaceXml(userXml, FileUserDetailListProvider.XmlMigrationRule("plop"), "testFile")

  "migration rule should work" >> {
    exec must beRight(equalsIgnoringSpace(expectedXml))
  }
}
