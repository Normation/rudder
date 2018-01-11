package com.normation.rudder.web.snippet

import net.liftweb.http.DispatchSnippet
import com.normation.plugins.SpringExtendableSnippet
import com.normation.plugins.SnippetExtensionKey
import scala.xml.NodeSeq
import com.normation.plugins.SnippetExtensionPoint
import net.liftweb.common.Loggable
import net.liftweb.http.js.JsCmds._
import net.liftweb.http.js.JE.JsRaw

class Index extends DispatchSnippet with SpringExtendableSnippet[Index] {
  def extendsAt = SnippetExtensionKey(classOf[Index].getSimpleName)

  def mainDispatch = Map(
    "showForm" -> { x:NodeSeq => x},
  )

}

class IndexExtension(
) extends SnippetExtensionPoint[Index] with Loggable {

  val extendsAt = SnippetExtensionKey(classOf[Index].getSimpleName)

  def compose(snippet:Index) : Map[String, NodeSeq => NodeSeq] = Map(
      "showForm" ->  ( x => {logger.info(x)
      logger.info(snippet)
      val res = x ++ <div>kikoo2</div> ++ <head>{ Script(net.liftweb.http.js.JsCmds.OnLoad(JsRaw("console.log(\"kikoo\");")))}

      <style>.skin-yellow .main-header .navbar  { "{ background-color: #4286f4; }"} </style>
</head>
      logger.warn(res)
      res
      }
      )
    )
}
