package com.normation.rudder.web.snippet

import com.normation.plugins.DefaultExtendableSnippet
import com.normation.rudder.domain.logger.ApplicationLogger
import com.normation.rudder.users.CurrentUser
import net.liftweb.http.DispatchSnippet
import net.liftweb.http.js.JE.*
import net.liftweb.http.js.JsCmds.*
import net.liftweb.util.*
import net.liftweb.util.CanBind.*
import scala.xml.Elem
import scala.xml.NodeSeq

class CommonLayout extends DispatchSnippet with DefaultExtendableSnippet[CommonLayout] {

  def mainDispatch: Map[String, NodeSeq => NodeSeq] = Map(
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

    display(xml) ++ WithNonce.scriptWithNonce(
      Script(
        OnLoad(
          JsRaw(
            """$('body').toggleClass('sidebar-collapse');"""
          )
        )
      )
    )
  }

  def display: CssSel = {
    "#toggleMenuButton" #> toggleMenuElement
  }

  val toggleMenuElement: Elem = {
    <a onclick="$('body').toggleClass('sidebar-collapse')" class="sidebar-toggle p-3" role="button">
      <i class="fa fa-bars"></i>
      <span class="visually-hidden">Toggle navigation</span>
    </a>
  }
}
