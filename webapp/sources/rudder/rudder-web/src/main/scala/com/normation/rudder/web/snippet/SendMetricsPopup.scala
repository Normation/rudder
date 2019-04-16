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
import com.normation.rudder.domain.eventlog.ModifySendServerMetricsEventType
import com.normation.eventlog.ModificationId
import com.normation.rudder.domain.appconfig.RudderWebProperty
import com.normation.rudder.domain.appconfig.RudderWebPropertyName

import com.normation.box._

class SendMetricsPopup extends DispatchSnippet with Loggable {

  private[this] val configService = RudderConfig.configService
  private[this] val eventLogRepo  = RudderConfig.eventLogRepository
  private[this] val uuidGen  = RudderConfig.stringUuidGenerator

  def dispatch = {
    case "display" => xml => Script(display())
  }

  def display() = {

    configService.send_server_metrics.toBox match {
      case Full(Some(a)) =>
        Noop
      case Full(None) =>

        // Show the popup if the application is running for more than one day, and if there was no event on 'send metrics' in the last 6 hours
        // Should be replaced with a smarter check on user
        val startEvent = eventLogRepo.getEventLogByCriteria(Some(s"eventType = '${ApplicationStartedEventType.serialize}'"), Some(1), Some("creationDate desc")).toBox
        val showPopup = startEvent match {
          case Full(events) =>
            // Check if the application is running for more than one day
            if (events.headOption.map(_.creationDate isBefore( DateTime.now minusDays 1)).getOrElse(false)) {
              // Check if there was an event of modification on the property
              // Only kind of event here, should be 'set the value to none' which is generated when clicking on 'ask later' button
              eventLogRepo.getEventLogByCriteria(Some(s"eventType = '${ModifySendServerMetricsEventType.serialize}'"), Some(1), Some("creationDate desc")).toBox match {
                case Full(events) =>
                  // If there is one event, we should check if the vent is older than 6 hours
                  events.headOption.map(_.creationDate isBefore( DateTime.now minusHours 6)).getOrElse(true)
                case eb:EmptyBox =>
                  val msg = eb ?~! "Could not get last application start event, do not display 'metrics' popup"
                  logger.warn(msg.messageChain)
                  false
              }
            } else {
              false
            }

          case eb:EmptyBox =>
          val msg = eb ?~! "Could not get last application start event, do not display 'metrics' popup"
          logger.warn(msg.messageChain)
          false
        }
        if (showPopup) {
          // ajax callback
          def ajaxCall(value : Option[Boolean]) = {
            SHtml.ajaxInvoke(() =>
              value match {
                case _ : Some[Boolean] =>
                  configService.set_send_server_metrics(value,CurrentUser.actor,Some("Property modified from 'Send metrics' popup")).toBox match {
                    case Full(_) => JsRaw(s"""$$("#sendMetricsPopup").bsModal('hide')""")
                    case eb : EmptyBox =>
                      val msg = eb ?~! "Could not update 'metrics' property"
                      logger.error(msg.messageChain)
                      Noop
                    }
                case None =>
                  val modId = ModificationId(uuidGen.newUuid)
                  val actor = CurrentUser.actor
                  val property = RudderWebProperty(RudderWebPropertyName("send_server_metrics"),"none","")
                    eventLogRepo.saveModifyGlobalProperty(modId, actor, property, property, ModifySendServerMetricsEventType, Some("Ask later for 'send metrics' value"))
                    JsRaw(s"""$$("#sendMetricsPopup").bsModal('hide')""")

              }
            )
          }
          OnLoad(JsRaw(s"""
            $$("#noSendMetrics").click(function(){${ajaxCall(Some(false)).toJsCmd}});
            $$("#yesSendMetrics").click(function(){${ajaxCall(Some(true)).toJsCmd}});
            $$("#laterSendMetrics").click(function(){${ajaxCall(None).toJsCmd}});
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
