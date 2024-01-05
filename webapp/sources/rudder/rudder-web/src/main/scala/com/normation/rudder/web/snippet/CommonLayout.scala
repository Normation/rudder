package com.normation.rudder.web.snippet

import com.normation.plugins.DefaultExtendableSnippet
import com.normation.rudder.domain.logger.ApplicationLogger
import com.normation.rudder.users.CurrentUser
import net.liftweb.http.DispatchSnippet
import scala.xml.NodeSeq

class CommonLayout extends DispatchSnippet with DefaultExtendableSnippet[CommonLayout] {

  def mainDispatch = Map(
    "display" -> init
  )

  /*
   * This seems to be needed in top of common layout to correctly init
   * the session var just after login.
   */
  def init(xml: NodeSeq): NodeSeq = {
    CurrentUser.get match {
      case None    => ApplicationLogger.warn("Authz.init called but user not authenticated")
      case Some(_) => // expected
    }
    xml
  }
}
