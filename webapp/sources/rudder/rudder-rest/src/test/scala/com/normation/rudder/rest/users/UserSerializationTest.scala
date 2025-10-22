package com.normation.rudder.rest.users

import com.normation.eventlog.EventActor
import com.normation.rudder.users.*
import org.junit.runner.RunWith
import org.specs2.mutable.Specification
import org.specs2.runner.JUnitRunner
import zio.json.ast.Json

@RunWith(classOf[JUnitRunner])
class UserSerializationTest extends Specification {

  "JsonUser" should {
    "use providers info" in {
      val providersInfo: Map[String, JsonProviderInfo] = Map(
        "provider1" -> JsonProviderInfo(
          "provider1",
          JsonRights(Set("read")),
          JsonRoles(Set("read_role")),
          JsonRights.empty
        ),
        "provider2" -> JsonProviderInfo(
          "provider2",
          JsonRights(Set("read", "write")),
          JsonRoles(Set("read_role", "write_role")),
          JsonRights(Set("custom_read"))
        )
      )

      val expected = JsonUser(
        "user",
        None,
        None,
        Json.Obj(),
        UserStatus.Active,
        JsonRights(Set("read", "write")),
        JsonRoles(Set("read_role", "write_role")),
        JsonRoles(Set("read_role", "write_role")),
        JsonRights(Set("custom_read")),
        List("provider1", "provider2"),
        providersInfo,
        "",
        None,
        None
      )

      JsonUser("user", None, None, Json.Obj(), UserStatus.Active, providersInfo, "", None, None) must beEqualTo(expected)
    }
  }

  "EventActor" should {
    "serialize as a case class" in {
      UserSerialization.codecEventActor.encoder.encodeJson(EventActor("foo")).toString must beEqualTo("""{"name":"foo"}""")
    }
    "have isomorphic serialization" in {

      val input = EventActor("foo")
      def encode(input: EventActor) = UserSerialization.codecEventActor.encoder.encodeJson(input).toString
      def decode(json:  String)     = UserSerialization.codecEventActor.decoder.decodeJson(json)

      decode(encode(input)) must beRight(input)
    }
  }
}
