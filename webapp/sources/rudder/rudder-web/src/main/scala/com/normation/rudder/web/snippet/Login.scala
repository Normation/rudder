package com.normation.rudder.web.snippet

import net.liftweb.http.DispatchSnippet
import com.normation.plugins.DefaultExtendableSnippet
import scala.xml.NodeSeq

class Login extends DispatchSnippet with DefaultExtendableSnippet[Login] {

  def mainDispatch = Map(
    "display" -> { x:NodeSeq => x },
  )

}
