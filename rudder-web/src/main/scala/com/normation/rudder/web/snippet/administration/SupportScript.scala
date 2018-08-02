package com.normation.rudder.web.snippet.administration


import net.liftweb._
import http._
import common._
import util.Helpers._
import js._




class SupportScript extends DispatchSnippet with Loggable {

  def dispatch = {
    case "render" => launchSupportScript
  }

    def launchSupportScript : IdMemoizeTransform = SHtml.idMemoize { outerXml =>

      // our process method returns a
      // JsCmd which will be sent back to the browser
      // as part of the response
      def process(): JsCmd = {
        //clear errors

        S.clearCurrentNotices
        S.redirectTo("/secure/api/system/support/info")
      }

      //process the list of networks
      "#launchSupportScriptButton" #> {
        SHtml.ajaxSubmit("Download support information", process _ ,("class","btn btn-primary"))
      }
    }

}
