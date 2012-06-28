package com.normation.rudder.domain.eventlog


import org.joda.time.DateTime
import com.normation.eventlog.EventActor
import com.normation.eventlog.EventLog
import com.normation.utils.HashcodeCaching
import scala.xml.NodeSeq
import com.normation.eventlog.EventLogDetails
import com.normation.eventlog.EventLogFilter
import com.normation.eventlog.EventLogType
import com.normation.rudder.domain.Constants


/**
 * Update the policy server
 */

sealed trait PolicyServerEventLog extends EventLog 


final case class UpdatePolicyServer(
    override val eventDetails : EventLogDetails
) extends PolicyServerEventLog with HashcodeCaching {
  override val cause = None
  override val eventType = UpdatePolicyServer.eventType
  override val eventLogCategory = PolicyServerLogCategory
  override def copySetCause(causeId:Int) = this.copy(eventDetails.copy(cause = Some(causeId)))
}

final case class AuthorizedNetworkModification(
   oldNetworks: Seq[String]
 , newNetworks: Seq[String] 
)

object UpdatePolicyServer extends EventLogFilter {
  override val eventType = UpdatePolicyServerEventType
 
  override def apply(x : (EventLogType, EventLogDetails)) : UpdatePolicyServer = UpdatePolicyServer(x._2)
  
  def buildDetails(modification: AuthorizedNetworkModification) : NodeSeq = {
    EventLog.withContent {
      <changeAuthorizedNetworks fileFormat={Constants.XML_FILE_FORMAT_2.toString}>
        <oldAuthorizedNetworks>{
          modification.oldNetworks.map { net => <net>{net}</net>}
        }</oldAuthorizedNetworks>
        <newAuthorizedNetworks>{
          modification.newNetworks.map { net => <net>{net}</net>}
        }</newAuthorizedNetworks>
      </changeAuthorizedNetworks>
    }
  }
  
}

object PolicyServerEventLogsFilter {
  final val eventList : List[EventLogFilter] = List(
      UpdatePolicyServer 
    )
}