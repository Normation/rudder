package com.normation.rudder.web.snippet

import net.liftweb.http.RequestVar
import net.liftweb.http.StatefulSnippet
import net.liftweb.util.Helpers
import scala.xml.Elem
import scala.xml.MetaData
import scala.xml.Node
import scala.xml.NodeSeq
import scala.xml.Null
import scala.xml.UnprefixedAttribute

object WithNonce extends StatefulSnippet {

  // value for debug purpose should not be empty as it could be confusing knowing how browser inspectors display nonces as ""
  private val defaultValue = "unknown-value"

  /**
    * Holder for a base64 nonce value in the lifetime of a request
    */
  private object nonce extends RequestVar[String](defaultValue) {}

  def dispatch: PartialFunction[String, NodeSeq => NodeSeq] = { case _ => render }

  def render(xhtml: NodeSeq): NodeSeq = {
    xhtml.map(scriptWithNonce(_))
  }

  def scriptWithNonce(base: Node): Node = {
    val currentNonce = nonce.setIfUnset(generateNonce)
    val attributes   = MetaData.update(base.attributes, base.scope, new UnprefixedAttribute("nonce", currentNonce, Null))

    base match {
      case e: Elem => e.copy(attributes = attributes)
      case _ => base
    }
  }

  private val base64encoder = java.util.Base64.getEncoder()

  private def generateNonce: String = {
    base64encoder.encodeToString(Helpers.randomString(20).getBytes("UTF-8"))
  }

  /**
    * Get the nonce value defined within the lifetime of the current request, and used in all html tags.
    * @return None if the nonce has not been generated in the current request (it means that no script has been set with a nonce in the page)
    */
  def getRequestNonce: Option[String] = {
    nonce.get match {
      case `defaultValue` => None
      case value          => Some(value)
    }
  }

}
