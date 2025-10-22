package com.normation.rudder.domain.reports

import enumeratum.Enum
import enumeratum.EnumEntry
import scala.concurrent.duration.Duration
import zio.json.*
import zio.json.enumeratum.*

/*
 * This file defines data structures to inform what we should display when a node compliance
 * is expired (keep compliance for a longer time, etc)
 */

sealed abstract class NodeComplianceExpirationMode(override val entryName: String) extends EnumEntry

object NodeComplianceExpirationMode extends Enum[NodeComplianceExpirationMode] with EnumCodec[NodeComplianceExpirationMode] {

  /*
   * Historical expiration based on a 2 agent run duration + a grace period.
   * But if the node is in status `NoReportInInterval`, then display that
   */
  // snake case because it's the convention chosen in "rudder" global property
  case object ExpireImmediately extends NodeComplianceExpirationMode("expire_immediately")

  /*
   * Keep the last know compliance for given additional time.
   * At some point, we will certainly make that property "by-policyType", but
   * for now it is the same for all compliance (and it's ok since we are computed
   * at the same time for now).
   */
  // snake case because it's the convention chosen in "rudder" global property
  case object KeepLast extends NodeComplianceExpirationMode("keep_last")

  override def values: IndexedSeq[NodeComplianceExpirationMode] = findValues
}

case class NodeComplianceExpiration(mode: NodeComplianceExpirationMode, duration: Option[Duration])

object NodeComplianceExpiration {
  val default: NodeComplianceExpiration = NodeComplianceExpiration(NodeComplianceExpirationMode.ExpireImmediately, None)

  implicit val decoderDuration: JsonDecoder[Duration] = JsonDecoder[String].mapOrFail(s => {
    try {
      Right(Duration(s))
    } catch {
      case ex: Throwable => Left(s"Error decoding node compliance keep last duration: ${ex.getMessage}")
    }
  })

  implicit val decoderNodeComplianceExpiration: JsonDecoder[NodeComplianceExpiration] = DeriveJsonDecoder.gen
}
