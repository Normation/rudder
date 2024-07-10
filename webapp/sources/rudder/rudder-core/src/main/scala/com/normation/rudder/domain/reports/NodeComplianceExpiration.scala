package com.normation.rudder.domain.reports

import scala.concurrent.duration.Duration

/*
 * This file defines data structures to inform what we should display when a node compliance
 * is expired (keep compliance for a longer time, etc)
 */

sealed trait NodeComplianceExpiration {
  def id: String
}

object NodeComplianceExpiration {

  /*
   * Historical expiration based on a 2 agent run duration + a grace period.
   * But if the node is in status `NoReportInInterval`, then display that
   */
  case object ExpireImmediately extends NodeComplianceExpiration {
    val id = "expireImmediately"
  }

  /*
   * Keep the last know compliance for given additional time.
   * At some point, we will certainly make that property "by-policyType", but
   * for now it is the same for all compliance (and it's ok since we are computed
   * at the same time for now).
   */
  case class KeepLast(duration: Duration) extends NodeComplianceExpiration {
    def id = "keepLast"
  }
}
