package com.normation.rudder.domain.reports

import com.normation.cfclerk.domain.ReportingLogic
import com.normation.cfclerk.domain.ReportingLogic.FocusReport
import com.normation.cfclerk.domain.ReportingLogic.FocusWorst
import com.normation.cfclerk.domain.ReportingLogic.WeightedReport
import com.normation.cfclerk.domain.ReportingLogic.WorstReportWeightedOne
import com.normation.cfclerk.domain.ReportingLogic.WorstReportWeightedSum
import com.normation.inventory.domain.NodeId
import org.junit.runner.RunWith
import org.specs2.mutable.Specification
import org.specs2.runner.JUnitRunner

@RunWith(classOf[JUnitRunner])
class TestBlockCompliance extends Specification {

  trait DummyComponentCompliance extends ComponentCompliance
  case class DummyBlockCompliance(
      override val componentName:  String,
      override val reportingLogic: ReportingLogic,
      override val subs:           List[DummyComponentCompliance]
  ) extends DummyComponentCompliance with BlockCompliance[DummyComponentCompliance]

  case class DummyValueCompliance(override val componentName: String, override val allReports: List[ReportType])
      extends DummyComponentCompliance {

    override def compliance: ComplianceLevel = ComplianceLevel.compute(allReports)
  }

  val classic = DummyBlockCompliance(
    "classic block",
    WeightedReport,
    DummyValueCompliance("success", ReportType.EnforceSuccess :: ReportType.EnforceSuccess :: Nil) ::
    DummyValueCompliance("error", ReportType.EnforceError :: Nil) ::
    DummyValueCompliance("non compliant", ReportType.AuditNonCompliant :: Nil) ::
    DummyValueCompliance("nothing :(", ReportType.NoAnswer :: Nil) ::
    Nil
  )

  val withSubBlocks = DummyBlockCompliance(
    "with SubBlocks",
    WeightedReport,
    classic ::
    DummyBlockCompliance(
      "error block",
      WeightedReport,
      DummyValueCompliance("error", ReportType.EnforceSuccess :: ReportType.EnforceError :: ReportType.EnforceError :: Nil) :: Nil
    ) ::
    DummyValueCompliance("compliant", ReportType.AuditCompliant :: Nil) ::
    Nil
  )

  trait DummyComponentComplianceByNode extends ComponentComplianceByNode
  case class DummyBlockComplianceByNode(
      override val componentName:  String,
      override val reportingLogic: ReportingLogic,
      override val subs:           List[DummyComponentComplianceByNode]
  ) extends DummyComponentComplianceByNode with BlockComplianceByNode[DummyComponentComplianceByNode]

  case class DummyValueComplianceByNode(
      override val componentName: String,
      override val reportsByNode: Map[NodeId, Seq[ReportType]]
  ) extends DummyComponentComplianceByNode {
    override def allReports: List[ReportType] = reportsByNode.values.toList.flatten
    override def compliance: ComplianceLevel  = ComplianceLevel.compute(allReports)
  }

  val (nid1, nid2, nid3) = (NodeId("n1"), NodeId("n2"), NodeId("n3"))
  val blockByNode        = DummyBlockComplianceByNode(
    "block by node",
    WeightedReport,
    DummyBlockComplianceByNode(
      "sub block",
      WeightedReport,
      DummyValueComplianceByNode(
        "sub value1",
        Map(
          (nid1, ReportType.EnforceSuccess :: Nil),
          (nid2, ReportType.AuditNonCompliant :: Nil),
          (nid3, ReportType.EnforceError :: Nil)
        )
      ) ::
      DummyValueComplianceByNode(
        "sub value2",
        Map(
          (nid1, ReportType.EnforceSuccess :: Nil),
          (nid2, ReportType.AuditCompliant :: Nil),
          (nid3, ReportType.EnforceSuccess :: Nil)
        )
      ) ::
      Nil
    ) ::
    DummyValueComplianceByNode(
      "direct value",
      Map(
        (nid1, ReportType.EnforceSuccess :: Nil),
        (nid2, ReportType.AuditCompliant :: Nil),
        (nid3, ReportType.EnforceError :: Nil)
      )
    ) ::
    Nil
  )

  sequential

  "Block classic (no subblock)" should {
    "Report on weighted sum" in {
      classic.compliance === ComplianceLevel(success = 2, error = 1, nonCompliant = 1, noAnswer = 1)
    }
    "Report on worst one" in {
      classic.copy(reportingLogic = WorstReportWeightedOne).compliance === ComplianceLevel(error = 1)
    }
    "Report on worst sum" in {
      classic.copy(reportingLogic = WorstReportWeightedSum).compliance === ComplianceLevel(error = 5)
    }
    "Report on focus" in {
      classic.copy(reportingLogic = FocusReport("nothing :(")).compliance === ComplianceLevel(noAnswer = 1)
    }
    "Report on focus worst" in {
      classic.copy(reportingLogic = FocusWorst).compliance === ComplianceLevel(error = 1)
    }
  }

  "Block with subblock" should {
    "Report on weighted sum" in {
      withSubBlocks.compliance === ComplianceLevel(success = 3, error = 3, compliant = 1, nonCompliant = 1, noAnswer = 1)
    }
    "Report on worst one" in {
      withSubBlocks.copy(reportingLogic = WorstReportWeightedOne).compliance === ComplianceLevel(error = 1)
    }
    "Report on worst sum" in {
      withSubBlocks.copy(reportingLogic = WorstReportWeightedSum).compliance === ComplianceLevel(error = 9)
    }
    "Report on focus" in {
      withSubBlocks.copy(reportingLogic = FocusReport("classic block")).compliance === ComplianceLevel(
        success = 2,
        error = 1,
        nonCompliant = 1,
        noAnswer = 1
      )
    }
    "Report on focus worst" in {
      withSubBlocks.copy(reportingLogic = FocusWorst).compliance === ComplianceLevel(error = 2, success = 1)
    }
  }

  "Block by node" should {
    "Report on weighted sum" in {
      blockByNode.compliance === ComplianceLevel(success = 4, error = 2, compliant = 2, nonCompliant = 1)
    }
    "Report on worst one" in {
      blockByNode.copy(reportingLogic = WorstReportWeightedOne).compliance === ComplianceLevel(
        success = 1,
        nonCompliant = 1,
        error = 1
      )
    }
    "Report on worst sum" in {
      blockByNode.copy(reportingLogic = WorstReportWeightedSum).compliance === ComplianceLevel(
        success = 3,
        nonCompliant = 3,
        error = 3
      )
    }
    "Report on focus" in {
      blockByNode.copy(reportingLogic = FocusReport("sub value2")).compliance === ComplianceLevel(success = 2, compliant = 1)
    }
    "Report on focus worst" in {
      blockByNode.copy(reportingLogic = FocusWorst).compliance === ComplianceLevel(
        error = 1,
        success = 2,
        nonCompliant = 1,
        compliant = 1
      )
    }
  }
}
