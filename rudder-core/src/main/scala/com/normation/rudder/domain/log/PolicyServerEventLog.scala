package com.normation.rudder.domain.log


import org.joda.time.DateTime
import com.normation.eventlog.EventActor
import com.normation.eventlog.EventLog
import com.normation.utils.HashcodeCaching
import scala.xml.NodeSeq


/**
 * Update the policy server
 */

sealed trait PolicyServerEventLog extends EventLog 


final case class UpdatePolicyServer(
    override val id : Option[Int] = None
  , override val principal : EventActor
  , override val details : NodeSeq
  , override val creationDate : DateTime = DateTime.now()
  , override val severity : Int = 100
) extends PolicyServerEventLog with HashcodeCaching {
  override val cause = None
  override val eventType = UpdatePolicyServerEventType
  override val eventLogCategory = PolicyServerLogCategory
  override def copySetCause(causeId:Int) = this
}