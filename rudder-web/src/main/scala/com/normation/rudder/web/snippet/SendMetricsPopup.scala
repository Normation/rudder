package com.normation.rudder.web.snippet

import bootstrap.liftweb.RudderConfig
import net.liftweb.common._
import net.liftweb.http.js.JsCmds._
import net.liftweb.http.js.JE.JsRaw
import net.liftweb.http.DispatchSnippet
import net.liftweb.http.SHtml
import com.normation.rudder.web.model.CurrentUser
import com.normation.rudder.domain.eventlog.ApplicationStartedEventType
import org.joda.time.DateTime

class SendMetricsPopup extends DispatchSnippet with Loggable {

  private[this] val configService = RudderConfig.configService
  private[this] val eventLogRepo  = RudderConfig.eventLogRepository

  def dispatch = {
    case "display" => xml => Script(display)
  }

  def display = {

    configService.send_server_metrics match {
      case Full(Some(a)) =>
        Noop
      case Full(None) =>

        // Show the popup if the application is running for more than one day
        // Should be replaced with a smarter check on user
        val startEvent = eventLogRepo.getEventLogByCriteria(Some(s"eventType = '${ApplicationStartedEventType.serialize}'"), Some(1), Some("creationDate desc"))
        val showPopup = startEvent match {
          case Full(events) =>
            events.headOption.map(_.creationDate isBefore( DateTime.now minusDays 1)).getOrElse(false)
          case eb:EmptyBox =>
          val msg = eb ?~! "Could not get last application start event, do not display 'metrics' popup"
          logger.warn(msg.messageChain)
          false
        }
        if (showPopup) {
          // ajax callback
          def ajaxCall(value : Option[Boolean]) = {
            SHtml.ajaxInvoke(() => configService.set_send_server_metrics(value,None,CurrentUser.getActor) match {
              case Full(_) => JsRaw(s"""$$("#sendMetricsPopup").bsModal('hide')""")
              case eb : EmptyBox =>
                val msg = eb ?~! "Could not update 'metrics' property"
                logger.error(msg.messageChain)
                Noop
              }
            )
          }
          OnLoad(JsRaw(s"""
            $$("#noSendMetrics").click(function(){${ajaxCall(Some(false)).toJsCmd}});
            $$("#yesSendMetrics").click(function(){${ajaxCall(Some(true)).toJsCmd}});
            $$("#sendMetricsPopup").bsModal();"""))
        } else {
          Noop
        }
      case eb : EmptyBox =>
        val msg = eb ?~! "Could not get 'metrics' property"
        logger.error(msg.messageChain)
        Noop
    }
  }
}