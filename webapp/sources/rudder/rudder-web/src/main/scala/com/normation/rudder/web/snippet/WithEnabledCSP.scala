package com.normation.rudder.web.snippet

import net.liftweb.http.RequestVar
import net.liftweb.http.StatefulSnippet
import scala.xml.*

object WithEnabledCSP extends StatefulSnippet {

  /**
    * Holder for state of strict CSP headers activation
    */
  private object enabled extends RequestVar[Boolean](false) {}

  def dispatch: PartialFunction[String, NodeSeq => NodeSeq] = _ => enable()

  def enable(): NodeSeq => NodeSeq = {
    enabled.setIfUnset(true)
    identity
  }

  def isEnabled: Boolean = enabled.get

}
