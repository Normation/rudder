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

package bootstrap.liftweb
package checks

import javax.servlet.UnavailableException

class CheckPolicyInstanceBusinessRules extends BootstrapChecks {

  @throws(classOf[ UnavailableException ])
  def checks(): Unit = { 

  //TODO : actualise boot check with business rules
  
//  ldap foreach { con =>
//    
//    val policyNodeIds = con.searchOne(rudderDit.RUDDER_NODES.dn, ALL, A_NODE_UUID).map { server =>
//      server(A_NODE_UUID).getOrElse(error("A policy server is broken (no server UUID): " + server))
//    }
//    
//    //each policy server must have a group   
//    val groupDn = acceptedServersDit.GROUPS.NODES_BY_POLICY_SERVER.dn
// 
//    policyNodeIds foreach { policyNodeId =>
//      val possible = con.searchOne(groupDn, EQ(A_NAME,policyNodeId))
//      if(possible.isEmpty) {
//        val group = acceptedServersDit.GROUPS.GROUP.model(
//          Some(GroupUuid(uuidGen.newUuid)),
//          Some(groupDn)
//        )
//        group += (A_NAME, policyNodeId)
//        con.save(group)
//      } else if(possible.size > 1) {
//        //OK, so that's a problem. Log error, and does nothing ? 
//        error("Found multiple group of server by policy server with name '{}' : {}".format(policyNodeId, possible))
//      } else {
//        //OK
//        
//        val nodeGroupUuid = possible.head(A_GROUP_UUID).get
//        //check that that nodeGroupEntry has a policy instance, create one if not
//        val qid = "server-by-policy-server-"+policyNodeId
//        val q = new QueryEntity(
//        Some(qid), 
//        Some("All nodes which have '%s' as policy server".format()), 
//          Some(Query(
//              OC_NODE,
//              And,
//              Seq(CriterionLine(
//                 ditQueryData.criteriaMap(OC_GROUP_OF_DNS),
//                 Criterion(A_NAME,GroupOfDnsComparator),
//                 Equals,
//                 nodeGroupUuid
//              ))
//          )))
//         
//        val piToSave = PolicyInstanceEntry(Some(Constants.hasPolicyServerPIID(NodeId(policyNodeId))))(rudderDit)
//        piToSave.name = Some(Constants.P_HAS_POLICY_SERVER)
//        val now = Some(DateTime.now)
//        piToSave.creationDate = now
//        piToSave.lastUpdateDate = now
//        piToSave.target = Seq(qid) 
//        piToSave.variables = Map(Constants.V_POLICY_SERVER -> Seq(policyNodeId), Constants.V_OWNER -> Seq(root))
//        bridgeToCfclerkService.save(piToSave) match {
//          case f@Failure(_,_,_) => error(f)
//          case Empty => error("PolicyInstance server 'save' does not succeed without more information")
//        }
//  
//        
//      }
//    }
//  }    
    
    
  }

}
