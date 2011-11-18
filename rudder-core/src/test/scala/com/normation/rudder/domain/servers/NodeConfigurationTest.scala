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

package com.normation.rudder.domain.servers


import junit.framework.TestSuite
import org.junit.Test
import org.junit._
import org.junit.Assert._
import org.junit.runner.RunWith
import org.junit.runners.BlockJUnit4ClassRunner
import com.normation.cfclerk.domain._
import com.normation.inventory.domain.AgentType
import com.normation.cfclerk.domain._
import com.normation.rudder.domain.policies.IdentifiableCFCPI
import com.normation.rudder.domain.policies.ConfigurationRuleId
import net.liftweb.common._



@RunWith(classOf[BlockJUnit4ClassRunner])
class NodeConfigurationTest {

	private val simplePolicy = new IdentifiableCFCPI(
	    new ConfigurationRuleId("crId"),
	    new CFCPolicyInstance(new CFCPolicyInstanceId("cfcId"),
			new PolicyPackageId(PolicyPackageName("ppId"), PolicyVersion("1.0")), 
			Map(), TrackerVariableSpec().toVariable(),
			priority = 0, serial = 0) // no variable
	)
	
	private val policyVaredOne = new IdentifiableCFCPI(
	    new ConfigurationRuleId("crId1"),
	    new CFCPolicyInstance(new CFCPolicyInstanceId("cfcId1"),
			new PolicyPackageId(PolicyPackageName("ppId1"), PolicyVersion("1.0")),
			Map("one" -> InputVariable(InputVariableSpec("one", ""), Seq("one"))),
			TrackerVariableSpec().toVariable(),
			priority = 0, serial = 0)  // one variable
	)

	
	private val policyOtherVaredOne = new IdentifiableCFCPI(
	    new ConfigurationRuleId("crId1"),
	    new CFCPolicyInstance(new CFCPolicyInstanceId("cfcId1"),
			new PolicyPackageId(PolicyPackageName("ppId1"), PolicyVersion("1.0")),
			Map("one" -> InputVariable(InputVariableSpec("one", ""), Seq("two"))), 
			TrackerVariableSpec().toVariable(),
			priority = 0, serial = 0)  // one variable
	)
	
	private val policyNextVaredOne = new IdentifiableCFCPI(
	    new ConfigurationRuleId("crId1"),
	    new CFCPolicyInstance(new CFCPolicyInstanceId("cfcId1"),
			new PolicyPackageId(PolicyPackageName("ppId1"), PolicyVersion("1.0")),
			Map("one" -> InputVariable(InputVariableSpec("one", ""), Seq("one"))),
			TrackerVariableSpec().toVariable(),
			priority = 0, serial = 1)  // next serial than policyVaredOne
	)
	
	private val policyVaredTwo = new IdentifiableCFCPI(
	    new ConfigurationRuleId("crId2"),
	    new CFCPolicyInstance(new CFCPolicyInstanceId("cfcId2"),
			new PolicyPackageId(PolicyPackageName("ppId2"), PolicyVersion("1.0")),
			Map("two" -> InputVariable(InputVariableSpec("two", ""), Seq("two"))), 
			TrackerVariableSpec().toVariable(),
			priority = 0, serial = 0) // one variable
  )
	    
	private val minNodeConf = new MinimalNodeConfig(
	    "name",
	    "hostname",
	    Seq(),
	    "psId",
	    "root"
	)
	    

	@Test
	def simpleCreateNodeConfiguration() {
		/* Create a simple node configuration and check its configuration after */
		val newNode = new SimpleNodeConfiguration("id",
		    Seq(),
		    Seq(),
		    false,
		    minNodeConf,
		    minNodeConf,
		    None,
		    Map(),
		    Map())
		assertEquals(newNode.isPolicyServer, false)
		assertEquals(newNode.getCurrentPolicyInstances.size, 0)
		assertEquals(newNode.getPolicyInstances.size, 0)
		
		assertEquals(newNode.getCurrentSystemVariables.size, 0)
		assertEquals(newNode.getTargetSystemVariables.size, 0)
		
		assertEquals(newNode.isModified, false)
		assertEquals(newNode.targetMinimalNodeConfig.agentsName.size, 0)
		
		// Now add a policy
		newNode.addPolicyInstance(simplePolicy) match {
		  case f: EmptyBox => 
		    val e = f ?~! "Error when adding policy instance %s on node %s".format(simplePolicy.configurationRuleId, newNode.id)
		    throw new RuntimeException(e.messageChain)
		  case Full(node) => 
		
		
				assertEquals(node.isModified, true)
				// Current policy don't change, but target does
				assertEquals(node.getCurrentPolicyInstances.size, 0)
				assertEquals(node.getPolicyInstances.size, 1)
				
				assertEquals(node.findPolicyInstanceByPolicy(new PolicyPackageId(PolicyPackageName("ppId"), PolicyVersion("1.0"))).size, 1)
				assertEquals(node.findPolicyInstanceByPolicy(new PolicyPackageId(PolicyPackageName("ppId1"), PolicyVersion("1.0"))).size, 0)
		
				assertEquals(node.findCurrentPolicyInstanceByPolicy(new PolicyPackageId(PolicyPackageName("ppId"), PolicyVersion("1.0"))).size, 0)
		
				assertEquals(node.getAllPoliciesNames().size, 1)
				assertEquals(node.getAllPoliciesNames().contains(new PolicyPackageId(PolicyPackageName("ppId"), PolicyVersion("1.0"))), true)
		}
	}
	
	@Test
	def completeCreateNodeConfiguration() {
		val newNode = new SimpleNodeConfiguration("id",
		    Seq(policyVaredOne), Seq(policyVaredOne),
		    false,
		    minNodeConf,
		    minNodeConf,
		    None,
		    Map(),
		    Map())
			
		assertEquals(newNode.isPolicyServer, false)
		assertEquals(newNode.getCurrentPolicyInstances.size, 1)
		assertEquals(newNode.getPolicyInstances.size, 1)
		
		assertEquals(newNode.getCurrentSystemVariables.size, 0)
		assertEquals(newNode.getTargetSystemVariables.size, 0)
		
		assertEquals(newNode.isModified, false)
		assertEquals(newNode.targetMinimalNodeConfig.agentsName.size, 0)
		
		assertEquals(newNode.findPolicyInstanceByPolicy(new PolicyPackageId(PolicyPackageName("ppId1"), PolicyVersion("1.0"))).size, 1)
		assertEquals(newNode.findPolicyInstanceByPolicy(new PolicyPackageId(PolicyPackageName("ppId"), PolicyVersion("1.0"))).size, 0)

		assertEquals(newNode.findCurrentPolicyInstanceByPolicy(new PolicyPackageId(PolicyPackageName("ppId1"), PolicyVersion("1.0"))).size, 1)
		  
		
		val modified = newNode.copy(targetSystemVariables = Map("one" -> InputVariable(InputVariableSpec("one", ""), Seq("one"))))
		    
		assertEquals(modified.isModified, false) // won't check system var
	}
	
	
}