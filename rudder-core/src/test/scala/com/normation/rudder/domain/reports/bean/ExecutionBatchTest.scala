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
import com.normation.rudder.domain.policies.ConfigurationRuleId
import com.normation.rudder.domain.policies.PolicyInstanceId
import com.normation.rudder.domain.reports.PolicyExpectedReports
import com.normation.rudder.domain.reports.ComponentCard
import org.joda.time.DateTime

@RunWith(classOf[JUnitRunner])
class ExecutionBatchTest extends Specification {
  private implicit def str2policyInstanceId(s:String) = PolicyInstanceId(s)
  private implicit def str2configurationRuleId(s:String) = ConfigurationRuleId(s)
  private implicit def str2nodeId(s:String) = NodeId(s)

  
  "An execution Batch, with one component, cardinality one, one node" should {
         
  	val uniqueExecutionBatch = new ConfigurationExecutionBatch(
       "cr", 
       Seq[PolicyExpectedReports](new PolicyExpectedReports(
           				"policy",
           				Seq(new ComponentCard("component", 1, Seq("None")  )))),
       12,
       new DateTime(),
       Seq[Reports](new ResultSuccessReport(new DateTime(), "cr", "policy", "one", 12, "component", "value",new DateTime(), "message")),
       Seq[NodeId]("one"),
       new DateTime(), None)

    "have one reports when we create it with one report" in {
      uniqueExecutionBatch.executionReports.size ==1
    }
    
    "have one success node when we create it with one success report" in {
      uniqueExecutionBatch.getSuccessServer == Seq(NodeId("one"))
    }
   
    "have no repaired node when we create it with one success report" in {
      (uniqueExecutionBatch.getRepairedServer.size == 0)
    }
    
    /*
    "have no warn node when we create it with one success report" in {
      uniqueExecutionBatch.getWarnServer.size == 0
    }*/
    "have no error node when we create it with one success report" in {
      (uniqueExecutionBatch.getErrorServer.size == 0)
    }
    /*
    "have no warn report when we create it with one success report" in {
      uniqueExecutionBatch.getWarnReports.size == 0 
    }*/
    
    "have no error report when we create it with one success report" in {
      uniqueExecutionBatch.getErrorReports.size == 0
    }
    "have no node without answer when we create it with one success report" in {
      uniqueExecutionBatch.getServerWithNoReports.size == 0 &&
      uniqueExecutionBatch.getPendingServer.size == 0
    }
    
    "have one success report when we create it with one success report" in {
      uniqueExecutionBatch.getSuccessReports.size == 1
    }
  }
  
  "An execution Batch, with one component, cardinality one, two nodes" should {
 
    val multipleNodeExecutionBatch = new ConfigurationExecutionBatch(
       "cr", 
       Seq[PolicyExpectedReports](new PolicyExpectedReports(
           				"policy",
           				Seq(new ComponentCard("component", 1, Seq("None") )))),
       12,
       new DateTime(),
       Seq[Reports](new ResultSuccessReport(new DateTime(), "cr", "policy", "one", 12, "component", "value",new DateTime(), "message")),
       Seq[NodeId]("one", "two"),
       new DateTime(), None)
    
    "have one reports when we create it with one report" in {
      multipleNodeExecutionBatch.executionReports.size ==1
    }
    
    "have one success node when we create it with one success report" in {
      multipleNodeExecutionBatch.getSuccessServer == Seq(NodeId("one"))
    }
    
    "have no repaired node when we create it with one success report" in {
      (multipleNodeExecutionBatch.getRepairedServer.size == 0)
    }
   /*
    "have no warn node when we create it with one success report" in {
      multipleNodeExecutionBatch.getWarnServer.size == 0 
      
    }*/
    
    "have no error node when we create it with one success report" in {
      multipleNodeExecutionBatch.getErrorServer.size == 0
    }
    
    "have no warn nor error report when we create it with one success report" in {
      //multipleNodeExecutionBatch.getWarnReports.size == 0 &&
      multipleNodeExecutionBatch.getErrorReports.size == 0
    }
    "have one node without answer when we create it with one success report" in {
      multipleNodeExecutionBatch.getPendingServer.size == 1
    }
  } 
  
  "An execution Batch, with one component, cardinality one, two nodes" should {
    val reports = Seq[Reports](
        new ResultSuccessReport(new DateTime(), "cr", "policy", "one", 12, "component", "value",new DateTime(), "message"),
        new ResultSuccessReport(new DateTime(), "cr", "policy", "two", 12, "component", "value", new DateTime(),"message")
              )
              
    val multipleNodeExecutionBatch = new ConfigurationExecutionBatch(
       "cr", 
       Seq[PolicyExpectedReports](new PolicyExpectedReports(
           				"policy",
           				Seq(new ComponentCard("component", 1, Seq("None") )))),
       12,
       new DateTime(),
       reports,
       Seq[NodeId]("one", "two"),
       new DateTime(), None)
    
    "have two reports when we create it with two report" in {
      multipleNodeExecutionBatch.executionReports.size == 2
    }
    
    "have two success node when we create it with two success report" in {
      multipleNodeExecutionBatch.getSuccessServer === Seq(NodeId("one"), NodeId("two"))
    }
    
    "have no repaired node when we create it with two success report" in {
      (multipleNodeExecutionBatch.getRepairedServer.size == 0)
    }
   
    "have no warn nor error node when we create it with two success report" in {
      //multipleNodeExecutionBatch.getWarnServer.size == 0 &&
      multipleNodeExecutionBatch.getErrorServer.size == 0
    }
    
    "have no warn nor error report when we create it with two success report" in {
     // multipleNodeExecutionBatch.getWarnReports.size == 0 &&
      multipleNodeExecutionBatch.getErrorReports.size == 0
    }
    "have no node without answer when we create it with two success report" in {
      multipleNodeExecutionBatch.getPendingServer.size == 0
    }
    "have two success report when we create it with two success report" in {
      multipleNodeExecutionBatch.getSuccessReports.size == 2
    }
        
  }
  
  "An execution Batch, with one component, cardinality two, two nodes" should {
    val reports = Seq[Reports](
        new ResultSuccessReport(new DateTime(), "cr", "policy", "one", 12, "component", "value",new DateTime(), "message"),
        new ResultSuccessReport(new DateTime(), "cr", "policy", "two", 12, "component", "value", new DateTime(),"message")
              )
              
    val multipleNodeExecutionBatch = new ConfigurationExecutionBatch(
       "cr", 
       Seq[PolicyExpectedReports](new PolicyExpectedReports(
           				"policy",
           				Seq(new ComponentCard("component", 2, Seq("None", "None") )))),
       12,
       new DateTime(),
       reports,
       Seq[NodeId]("one", "two"),
       new DateTime(), None)
    
    "have two reports when we create it with two report" in {
      multipleNodeExecutionBatch.executionReports.size == 2
    }
    
    "have zero success node when we create it with not enough success report" in {
      multipleNodeExecutionBatch.getSuccessServer.size == 0
    }
   
    "have no repaired node when we create it with two success report" in {
      (multipleNodeExecutionBatch.getRepairedServer.size == 0)
    }
    /*
    "have no warn when we create it with two success report" in {
      multipleNodeExecutionBatch.getWarnServer.size == 0 
    }*/
    
    "have two error when we create it with not enough report" in {
      multipleNodeExecutionBatch.getErrorServer === Seq(NodeId("one"), NodeId("two"))
    }
     
    "have no warn nor error report when we create it with two success report" in {
    //  multipleNodeExecutionBatch.getWarnReports.size == 0 &&
      multipleNodeExecutionBatch.getErrorReports.size == 0
    }
    "have no node without answer when we create it with two success report" in {
      multipleNodeExecutionBatch.getPendingServer.size == 0
    }
    "have two success report when we create it with two success report" in {
      multipleNodeExecutionBatch.getSuccessReports.size == 2
    }
        
  }

  "An execution Batch, with one component, cardinality two, three nodes" should {
    val reports = Seq[Reports](
        new ResultSuccessReport(new DateTime(), "cr", "policy", "one", 12, "component", "value",new DateTime(), "message"),
        new ResultSuccessReport(new DateTime(), "cr", "policy", "one", 12, "component", "value2", new DateTime(),"message"),
        new LogRepairedReport(new DateTime(), "cr", "policy", "two", 12, "component", "value", new DateTime(),"message"),
        new ResultSuccessReport(new DateTime(), "cr", "policy", "two", 12, "component", "value", new DateTime(),"message"),
        new ResultSuccessReport(new DateTime(), "cr", "policy", "two", 12, "component", "value2", new DateTime(),"message"),
        new ResultErrorReport(new DateTime(), "cr", "policy", "three", 12, "component", "value", new DateTime(),"message"),
        new ResultSuccessReport(new DateTime(), "cr", "policy", "three", 12, "component", "value", new DateTime(),"message"),
        new ResultSuccessReport(new DateTime(), "cr", "policy", "three", 12, "component", "value", new DateTime(),"message")
              )
              
    val multipleNodeExecutionBatch = new ConfigurationExecutionBatch(
       "cr", 
       Seq[PolicyExpectedReports](new PolicyExpectedReports(
           				"policy",
           				Seq(new ComponentCard("component", 2, Seq("None", "None") )))),
       12,
       new DateTime(),
       reports,
       Seq[NodeId]("one", "two", "three"),
       new DateTime(), None)
    
    "have 8 reports when we create it with 8 reports" in {
      multipleNodeExecutionBatch.executionReports.size == 8
    }
    
    "have two success node when we create it with only one node with success only and one node with success and log info" in {
      multipleNodeExecutionBatch.getSuccessServer === Seq(NodeId("one"), NodeId("two"))
    }
   
    "have no repaired node" in {
      (multipleNodeExecutionBatch.getRepairedServer.size == 0)
    }
    /*
    "have two warn" in { // error and warn may overlap
      multipleNodeExecutionBatch.getWarnServer === Seq(NodeId("two"), NodeId("three")) 
    }*/
    
    "have one error" in {
      multipleNodeExecutionBatch.getErrorServer === Seq(NodeId("three"))
    }
     /*
    "have one warn report" in {
      multipleNodeExecutionBatch.getWarnReports.size == 1
    }*/
    "have one error report" in {
      multipleNodeExecutionBatch.getErrorReports.size == 1
    }

    "have no node without answer" in {
      multipleNodeExecutionBatch.getPendingServer.size == 0
    }
    "have six success reports when we create it with 6 success report" in {
      multipleNodeExecutionBatch.getSuccessReports.size == 6
    }
        
  }


  
  "An execution Batch, with two policy instance, two nodes, one component" should {
    val reports = Seq[Reports](
        new ResultSuccessReport(new DateTime(), "cr", "policy1", "one", 12, "component1", "None",new DateTime(), "message"),
        new ResultSuccessReport(new DateTime(), "cr", "policy2", "one", 12, "component2", "None",new DateTime(), "message")
              )
              
    val multipleNodeExecutionBatch = new ConfigurationExecutionBatch(
       "cr", 
       Seq[PolicyExpectedReports](new PolicyExpectedReports(
           				"policy1",
           				Seq(new ComponentCard("component1", 1, Seq("None") ))),
           				new PolicyExpectedReports(
           				"policy2",
           				Seq(new ComponentCard("component2", 1, Seq("None") )))
       ),
       12,
       new DateTime(),
       reports,
       Seq[NodeId]("one", "two"),
       new DateTime(), None)
    
    "have two reports when we create it with two report" in {
      multipleNodeExecutionBatch.executionReports.size == 2
    }
    
    "have one only success node when we create it with only one node responding" in {
      multipleNodeExecutionBatch.getSuccessServer.size == 1
    }
   
    "have no repaired node when we create it with no repaired report" in {
      (multipleNodeExecutionBatch.getRepairedServer.size == 0)
    }
    
    /*"have no warn when we create it with no warn report" in {
      multipleNodeExecutionBatch.getWarnServer.size == 0 
    }*/
    
    "have no error when we create it with enough reports for the right node" in {
      multipleNodeExecutionBatch.getErrorServer.size == 0
    }
     
    "have no warn nor error report when we create it with two success report" in {
      //multipleNodeExecutionBatch.getWarnReports.size == 0 &&
      multipleNodeExecutionBatch.getErrorReports.size == 0
    }
    "have one node without answer when we create it with one node without answer" in {
      multipleNodeExecutionBatch.getPendingServer.size == 1
    }
    "have two success report when we create it with two success report" in {
      multipleNodeExecutionBatch.getSuccessReports.size == 2
    }
        
  }
  
   "An execution Batch, with one component, cardinality two, one node" should {
    val reports = Seq[Reports](
        new ResultSuccessReport(new DateTime(), "cr", "policy", "one", 12, "component", "value1",new DateTime(), "message"),
        new ResultRepairedReport(new DateTime(), "cr", "policy", "one", 12, "component", "value2", new DateTime(),"message"),
        new ResultSuccessReport(new DateTime(), "cr", "policy", "one", 12, "other_component", "bar",new DateTime(), "message")
              )
              
    val multipleNodeExecutionBatch = new ConfigurationExecutionBatch(
       "cr", 
       Seq[PolicyExpectedReports](new PolicyExpectedReports(
           				"policy",
           				Seq(new ComponentCard("component", 2, Seq("value1", "value2") )))),
       12,
       new DateTime(),
       reports,
       Seq[NodeId]("one"),
       new DateTime(), None)
    
    "have 3 reports when we create it with 3 reports" in {
      multipleNodeExecutionBatch.executionReports.size == 3
    }
    
    "have one repaired node" in {
      multipleNodeExecutionBatch.getRepairedServer.size == 1
    }
   
    "have no success node" in {
      multipleNodeExecutionBatch.getSuccessServer.size == 0
    }
    
    "have no error node" in {
      multipleNodeExecutionBatch.getErrorServer.size == 0
    }
    
   }
  

  "An execution Batch, two policies, one in success, one with wrong component value" should {
    val reports = Seq[Reports](
        new ResultSuccessReport(new DateTime(), "cr", "policy1", "one", 12, "motdConfiguration", "None",new DateTime(), "message"),
        new ResultSuccessReport(new DateTime(), "cr", "policy2", "one", 12, "aptPackageInstallation", "None",new DateTime(), "message")
              )
              
    val multipleNodeExecutionBatch = new ConfigurationExecutionBatch(
       "cr", 
       Seq[PolicyExpectedReports](new PolicyExpectedReports(
           				"policy1",
           				Seq(new ComponentCard("motdConfiguration", 1, Seq("None") ))),
           		new PolicyExpectedReports(
           				"policy2",
           				Seq(new ComponentCard("aptPackageInstallation", 1, Seq("vim") )))		
       ),
       12,
       new DateTime(),
       reports,
       Seq[NodeId]("one"),
       new DateTime(), None)
    
    "have 2 reports when we create it with 2 reports" in {
      multipleNodeExecutionBatch.executionReports.size == 2
    }
    
    "have no repaired node" in {
      multipleNodeExecutionBatch.getRepairedServer.size == 0
    }
   
    "have no success node" in {
      (multipleNodeExecutionBatch.getSuccessServer.size == 0)
    }
    
    "have one error node" in {
      multipleNodeExecutionBatch.getErrorServer.size == 1
    }
    
  }

  // Test the multiple identical keys
  "An execution Batch, with one component, one node, but the same key twices" should {
    val executionTimestamp = new DateTime()
    val reports = Seq[Reports](
        new ResultSuccessReport(executionTimestamp, "cr", "policy", "nodeId", 12, "component", "value", executionTimestamp, "message"),
        new ResultSuccessReport(executionTimestamp, "cr", "policy", "nodeId", 12, "component", "value", executionTimestamp, "message")        
              )
              
    val sameKeyExecutionBatch = new ConfigurationExecutionBatch(
       "cr", 
       Seq[PolicyExpectedReports](new PolicyExpectedReports(
                  "policy",
                  Seq(new ComponentCard("component", 2, Seq("value", "value") )))),
       12,
       executionTimestamp,
       reports,
       Seq[NodeId]("nodeId"),
       executionTimestamp, None)
    
    "have 2 reports when we create it with 2 reports" in {
      sameKeyExecutionBatch.executionReports.size == 2
    }
    
    "have one success node" in {
      sameKeyExecutionBatch.getSuccessServer.size == 1
    }
    
    "have no error node" in {
      sameKeyExecutionBatch.getErrorServer.size == 0
    }
    
    "have no repaired node" in {
      sameKeyExecutionBatch.getRepairedServer.size == 0
    }

    "have no unknown node" in {
      sameKeyExecutionBatch.getUnknownNodes.size == 0
    }
  }
  
  // Test the multiple identical keys
  "An execution Batch, with one component, one node, but the same key twices" should {
    val executionTimestamp = new DateTime()
    val reports = Seq[Reports](
        new ResultSuccessReport(executionTimestamp, "cr", "policy", "nodeId", 12, "component", "value", executionTimestamp, "message"),
        new ResultSuccessReport(executionTimestamp, "cr", "policy", "nodeId", 12, "component", "value", executionTimestamp, "message"),
        new ResultSuccessReport(executionTimestamp, "cr", "policy", "nodeId", 12, "component", "value", executionTimestamp, "message")
              )
              
    val sameKeyExecutionBatch = new ConfigurationExecutionBatch(
       "cr", 
       Seq[PolicyExpectedReports](new PolicyExpectedReports(
                  "policy",
                  Seq(new ComponentCard("component", 2, Seq("value", "value") )))),
       12,
       executionTimestamp,
       reports,
       Seq[NodeId]("nodeId"),
       executionTimestamp, None)
    
    "have 3 reports when we create it with 3 reports" in {
      sameKeyExecutionBatch.executionReports.size == 3
    }
    
    "have no success node when there are too many success reports" in {
      sameKeyExecutionBatch.getSuccessServer.size == 0
    }
    
    "have no error node when there are too many success reports" in {
      sameKeyExecutionBatch.getErrorServer.size == 0
    }
    
    "have no repaired node  when there are too many success reports" in {
      sameKeyExecutionBatch.getRepairedServer.size == 0
    }

    "have one unknown node  when there are too many success reports" in {
      sameKeyExecutionBatch.getUnknownNodes.size == 1
    }
  }
  
  // Test the multiple identical keys, with the None expectation, twice, and two results
  "An execution Batch, with one component, one node, but the same key with a None expectation" should {
    val executionTimestamp = new DateTime()
    val reports = Seq[Reports](
        new ResultSuccessReport(executionTimestamp, "cr", "policy", "nodeId", 12, "component", "value", executionTimestamp, "message"),
        new ResultSuccessReport(executionTimestamp, "cr", "policy", "nodeId", 12, "component", "value", executionTimestamp, "message")        
              )
              
    val sameKeyExecutionBatch = new ConfigurationExecutionBatch(
       "cr", 
       Seq[PolicyExpectedReports](new PolicyExpectedReports(
                  "policy",
                  Seq(new ComponentCard("component", 2, Seq("None", "None") )))),
       12,
       executionTimestamp,
       reports,
       Seq[NodeId]("nodeId"),
       executionTimestamp, None)
    
    "have 2 reports when we create it with 2 reports" in {
      sameKeyExecutionBatch.executionReports.size == 2
    }
    
    "have one success node" in {
      sameKeyExecutionBatch.getSuccessServer.size == 1
    }
    
    "have no error node" in {
      sameKeyExecutionBatch.getErrorServer.size == 0
    }
    
    "have no repaired node" in {
      sameKeyExecutionBatch.getRepairedServer.size == 0
    }

    "have no unknown node" in {
      sameKeyExecutionBatch.getUnknownNodes.size == 0
    }
  }
  
  // Test the multiple identical keys, with the None expectation, twice, and two results
  "An execution Batch, with one component, one node, but the same key with a None expectation, and too many reports" should {
    val executionTimestamp = new DateTime()
    val reports = Seq[Reports](
        new ResultSuccessReport(executionTimestamp, "cr", "policy", "nodeId", 12, "component", "value", executionTimestamp, "message"),
        new ResultSuccessReport(executionTimestamp, "cr", "policy", "nodeId", 12, "component", "value", executionTimestamp, "message"),
        new ResultSuccessReport(executionTimestamp, "cr", "policy", "nodeId", 12, "component", "value", executionTimestamp, "message")
              )
              
    val sameKeyExecutionBatch = new ConfigurationExecutionBatch(
       "cr", 
       Seq[PolicyExpectedReports](new PolicyExpectedReports(
                  "policy",
                  Seq(new ComponentCard("component", 2, Seq("None", "None") )))),
       12,
       executionTimestamp,
       reports,
       Seq[NodeId]("nodeId"),
       executionTimestamp, None)
    
    "have 3 reports when we create it with 3 reports" in {
      sameKeyExecutionBatch.executionReports.size == 3
    }
    
    "have no success node when there are too many success reports" in {
      sameKeyExecutionBatch.getSuccessServer.size == 0
    }
    
    "have no error node when there are too many success reports" in {
      sameKeyExecutionBatch.getErrorServer.size == 0
    }
    
    "have no repaired node when there are too many success reports" in {
      sameKeyExecutionBatch.getRepairedServer.size == 0
    }

    "have one unknown node when there are too many success reports" in {
      sameKeyExecutionBatch.getUnknownNodes.size == 1
    }
  }
  
  // Test the multiple identical keys, for reparation
  "An execution Batch, with one component, one node, but the same key twices and only reparation" should {
    val executionTimestamp = new DateTime()
    val reports = Seq[Reports](
        new ResultRepairedReport(executionTimestamp, "cr", "policy", "nodeId", 12, "component", "value", executionTimestamp, "message"),
        new ResultRepairedReport(executionTimestamp, "cr", "policy", "nodeId", 12, "component", "value", executionTimestamp, "message")        
              )
              
    val sameKeyExecutionBatch = new ConfigurationExecutionBatch(
       "cr", 
       Seq[PolicyExpectedReports](new PolicyExpectedReports(
                  "policy",
                  Seq(new ComponentCard("component", 2, Seq("value", "value") )))),
       12,
       executionTimestamp,
       reports,
       Seq[NodeId]("nodeId"),
       executionTimestamp, None)
    
    "have 2 reports when we create it with 2 reports" in {
      sameKeyExecutionBatch.executionReports.size == 2
    }
    
    "have zero success node" in {
      sameKeyExecutionBatch.getSuccessServer.size == 0
    }
    
    "have no error node" in {
      sameKeyExecutionBatch.getErrorServer.size == 0
    }
    
    "have one repaired node" in {
      sameKeyExecutionBatch.getRepairedServer.size == 1
    }

    "have no unknown node" in {
      sameKeyExecutionBatch.getUnknownNodes.size == 0
    }
  }
  
  // Test the multiple identical keys, for reparation
  "An execution Batch, with one component, one node, but the same key twices and only reparation" should {
    val executionTimestamp = new DateTime()
    val reports = Seq[Reports](
        new ResultRepairedReport(executionTimestamp, "cr", "policy", "nodeId", 12, "component", "value", executionTimestamp, "message"),
        new ResultRepairedReport(executionTimestamp, "cr", "policy", "nodeId", 12, "component", "value", executionTimestamp, "message"),
        new ResultSuccessReport(executionTimestamp, "cr", "policy", "nodeId", 12, "component", "value", executionTimestamp, "message")
              )
              
    val sameKeyExecutionBatch = new ConfigurationExecutionBatch(
       "cr", 
       Seq[PolicyExpectedReports](new PolicyExpectedReports(
                  "policy",
                  Seq(new ComponentCard("component", 2, Seq("value", "value") )))),
       12,
       executionTimestamp,
       reports,
       Seq[NodeId]("nodeId"),
       executionTimestamp, None)
    
    "have 3 reports when we create it with 3 reports" in {
      sameKeyExecutionBatch.executionReports.size == 3
    }
    
    "have zero success node" in {
      sameKeyExecutionBatch.getSuccessServer.size == 0
    }
    
    "have no error node" in {
      sameKeyExecutionBatch.getErrorServer.size == 0
    }
    
    "have one repaired node" in {
      sameKeyExecutionBatch.getRepairedServer.size == 1
    }

    "have no unknown node" in {
      sameKeyExecutionBatch.getUnknownNodes.size == 0
    }
  }
  
  // Test for mixed keys keys, with a value and valuetwice  expectation, and three results
  "An execution Batch, with one component, one node, but the same key with a None expectation" should {
    val executionTimestamp = new DateTime()
    val reports = Seq[Reports](
        new ResultSuccessReport(executionTimestamp, "cr", "policy", "nodeId", 12, "component", "value", executionTimestamp, "message"),
        new ResultSuccessReport(executionTimestamp, "cr", "policy", "nodeId", 12, "component", "valuetwice", executionTimestamp, "message"),
        new ResultSuccessReport(executionTimestamp, "cr", "policy", "nodeId", 12, "component", "valuetwice", executionTimestamp, "message")
              )
              
    val sameKeyExecutionBatch = new ConfigurationExecutionBatch(
       "cr", 
       Seq[PolicyExpectedReports](new PolicyExpectedReports(
                  "policy",
                  Seq(new ComponentCard("component", 3, Seq("value", "valuetwice", "valuetwice") )))),
       12,
       executionTimestamp,
       reports,
       Seq[NodeId]("nodeId"),
       executionTimestamp, None)
    
    "have 3 reports when we create it with 3 reports" in {
      sameKeyExecutionBatch.executionReports.size == 3
    }
    
    "have one success node" in {
      sameKeyExecutionBatch.getSuccessServer.size == 1
    }
    
    "have no error node" in {
      sameKeyExecutionBatch.getErrorServer.size == 0
    }
    
    "have no repaired node" in {
      sameKeyExecutionBatch.getRepairedServer.size == 0
    }

    "have no unknown node" in {
      sameKeyExecutionBatch.getUnknownNodes.size == 0
    }
  }
  
  // Test for mixed keys keys, two policies, with a value and valuetwice  expectation, and three results
  "An execution Batch, with one component, one node, but the same key with a None expectation" should {
    val executionTimestamp = new DateTime()
    val reports = Seq[Reports](
        new ResultSuccessReport(executionTimestamp, "cr", "policy", "nodeId", 12, "component", "value", executionTimestamp, "message"),
        new ResultSuccessReport(executionTimestamp, "cr", "policy", "nodeId", 12, "component", "valuetwice", executionTimestamp, "message"),
        new ResultSuccessReport(executionTimestamp, "cr", "policy", "nodeId", 12, "component", "valuetwice", executionTimestamp, "message"),
        new ResultSuccessReport(executionTimestamp, "cr", "policy2", "nodeId", 12, "component", "value", executionTimestamp, "message")
              )
              
    val sameKeyExecutionBatch = new ConfigurationExecutionBatch(
       "cr", 
       Seq[PolicyExpectedReports](new PolicyExpectedReports(
                  "policy",
                  Seq(new ComponentCard("component", 3, Seq("value", "valuetwice", "valuetwice") ))),
                  new PolicyExpectedReports(
                  "policy2",
                  Seq(new ComponentCard("component", 1, Seq("value") )))
       ),
       12,
       executionTimestamp,
       reports,
       Seq[NodeId]("nodeId"),
       executionTimestamp, None)
    
    "have 4 reports when we create it with 4 reports" in {
      sameKeyExecutionBatch.executionReports.size == 4
    }
    
    "have one success node" in {
      sameKeyExecutionBatch.getSuccessServer.size == 1
    }
    
    "have no error node" in {
      sameKeyExecutionBatch.getErrorServer.size == 0
    }
    
    "have no repaired node" in {
      sameKeyExecutionBatch.getRepairedServer.size == 0
    }

    "have no unknown node" in {
      sameKeyExecutionBatch.getUnknownNodes.size == 0
    }
  }
}