package com.normation.rudder.web.snippet

import net.liftweb.http.DispatchSnippet
import com.normation.plugins.SpringExtendableSnippet
import com.normation.plugins.SnippetExtensionKey
import scala.xml.NodeSeq

class CommonLayout extends DispatchSnippet with SpringExtendableSnippet[CommonLayout] {
  def extendsAt = SnippetExtensionKey(classOf[CommonLayout].getSimpleName)

  def mainDispatch = Map(
    "display" -> { x:NodeSeq => x},
  )

}
