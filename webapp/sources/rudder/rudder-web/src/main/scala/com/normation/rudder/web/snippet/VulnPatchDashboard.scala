package com.normation.rudder.web.snippet

import com.normation.plugins.SecureExtendableSnippet
import com.normation.rudder.tenants.QueryContext
import scala.xml.NodeSeq

class VulnPatchDashboard extends SecureExtendableSnippet[VulnPatchDashboard] {

  def mainSecureDispatch: QueryContext ?=> Map[String, NodeSeq => NodeSeq] = {
    Map(
      "details" -> details
    )
  }
  def details = identity[NodeSeq]
}
