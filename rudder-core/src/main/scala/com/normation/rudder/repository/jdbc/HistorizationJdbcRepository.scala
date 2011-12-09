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

package com.normation.rudder.repository.jdbc


import org.joda.time.DateTime
import org.squeryl.PrimitiveTypeMode._
import org.squeryl.Schema
import org.squeryl.annotations.Column
import net.liftweb.common._
import org.squeryl.KeyedEntity
import java.sql.Timestamp
import com.normation.rudder.domain.nodes.NodeGroup
import com.normation.rudder.repository.HistorizationRepository
import com.normation.rudder.domain.policies.PolicyInstance
import com.normation.rudder.domain.policies.UserPolicyTemplate
import com.normation.utils.HashcodeCaching
import com.normation.cfclerk.domain.PolicyPackage
import org.squeryl.dsl.CompositeKey2
import com.normation.rudder.domain.policies.ConfigurationRule
import com.normation.rudder.domain.policies.PolicyInstanceId
import com.normation.rudder.domain.policies.ConfigurationRuleId
import com.normation.rudder.domain.policies.GroupTarget
import com.normation.rudder.domain.nodes.NodeGroupId
import com.normation.rudder.domain.nodes.Node
import com.normation.rudder.domain.nodes.NodeInfo


class HistorizationJdbcRepository(squerylConnectionProvider : SquerylConnectionProvider)  extends HistorizationRepository with  Loggable {

  def toTimeStamp(d:DateTime) : Timestamp = new Timestamp(d.getMillis)
  
   
  def getAllOpenedNodes() : Seq[SerializedNodes] = {
   
    squerylConnectionProvider.ourTransaction {
	    val q = from(Nodes.nodes)(node => 
	      where(node.endTime.isNull)
	      select(node)
	    )
      q.toList
    }
  }
 
  def getAllNodes(after : Option[DateTime]) : Seq[SerializedNodes] = {
    squerylConnectionProvider.ourTransaction {
	    val q = from(Nodes.nodes)(node =>
	      where(after.map(date => {
	        node.startTime > toTimeStamp(date) or
	        (node.endTime.isNotNull and node.endTime.>(Some(toTimeStamp(date))))
	        
	         
	      }).getOrElse(1===1))
	      select(node)
	    )
      q.toList
    }
  }
  
  
  def updateNodes(nodes : Seq[NodeInfo], closable : Seq[String]) :Seq[SerializedNodes] = {
    squerylConnectionProvider.ourTransaction {
	    // close the nodes
	    val q = update(Nodes.nodes)(node => 
	      where(node.endTime.isNull and node.nodeId.in(nodes.map(x => x.id.value) ++ closable))
	      set(node.endTime := Some(toTimeStamp(new DateTime())))
	    )
	    
	    val insertion = Nodes.nodes.insert(nodes.map(SerializedNodes.fromNode(_)))
	    // add the new ones
     Seq()
    }
  }
  
  def getAllOpenedGroups() : Seq[SerializedGroups] = {
   
    squerylConnectionProvider.ourTransaction {
	    val q = from(Groups.groups)(group => 
	      where(group.endTime.isNull)
	      select(group)
	    )
	    q.toList
    }
  }
  
  def getAllGroups(after : Option[DateTime]) : Seq[SerializedGroups] = {
    squerylConnectionProvider.ourTransaction {
	    val q = from(Groups.groups)(group =>
	      where(after.map(date => {
	        group.startTime > toTimeStamp(date) or
	        (group.endTime.isNotNull and group.endTime.>(Some(toTimeStamp(date))))
	        
	         
	      }).getOrElse(1===1))
	      select(group)
	    )
      q.toList
	
    }
  }
  
  
  
  def updateGroups(nodes : Seq[NodeGroup], closable : Seq[String]) :Seq[SerializedGroups] = {
    squerylConnectionProvider.ourTransaction {
	    // close the nodes
	    val q = update(Groups.groups)(group => 
	      where(group.endTime.isNull and group.groupId.in(nodes.map(x => x.id.value) ++ closable))
	      set(group.endTime := Some(toTimeStamp(new DateTime())))
	    )
	    
	    val insertion = Groups.groups.insert(nodes.map(SerializedGroups.fromNodeGroup(_)))
	    // add the new ones
      Seq()
    }
  }
  
  
  def getAllOpenedPIs() : Seq[SerializedPIs] = {
   
    squerylConnectionProvider.ourTransaction {
	    val q = from(PolicyInstances.policyInstances)(pi => 
	      where(pi.endTime.isNull)
	      select(pi)
	    )
	    q.toList
	    
    }
  }
  
   def getAllPIs(after : Option[DateTime]) : Seq[SerializedPIs] = {
    squerylConnectionProvider.ourTransaction {
	    val q = from(PolicyInstances.policyInstances)(pi =>
	      where(after.map(date => {
	        pi.startTime > toTimeStamp(date) or
	        (pi.endTime.isNotNull and pi.endTime > toTimeStamp(date))
	      }).getOrElse(1===1))
	      select(pi)
	    )
      q.toList

    }
  }

  def updatePIs(pis : Seq[(PolicyInstance, UserPolicyTemplate, PolicyPackage)], 
      				closable : Seq[String]) :Seq[SerializedPIs] = {

    squerylConnectionProvider.ourTransaction {
	    // close the pis
	    val q = update(PolicyInstances.policyInstances)(pi => 
	      where(pi.endTime.isNull and pi.policyInstanceId.in(pis.map(x => x._1.id.value) ++ closable))
	      set(pi.endTime := toTimeStamp(new DateTime()))
	    )
	    
	    val insertion = PolicyInstances.policyInstances.insert(pis.map(x => SerializedPIs.fromPolicyInstance(x._1, x._2, x._3)))
	    // add the new ones
     
     Seq()
    }
  }
  
  def getAllOpenedCRs() : Seq[ConfigurationRule] = {
    squerylConnectionProvider.ourTransaction {
	    val q = from(ConfigurationRules.configurationRules)(cr => 
	      where(cr.endTime.isNull)
	      select(cr)
	    )
	    val crs = q.toList
	    
	    
	    // Now that we have the opened CR, we must complete them
	    val pis = from(ConfigurationRules.pis)(pi => 
	      where(pi.crid.in(crs.map(x => x.id)))
	      select(pi)
	    )
	    val groups = from(ConfigurationRules.groups)(group => 
	      where(group.crid.in(crs.map(x => x.id)))
	      select(group)
	    )
	    
	    
	    val (piSeq, groupSeq) = (pis.toList, groups.toList)
	        
	    crs.map ( cr => (cr, 
	    		groupSeq.filter(group => group.crid == cr.id),
	        piSeq.filter(pi => pi.crid == cr.id)	        	
	    )).map( x=> SerializedCRs.fromSerialized(x._1, x._2, x._3) )
    }
    
  }

  def getAllCRs(after : Option[DateTime]) : Seq[(SerializedCRs, Seq[SerializedCRGroups],  Seq[SerializedCRPIs])] = {
    squerylConnectionProvider.ourTransaction {
	    val q = from(ConfigurationRules.configurationRules)(cr => 
	       where(after.map(date => {
	        cr.startTime > toTimeStamp(date) or
	        (cr.endTime.isNotNull and cr.endTime > toTimeStamp(date))
	      }).getOrElse(1===1))
	      select(cr)
	    )
	    val crs = q.toList
	    
	    
	    // Now that we have the opened CR, we must complete them
	    val pis = from(ConfigurationRules.pis)(pi => 
	      where(pi.crid.in(crs.map(x => x.id)))
	      select(pi)
	    )
	    val groups = from(ConfigurationRules.groups)(group => 
	      where(group.crid.in(crs.map(x => x.id)))
	      select(group)
	    )
	    
	    
	    val (piSeq, groupSeq) = crs.size match {
	      case 0 => (Seq(), Seq())
	      case _ => (pis.toSeq, groups.toSeq)
	    }
	        
	    crs.map ( cr => (cr, 
	    		groupSeq.filter(group => group.crid == cr.id),
	        piSeq.filter(pi => pi.crid == cr.id)	        	
	    ))
    }
    
  }
  
  
  def updateCrs(crs : Seq[ConfigurationRule], closable : Seq[String]) : Unit = {
    squerylConnectionProvider.ourTransaction {     
	    // close the crs
	    val q = update(ConfigurationRules.configurationRules)(cr => 
	      where(cr.endTime.isNull and cr.configurationRuleId.in(crs.map(x => x.id.value) ++ closable))
	      set(cr.endTime := toTimeStamp(new DateTime()))
	    )
   
	    crs.map( cr => {  
	      val serialized = ConfigurationRules.configurationRules.insert(SerializedCRs.toSerialized(cr))
	      
	      
	      cr.policyInstanceIds.map( pi => ConfigurationRules.pis.insert(new SerializedCRPIs(serialized.id, pi.value)))
	      
	      cr.target.map(group => group match {
	        case GroupTarget(groupId) => ConfigurationRules.groups.insert(new SerializedCRGroups(serialized.id, groupId.value))
	        case _ => //
	      })
	    })
	    
    }
  }
  
}





//// here are some utility classes to use with the service ////

case class SerializedGroups(
    @Column("groupid") groupId: String,
    @Column("groupname") groupName: String,
    @Column("groupdescription") groupDescription: String,
    @Column("nodecount") nodeCount: Int,
    @Column("starttime") startTime: Timestamp,
    @Column("endtime") endTime: Option[Timestamp]
) extends KeyedEntity[Long] {
  @Column("id")
  val id = 0L
}

object SerializedGroups {
  def fromNodeGroup(nodeGroup : NodeGroup) : SerializedGroups = {
    new SerializedGroups(nodeGroup.id.value, 
        		nodeGroup.name, 
        		nodeGroup.description, 
        		nodeGroup.serverList.size, 
        		new Timestamp(new DateTime().getMillis), None )
  }
}


object Groups extends Schema {
  val groups = table[SerializedGroups]("groups")
  
  on(groups)(t => declare( 
      t.id.is(autoIncremented("groupsid"), primaryKey)))
}

case class SerializedNodes(
    @Column("nodeid") nodeId: String,
    @Column("nodename") nodeName: String,
    @Column("nodedescription") nodeDescription: String,
    @Column("starttime") startTime: Timestamp,
    @Column("endtime") endTime: Option[Timestamp]
) extends KeyedEntity[Long] {
  @Column("id")
  val id = 0L
}

object SerializedNodes {
  def fromNode(node : NodeInfo) : SerializedNodes = {
    new SerializedNodes(node.id.value, 
        		node.hostname, 
        		node.description,  
        		new Timestamp(new DateTime().getMillis), None )
  }
}


object Nodes extends Schema {
  val nodes = table[SerializedNodes]("nodes")
  
  on(nodes)(t => declare( 
      t.id.is(autoIncremented("nodesid"), primaryKey)))
}

case class SerializedPIs(
    @Column("policyinstanceid") policyInstanceId: String,
    @Column("policyinstancename") policyInstanceName: String,
    @Column("policyinstancedescription") policyInstanceDescription: String,
    @Column("priority") priority: Int,
    @Column("policypackagename") policyPackageName: String,
    @Column("policypackagedescription") policyPackageDescription: String,
    @Column("policypackageversion") policyPackageVersion: String,
    @Column("starttime") startTime: Timestamp,
    @Column("endtime") endTime: Timestamp
) extends KeyedEntity[Long]  {
  @Column("id")
  val id = 0L
}

object SerializedPIs {
  def fromPolicyInstance(policyInstance : PolicyInstance, 
      userPT : UserPolicyTemplate,
      policyPackage : PolicyPackage) : SerializedPIs = {
    new SerializedPIs(policyInstance.id.value, 
        		policyInstance.name, 
        		policyInstance.shortDescription, 
        		policyInstance.priority,
        		userPT.referencePolicyTemplateName.value,
        		policyPackage.description,
        		policyInstance.policyTemplateVersion.toString,
        		new Timestamp(new DateTime().getMillis), null )
  }
}

object PolicyInstances extends Schema {
  val policyInstances = table[SerializedPIs]("policyinstances")
  
  on(policyInstances)(t => declare( 
      t.id.is(autoIncremented("policyinstancesid"), primaryKey)))
}

case class SerializedCRs(
    @Column("configurationruleid") configurationRuleId: String,
    @Column("serial") serial: Int,
    @Column("name") name: String,
    @Column("shortdescription") shortDescription: String,
    @Column("longdescription") longDescription: String,
    @Column("isactivated") isActivatedStatus: Boolean,
    @Column("starttime") startTime: Timestamp,
    @Column("endtime") endTime: Timestamp
) extends KeyedEntity[Long]  {
  @Column("id")
  val id = 0L
}

case class SerializedCRGroups(
    @Column("crid") crid: Long,// really, the id (not the cr one)
    @Column("groupid") groupId: String
) extends KeyedEntity[CompositeKey2[Long,String]]  {
 
  def id = compositeKey(crid, groupId)
}

case class SerializedCRPIs(
    @Column("crid") crid: Long,// really, the id (not the cr one)
    @Column("policyinstanceid") piid: String
) extends KeyedEntity[CompositeKey2[Long,String]]  {
 
  def id = compositeKey(crid, piid)
}

object SerializedCRs {
  def fromSerialized(cr : SerializedCRs, 
      							crgr : Seq[SerializedCRGroups],
      							crpi : Seq[SerializedCRPIs] ) : ConfigurationRule = {
    ConfigurationRule(
    		ConfigurationRuleId(cr.configurationRuleId),
    		cr.name, 
        cr.serial,
    		crgr.headOption.map(x => new GroupTarget(new NodeGroupId(x.groupId))), 
    		crpi.map(x => new PolicyInstanceId(x.piid)).toSet,
    		cr.shortDescription,
    		cr.longDescription,
    		cr.isActivatedStatus,
    		false
    )
    
  }

  def toSerialized(cr : ConfigurationRule) : SerializedCRs = {
    new SerializedCRs(cr.id.value,
        cr.serial,
        cr.name,
        cr.shortDescription,
        cr.longDescription, 
        cr.isActivatedStatus,
        new Timestamp(new DateTime().getMillis), null)
  }
  
}

object ConfigurationRules extends Schema {
  val configurationRules = table[SerializedCRs]("configurationrules")
  val groups = table[SerializedCRGroups]("configurationrulesgroups")
  val pis = table[SerializedCRPIs]("configurationrulespolicyinstance")
  
  on(configurationRules)(t => declare( 
      t.id.is(autoIncremented("configurationrulesid"), primaryKey)))

}

