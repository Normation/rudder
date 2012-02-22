/*
*************************************************************************************
* Copyright 2011 Normation SAS
*************************************************************************************
*
* This program is free software: you can redistribute it and/or modify
* it under the terms of the GNU Affero General Public License as
* published by the Free Software Foundation, either version 3 of the
* License, or (at your option) any later version.
*
* In accordance with the terms of section 7 (7. Additional Terms.) of
* the GNU Affero GPL v3, the copyright holders add the following
* Additional permissions:
* Notwithstanding to the terms of section 5 (5. Conveying Modified Source
* Versions) and 6 (6. Conveying Non-Source Forms.) of the GNU Affero GPL v3
* licence, when you create a Related Module, this Related Module is
* not considered as a part of the work and may be distributed under the
* license agreement of your choice.
* A "Related Module" means a set of sources files including their
* documentation that, without modification of the Source Code, enables
* supplementary functions or services in addition to those offered by
* the Software.
*
* This program is distributed in the hope that it will be useful,
* but WITHOUT ANY WARRANTY; without even the implied warranty of
* MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
* GNU Affero General Public License for more details.
*
* You should have received a copy of the GNU Affero General Public License
* along with this program. If not, see <http://www.gnu.org/licenses/agpl.html>.
*
*************************************************************************************
*/

package com.normation.rudder.domain.reports.bean


import org.junit.runner._
import org.specs2.mutable._
import org.specs2.runner._
import scala.collection._
import com.normation.inventory.domain.NodeId
import com.normation.rudder.domain.policies.RuleId
import com.normation.rudder.domain.policies.DirectiveId
import com.normation.rudder.domain.reports.DirectiveExpectedReports
import com.normation.rudder.domain.reports.ReportComponent
import org.joda.time.DateTime

@RunWith(classOf[JUnitRunner])
class ExecutionBatchTest extends Specification {
  private implicit def str2directiveId(s:String) = DirectiveId(s)
  private implicit def str2ruleId(s:String) = RuleId(s)
  private implicit def str2nodeId(s:String) = NodeId(s)

  
  "An execution Batch, with one component, cardinality one, one node" should {
         
    val uniqueExecutionBatch = new ConfigurationExecutionBatch(
       "rule", 
       Seq[DirectiveExpectedReports](new DirectiveExpectedReports(
                   "policy",
                   Seq(new ReportComponent("component", 1, Seq("None")  )))),
       12,
       DateTime.now(),
       Seq[Reports](new ResultSuccessReport(DateTime.now(), "rule", "policy", "one", 12, "component", "value",DateTime.now(), "message")),
       Seq[NodeId]("one"),
       DateTime.now(), None)

    "have one reports when we create it with one report" in {
      uniqueExecutionBatch.executionReports.size ==1
    }
    
    "have one success node when we create it with one success report" in {
      uniqueExecutionBatch.getSuccessNodeIds == Seq(NodeId("one"))
    }
   
    "have no repaired node when we create it with one success report" in {
      (uniqueExecutionBatch.getRepairedNodeIds.size == 0)
    }
    
    /*
    "have no warn node when we create it with one success report" in {
      uniqueExecutionBatch.getWarnNode.size == 0
    }*/
    "have no error node when we create it with one success report" in {
      (uniqueExecutionBatch.getErrorNodeIds.size == 0)
    }
    /*
    "have no warn report when we create it with one success report" in {
      uniqueExecutionBatch.getWarnReports.size == 0 
    }*/
    
    "have no error report when we create it with one success report" in {
      uniqueExecutionBatch.getErrorReports.size == 0
    }
    "have no node without answer when we create it with one success report" in {
      uniqueExecutionBatch.getNoReportNodeIds.size == 0 &&
      uniqueExecutionBatch.getPendingNodeIds.size == 0
    }
    
    "have one success report when we create it with one success report" in {
      uniqueExecutionBatch.getSuccessReports.size == 1
    }
  }
  
  "An execution Batch, with one component, cardinality one, two nodes" should {
 
    val multipleNodeExecutionBatch = new ConfigurationExecutionBatch(
       "rule", 
       Seq[DirectiveExpectedReports](new DirectiveExpectedReports(
                   "policy",
                   Seq(new ReportComponent("component", 1, Seq("None") )))),
       12,
       DateTime.now(),
       Seq[Reports](new ResultSuccessReport(DateTime.now(), "rule", "policy", "one", 12, "component", "value",DateTime.now(), "message")),
       Seq[NodeId]("one", "two"),
       DateTime.now(), None)
    
    "have one reports when we create it with one report" in {
      multipleNodeExecutionBatch.executionReports.size ==1
    }
    
    "have one success node when we create it with one success report" in {
      multipleNodeExecutionBatch.getSuccessNodeIds == Seq(NodeId("one"))
    }
    
    "have no repaired node when we create it with one success report" in {
      (multipleNodeExecutionBatch.getRepairedNodeIds.size == 0)
    }
   /*
    "have no warn NodeIds when we create it with one success report" in {
      multipleNodeExecutionBatch.getWarnNode.size == 0 
      
    }*/
    
    "have no error node when we create it with one success report" in {
      multipleNodeExecutionBatch.getErrorNodeIds.size == 0
    }
    
    "have no warn nor error report when we create it with one success report" in {
      //multipleNodeExecutionBatch.getWarnReports.size == 0 &&
      multipleNodeExecutionBatch.getErrorReports.size == 0
    }
    "have one node without answer when we create it with one success report" in {
      multipleNodeExecutionBatch.getPendingNodeIds.size == 1
    }
  } 
  
  "An execution Batch, with one component, cardinality one, two nodes" should {
    val reports = Seq[Reports](
        new ResultSuccessReport(DateTime.now(), "rule", "policy", "one", 12, "component", "value",DateTime.now(), "message"),
        new ResultSuccessReport(DateTime.now(), "rule", "policy", "two", 12, "component", "value", DateTime.now(),"message")
              )
              
    val multipleNodeExecutionBatch = new ConfigurationExecutionBatch(
       "rule", 
       Seq[DirectiveExpectedReports](new DirectiveExpectedReports(
                   "policy",
                   Seq(new ReportComponent("component", 1, Seq("None") )))),
       12,
       DateTime.now(),
       reports,
       Seq[NodeId]("one", "two"),
       DateTime.now(), None)
    
    "have two reports when we create it with two report" in {
      multipleNodeExecutionBatch.executionReports.size == 2
    }
    
    "have two success node when we create it with two success report" in {
      multipleNodeExecutionBatch.getSuccessNodeIds === Seq(NodeId("one"), NodeId("two"))
    }
    
    "have no repaired node when we create it with two success report" in {
      (multipleNodeExecutionBatch.getRepairedNodeIds.size == 0)
    }
   
    "have no warn nor error node when we create it with two success report" in {
      //multipleNodeExecutionBatch.getWarnNode.size == 0 &&
      multipleNodeExecutionBatch.getErrorNodeIds.size == 0
    }
    
    "have no warn nor error report when we create it with two success report" in {
     // multipleNodeExecutionBatch.getWarnReports.size == 0 &&
      multipleNodeExecutionBatch.getErrorReports.size == 0
    }
    "have no node without answer when we create it with two success report" in {
      multipleNodeExecutionBatch.getPendingNodeIds.size == 0
    }
    "have two success report when we create it with two success report" in {
      multipleNodeExecutionBatch.getSuccessReports.size == 2
    }
        
  }
  
  "An execution Batch, with one component, cardinality two, two nodes" should {
    val reports = Seq[Reports](
        new ResultSuccessReport(DateTime.now(), "rule", "policy", "one", 12, "component", "value",DateTime.now(), "message"),
        new ResultSuccessReport(DateTime.now(), "rule", "policy", "two", 12, "component", "value", DateTime.now(),"message")
              )
              
    val multipleNodeExecutionBatch = new ConfigurationExecutionBatch(
       "rule", 
       Seq[DirectiveExpectedReports](new DirectiveExpectedReports(
                   "policy",
                   Seq(new ReportComponent("component", 2, Seq("None", "None") )))),
       12,
       DateTime.now(),
       reports,
       Seq[NodeId]("one", "two"),
       DateTime.now(), None)
    
    "have two reports when we create it with two report" in {
      multipleNodeExecutionBatch.executionReports.size == 2
    }
    
    "have zero success node when we create it with not enough success report" in {
      multipleNodeExecutionBatch.getSuccessNodeIds.size == 0
    }
   
    "have no repaired node when we create it with two success report" in {
      (multipleNodeExecutionBatch.getRepairedNodeIds.size == 0)
    }
    /*
    "have no warn when we create it with two success report" in {
      multipleNodeExecutionBatch.getWarnNode.size == 0 
    }*/
    
    "have two error when we create it with not enough report" in {
      multipleNodeExecutionBatch.getErrorNodeIds === Seq(NodeId("one"), NodeId("two"))
    }
     
    "have no warn nor error report when we create it with two success report" in {
    //  multipleNodeExecutionBatch.getWarnReports.size == 0 &&
      multipleNodeExecutionBatch.getErrorReports.size == 0
    }
    "have no node without answer when we create it with two success report" in {
      multipleNodeExecutionBatch.getPendingNodeIds.size == 0
    }
    "have two success report when we create it with two success report" in {
      multipleNodeExecutionBatch.getSuccessReports.size == 2
    }
        
  }

  "An execution Batch, with one component, cardinality two, three nodes" should {
    val reports = Seq[Reports](
        new ResultSuccessReport(DateTime.now(), "rule", "policy", "one", 12, "component", "value",DateTime.now(), "message"),
        new ResultSuccessReport(DateTime.now(), "rule", "policy", "one", 12, "component", "value2", DateTime.now(),"message"),
        new LogRepairedReport(DateTime.now(), "rule", "policy", "two", 12, "component", "value", DateTime.now(),"message"),
        new ResultSuccessReport(DateTime.now(), "rule", "policy", "two", 12, "component", "value", DateTime.now(),"message"),
        new ResultSuccessReport(DateTime.now(), "rule", "policy", "two", 12, "component", "value2", DateTime.now(),"message"),
        new ResultErrorReport(DateTime.now(), "rule", "policy", "three", 12, "component", "value", DateTime.now(),"message"),
        new ResultSuccessReport(DateTime.now(), "rule", "policy", "three", 12, "component", "value", DateTime.now(),"message"),
        new ResultSuccessReport(DateTime.now(), "rule", "policy", "three", 12, "component", "value", DateTime.now(),"message")
              )
              
    val multipleNodeExecutionBatch = new ConfigurationExecutionBatch(
       "rule", 
       Seq[DirectiveExpectedReports](new DirectiveExpectedReports(
                   "policy",
                   Seq(new ReportComponent("component", 2, Seq("None", "None") )))),
       12,
       DateTime.now(),
       reports,
       Seq[NodeId]("one", "two", "three"),
       DateTime.now(), None)
    
    "have 8 reports when we create it with 8 reports" in {
      multipleNodeExecutionBatch.executionReports.size == 8
    }
    
    "have two success node when we create it with only one node with success only and one node with success and log info" in {
      multipleNodeExecutionBatch.getSuccessNodeIds === Seq(NodeId("one"), NodeId("two"))
    }
   
    "have no repaired node" in {
      (multipleNodeExecutionBatch.getRepairedNodeIds.size == 0)
    }
    /*
    "have two warn" in { // error and warn may overlap
      multipleNodeExecutionBatch.getWarnNode === Seq(NodeId("two"), NodeId("three")) 
    }*/
    
    "have one error" in {
      multipleNodeExecutionBatch.getErrorNodeIds === Seq(NodeId("three"))
    }
     /*
    "have one warn report" in {
      multipleNodeExecutionBatch.getWarnReports.size == 1
    }*/
    "have one error report" in {
      multipleNodeExecutionBatch.getErrorReports.size == 1
    }

    "have no node without answer" in {
      multipleNodeExecutionBatch.getPendingNodeIds.size == 0
    }
    "have six success reports when we create it with 6 success report" in {
      multipleNodeExecutionBatch.getSuccessReports.size == 6
    }
        
  }


  
  "An execution Batch, with two policy instance, two nodes, one component" should {
    val reports = Seq[Reports](
        new ResultSuccessReport(DateTime.now(), "rule", "policy1", "one", 12, "component1", "None",DateTime.now(), "message"),
        new ResultSuccessReport(DateTime.now(), "rule", "policy2", "one", 12, "component2", "None",DateTime.now(), "message")
              )
              
    val multipleNodeExecutionBatch = new ConfigurationExecutionBatch(
       "rule", 
       Seq[DirectiveExpectedReports](new DirectiveExpectedReports(
                   "policy1",
                   Seq(new ReportComponent("component1", 1, Seq("None") ))),
                   new DirectiveExpectedReports(
                   "policy2",
                   Seq(new ReportComponent("component2", 1, Seq("None") )))
       ),
       12,
       DateTime.now(),
       reports,
       Seq[NodeId]("one", "two"),
       DateTime.now(), None)
    
    "have two reports when we create it with two report" in {
      multipleNodeExecutionBatch.executionReports.size == 2
    }
    
    "have one only success node when we create it with only one node responding" in {
      multipleNodeExecutionBatch.getSuccessNodeIds.size == 1
    }
   
    "have no repaired node when we create it with no repaired report" in {
      (multipleNodeExecutionBatch.getRepairedNodeIds.size == 0)
    }
    
    /*"have no warn when we create it with no warn report" in {
      multipleNodeExecutionBatch.getWarnNode.size == 0 
    }*/
    
    "have no error when we create it with enough reports for the right node" in {
      multipleNodeExecutionBatch.getErrorNodeIds.size == 0
    }
     
    "have no warn nor error report when we create it with two success report" in {
      //multipleNodeExecutionBatch.getWarnReports.size == 0 &&
      multipleNodeExecutionBatch.getErrorReports.size == 0
    }
    "have one node without answer when we create it with one node without answer" in {
      multipleNodeExecutionBatch.getPendingNodeIds.size == 1
    }
    "have two success report when we create it with two success report" in {
      multipleNodeExecutionBatch.getSuccessReports.size == 2
    }
        
  }
  
   "An execution Batch, with one component, cardinality two, one node" should {
    val reports = Seq[Reports](
        new ResultSuccessReport(DateTime.now(), "rule", "policy", "one", 12, "component", "value1",DateTime.now(), "message"),
        new ResultRepairedReport(DateTime.now(), "rule", "policy", "one", 12, "component", "value2", DateTime.now(),"message"),
        new ResultSuccessReport(DateTime.now(), "rule", "policy", "one", 12, "other_component", "bar",DateTime.now(), "message")
              )
              
    val multipleNodeExecutionBatch = new ConfigurationExecutionBatch(
       "rule", 
       Seq[DirectiveExpectedReports](new DirectiveExpectedReports(
                   "policy",
                   Seq(new ReportComponent("component", 2, Seq("value1", "value2") )))),
       12,
       DateTime.now(),
       reports,
       Seq[NodeId]("one"),
       DateTime.now(), None)
    
    "have 3 reports when we create it with 3 reports" in {
      multipleNodeExecutionBatch.executionReports.size == 3
    }
    
    "have one repaired node" in {
      multipleNodeExecutionBatch.getRepairedNodeIds.size == 1
    }
   
    "have no success node" in {
      (multipleNodeExecutionBatch.getSuccessNodeIds.size == 0)
    }
    
    "have no error node" in {
      multipleNodeExecutionBatch.getErrorNodeIds.size == 0
    }
    
   }
  

  "An execution Batch, two policies, one in success, one with wrong component value" should {
    val reports = Seq[Reports](
        new ResultSuccessReport(DateTime.now(), "rule", "policy1", "one", 12, "motdConfiguration", "None",DateTime.now(), "message"),
        new ResultSuccessReport(DateTime.now(), "rule", "policy2", "one", 12, "aptPackageInstallation", "None",DateTime.now(), "message")
              )
              
    val multipleNodeExecutionBatch = new ConfigurationExecutionBatch(
       "rule", 
       Seq[DirectiveExpectedReports](new DirectiveExpectedReports(
                   "policy1",
                   Seq(new ReportComponent("motdConfiguration", 1, Seq("None") ))),
               new DirectiveExpectedReports(
                   "policy2",
                   Seq(new ReportComponent("aptPackageInstallation", 1, Seq("vim") )))    
       ),
       12,
       DateTime.now(),
       reports,
       Seq[NodeId]("one"),
       DateTime.now(), None)
    
    "have 2 reports when we create it with 2 reports" in {
      multipleNodeExecutionBatch.executionReports.size == 2
    }
    
    "have no repaired node" in {
      multipleNodeExecutionBatch.getRepairedNodeIds.size == 0
    }
   
    "have no success node" in {
      (multipleNodeExecutionBatch.getSuccessNodeIds.size == 0)
    }
    
    "have one error node" in {
      multipleNodeExecutionBatch.getErrorNodeIds.size == 1
    }
    
  }
}