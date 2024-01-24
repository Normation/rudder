package com.normation.rudder.web.snippet

import net.liftweb.http.DispatchSnippet
import net.liftweb.http.LiftRules
import net.liftweb.http.S

/**
  * We need to provide both lift.js and page js to the client since the scripts appended by lift is hard to intercept.
  * TODO: find a way to remove these duplicates after merging, or parse the whole html and add nonces to the scripts after merging
  * , see lift source code : 
  * https://github.com/lift/framework/blob/master/web/webkit/src/main/scala/net/liftweb/http/LiftSession.scala#L995
  * 
  * For now both scripts will be duplicated in the page, it does not induce weird behavior
  */
class CustomPageJs extends DispatchSnippet {

  def dispatch: DispatchIt = { case "pageScript" => _ => pageScript }

  def pageScript = {
    <script data-lift="with-nonce" src="/classpath/lift.js"></script>
    <script type="text/javascript" data-lift="with-nonce" src={scriptUrl(s"page/${S.renderVersion}.js")}></script>
  }

  private def scriptUrl(scriptFile: String) = {
    S.encodeURL(s"/${LiftRules.liftContextRelativePath}/$scriptFile")
  }
}
