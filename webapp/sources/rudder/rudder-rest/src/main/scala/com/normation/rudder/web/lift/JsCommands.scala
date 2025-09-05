package com.normation.rudder.web.lift

import net.liftweb.http.js.JsCmd
import scala.xml.Node
import scala.xml.Unparsed

object JsCommands {

  // copied and adapted from Lift JsCommands code
  object ScriptModule {
    def apply(script: JsCmd, imports: String = ""): Node = <script type="module">{
      Unparsed("""
// <![CDATA[
""" + (if (imports.isEmpty) imports else (imports + "\n")) + fixEndScriptTag(script.toJsCmd) + """
// ]]>
""")
    }</script>

    private def fixEndScriptTag(in: String): String =
      """\<\/script\>""".r.replaceAllIn(in, """<\\/script>""")
  }
}
