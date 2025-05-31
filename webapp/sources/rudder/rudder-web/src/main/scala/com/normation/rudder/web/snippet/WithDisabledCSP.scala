package com.normation.rudder.web.snippet

import net.liftweb.http.RequestVar
import net.liftweb.http.StatefulSnippet
import scala.xml.*

object WithDisabledCSP extends StatefulSnippet {

  /**
    * Holder for state of strict CSP headers, if they should be disabled on a specific page
    */
  private object disabled extends RequestVar[Boolean](false) {}

  def dispatch: PartialFunction[String, NodeSeq => NodeSeq] = _ => disable()

  def disable(): NodeSeq => NodeSeq = {
    disabled.setIfUnset(true)
    identity
  }

  def isDisabled: Boolean = disabled.get

}
