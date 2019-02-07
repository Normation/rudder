package com.normation.rudder.web.snippet

import net.liftweb.http.DispatchSnippet
import com.normation.plugins.SpringExtendableSnippet
import com.normation.plugins.SnippetExtensionKey
import scala.xml.NodeSeq

class Login extends DispatchSnippet with SpringExtendableSnippet[Login] {
  def extendsAt = SnippetExtensionKey(classOf[Login].getSimpleName)

  def mainDispatch = Map(
    "display" -> { x:NodeSeq => x },
  )

}
