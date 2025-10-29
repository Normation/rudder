package com.normation.rudder.web.lift

import com.normation.errors.PureResult
import com.normation.errors.SecurityError
import com.normation.rudder.web.StaticResourceRewrite
import java.net.URI
import net.liftweb.http.js.JsCmd
import scala.xml.Node
import scala.xml.Unparsed
import zio.NonEmptyChunk

object JsCommands {
  opaque type JsModuleFeature = String

  private object JsImportAll // for "import *"
  opaque type JsModuleFeatures = JsImportAll.type | NonEmptyChunk[JsModuleFeature]
  object JsModuleFeatures {
    def apply(f: String, other: String*): JsModuleFeatures = NonEmptyChunk(f, other*)
    def all: JsModuleFeatures = JsImportAll
  }
  extension (feat: JsModuleFeatures) {
    private def toImport: String = {
      feat match {
        case JsImportAll => "*"
        case nec: NonEmptyChunk[JsModuleFeature] => s"""{ ${nec.mkString(", ")} }"""
      }
    }
  }

  // use URI, which would can be relativized, and checked against our static assets directory
  opaque type JsModulePath = URI
  extension (path: JsModulePath) {
    private def toPath: String =
      path.getRawPath

  }
  object JsModulePath {
    def apply(component: String): PureResult[JsModulePath] = {
      PureResult.attempt(URI.create(component))
    }

    // for testing purposes
    private[lift] def force(component: String): JsModulePath = URI.create(component)
  }

  /**
   * An import in JS of the form :
   * {{{
   *  import (features) from "(path)";
   * }}}
   *
   * Since we will want to import existing JS modules within some lift AJAX
   * (see addition of ESM modules : https://issues.rudder.io/issues/27124)
   * , we need to validate the import for known formats :
   * - import "features", that need to be exported from JS files (they are not known from Scala code, they are JS functions/values)
   * - path that needs to be absolute (that should be obtained from [[com.normation.rudder.web.StaticResourceRewrite]])
   * 
   * (We don't use import maps, which could at some point help mapping components to known paths)
   *
   */
  case class JsModuleImport(features: JsModuleFeatures, path: JsModulePath) {
    private[lift] def toJs: String =
      s"""import ${features.toImport} from "${path.toPath}";"""

  }

  private case class StaticImportRelativeError(path: JsModulePath) extends SecurityError {
    override def msg: String = s"Cannot import from unresolved javascript module at '${path.toPath}'"

  }

  /**
   * A script that needs some imports which are defined in ESM modules.
   * It needs to validate the imports that are added against path traversal.
   * 
   * To render it to actual HTML, use the [[toHtml]] method.
   * (the `toHtml` method is adapted from Lift's own JsCommands html rendering of a [[net.liftweb.http.js.Script]])
   */
  case class ScriptModule private (private val script: JsCmd, private val staticImports: List[JsModuleImport]) {

    /**
     * The method to add an import, the path should be within the javascript modules directory.
     * The input path should not be absolute because the javascript (the leading "/" is stripped if it is absolute)
     *
     * Examples : 
     * {{{
     *   withStaticImport(JsModuleFeatures("myFunction"), "/javascript/rudder/charting.js")
     * }}}
     * , or
     * {{{
     *   withStaticImport(JsModuleFeatures("myFunction"), "javascript/libs/jquery.min.js")
     * }}}
     * 
     */
    def withStaticImport(features: JsModuleFeatures, path: String)(using
        s: StaticResourceRewrite
    ): PureResult[ScriptModule] = {
      for {
        d          <- ScriptModule.rudderJavascriptModulesDir
        unresolved <- JsModulePath(path.stripPrefix("/"))
        resolved    = d.resolve(unresolved)
        res        <- withStaticImport(features, resolved)
      } yield {
        res
      }
    }

    /**
     * Resolves the path to the javascript directory, and prevents relative paths,
     * outside of rudder javascript modules directory
     */
    private def withStaticImport(features: JsModuleFeatures, path: JsModulePath)(using
        s: StaticResourceRewrite
    ): PureResult[ScriptModule] = {
      for {
        d        <- ScriptModule.rudderJavascriptModulesDir
        checkPath = path.normalize().getPath.startsWith(d.normalize().getPath)
        res      <- Either.cond(
                      checkPath,
                      copy(staticImports = JsModuleImport(features, path) :: staticImports),
                      StaticImportRelativeError(path)
                    )
      } yield {
        res
      }
    }

    def toHtml: Node = <script type="module">{
      Unparsed(
        """
// <![CDATA[
""" + (if (staticImports.isEmpty) "" else staticImports.map(_.toJs).mkString(";\n"))
        + fixEndScriptTag(script.toJsCmd) + """
// ]]>
"""
      )
    }</script>

    private def fixEndScriptTag(in: String): String =
      """\<\/script\>""".r.replaceAllIn(in, """<\\/script>""")
  }

  object ScriptModule {

    /**
     * Our directory within the static resources, to check against potential escape (traversal)
     */
    private def rudderJavascriptModulesDir(using s: StaticResourceRewrite): PureResult[JsModulePath] =
      PureResult.attempt(URI.create(s.resourceUrl("/javascript")))

    def apply(script: JsCmd): ScriptModule = new ScriptModule(script, Nil)
  }
}
