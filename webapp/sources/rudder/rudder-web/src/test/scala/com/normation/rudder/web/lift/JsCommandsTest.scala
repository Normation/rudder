package com.normation.rudder.web.lift

import com.normation.errors.SecurityError
import com.normation.rudder.services.servers.InstanceId
import com.normation.rudder.web.StaticResourceRewrite
import com.normation.rudder.web.lift.JsCommands.*
import net.liftweb.http.js.JE.*
import net.liftweb.http.js.JsCmds.*
import org.junit.runner.RunWith
import org.specs2.mutable.Specification
import org.specs2.runner.JUnitRunner

@RunWith(classOf[JUnitRunner])
class JsCommandsTest extends Specification {

  given StaticResourceRewrite = new StaticResourceRewrite("123", InstanceId("42"))

  "JsCommands" should {
    "have JS module imports" in {
      JsModuleImport(JsModuleFeatures.all, JsModulePath.force("some-lib")).toJs must beEqualTo(
        """import * from "some-lib";"""
      )
      JsModuleImport(JsModuleFeatures("functionA", "functionB"), JsModulePath.force("some-lib")).toJs must beEqualTo(
        """import { functionA, functionB } from "some-lib";"""
      )
    }

    "output html without imports" in {
      ScriptModule(OnLoad(JsRaw("console.debug(null == 0)"))).toHtml.toString must beEqualTo(
        """<script type="module">
          |// <![CDATA[
          |jQuery(document).ready(function() {console.debug(null == 0);});
          |// ]]>
          |</script>""".stripMargin
      )
    }
    "output html with added import to module" in {
      // "f86e4af40842494647b24a99" is the result of the has for version "123" and instance ID "42"
      val expected = {
        """<script type="module">
          |// <![CDATA[
          |import { functionA } from "/cache-f86e4af40842494647b24a99/javascript/some-lib";jQuery(document).ready(function() {functionA(null);});
          |// ]]>
          |</script>""".stripMargin
      }

      ScriptModule(OnLoad(JsRaw("functionA(null)")))
        .withStaticImport(JsModuleFeatures("functionA"), "javascript/some-lib")
        .map(_.toHtml.toString)
        .aka("relative import") must beRight(expected)

      ScriptModule(OnLoad(JsRaw("functionA(null)")))
        .withStaticImport(JsModuleFeatures("functionA"), "/javascript/some-lib")
        .map(_.toHtml.toString)
        .aka("absolute import") must beRight(expected)

    }

    "fail to output html for unknown javascript assets directory" in {
      ScriptModule(OnLoad(JsRaw("functionA(null)")))
        .withStaticImport(JsModuleFeatures.all, "java/some-lib")
        .map(_.toHtml.toString) must beLeft(beAnInstanceOf[SecurityError])
    }

    "fail to output html for relative import" in {
      ScriptModule(OnLoad(JsRaw("functionA(null)")))
        .withStaticImport(JsModuleFeatures("functionA"), "../some-lib")
        .map(_.toHtml.toString) must beLeft(beAnInstanceOf[SecurityError])
    }

    "fail to output html for external URL" in {
      ScriptModule(OnLoad(JsRaw("functionA(null)")))
        .withStaticImport(JsModuleFeatures("functionA"), "https://some.malicious.domain/evil.js")
        .map(_.toHtml.toString) must beLeft(beAnInstanceOf[SecurityError])
    }
  }

}
