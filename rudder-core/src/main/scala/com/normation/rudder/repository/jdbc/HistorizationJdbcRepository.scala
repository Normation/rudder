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
import com.normation.rudder.domain.policies.Directive
import com.normation.rudder.domain.policies.ActiveTechnique
import com.normation.utils.HashcodeCaching
import com.normation.cfclerk.domain.Technique
import org.squeryl.dsl.CompositeKey2
import com.normation.rudder.domain.policies.Rule
import com.normation.rudder.domain.policies.DirectiveId
import com.normation.rudder.domain.policies.RuleId
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
        set(node.endTime := Some(toTimeStamp(DateTime.now())))
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
        set(group.endTime := Some(toTimeStamp(DateTime.now())))
      )
      
      val insertion = Groups.groups.insert(nodes.map(SerializedGroups.fromNodeGroup(_)))
      // add the new ones
      Seq()
    }
  }
  
  
  def getAllOpenedDirectives() : Seq[SerializedDirectives] = {
   
    squerylConnectionProvider.ourTransaction {
      val q = from(Directives.directives)(directive => 
        where(directive.endTime.isNull)
        select(directive)
      )
      q.toList
      
    }
  }
  
   def getAllDirectives(after : Option[DateTime]) : Seq[SerializedDirectives] = {
    squerylConnectionProvider.ourTransaction {
      val q = from(Directives.directives)(directive =>
        where(after.map(date => {
          directive.startTime > toTimeStamp(date) or
          (directive.endTime.isNotNull and directive.endTime > toTimeStamp(date))
        }).getOrElse(1===1))
        select(directive)
      )
      q.toList

    }
  }

  def updateDirectives(directives : Seq[(Directive, ActiveTechnique, Technique)], 
              closable : Seq[String]) :Seq[SerializedDirectives] = {

    squerylConnectionProvider.ourTransaction {
      // close the directives
      val q = update(Directives.directives)(directive => 
        where(directive.endTime.isNull and directive.directiveId.in(directives.map(x => x._1.id.value) ++ closable))
        set(directive.endTime := toTimeStamp(DateTime.now()))
      )
      
      val insertion = Directives.directives.insert(directives.map(x => SerializedDirectives.fromDirective(x._1, x._2, x._3)))
      // add the new ones
     
     Seq()
    }
  }
  
  def getAllOpenedRules() : Seq[Rule] = {
    squerylConnectionProvider.ourTransaction {
      val q = from(Rules.rules)(rule => 
        where(rule.endTime.isNull)
        select(rule)
      )
      val rules = q.toList
      
      
      // Now that we have the opened CR, we must complete them
      val directives = from(Rules.directives)(directive => 
        where(directive.crid.in(rules.map(x => x.id)))
        select(directive)
      )
      val groups = from(Rules.groups)(group => 
        where(group.crid.in(rules.map(x => x.id)))
        select(group)
      )
      
      
      val (piSeq, groupSeq) = (directives.toList, groups.toList)
          
      rules.map ( rule => (rule, 
          groupSeq.filter(group => group.crid == rule.id),
          piSeq.filter(directive => directive.crid == rule.id)            
      )).map( x=> SerializedRules.fromSerialized(x._1, x._2, x._3) )
    }
    
  }

  def getAllRules(after : Option[DateTime]) : Seq[(SerializedRules, Seq[SerializedRuleGroups],  Seq[SerializedRuleDirectives])] = {
    squerylConnectionProvider.ourTransaction {
      val q = from(Rules.rules)(rule => 
         where(after.map(date => {
          rule.startTime > toTimeStamp(date) or
          (rule.endTime.isNotNull and rule.endTime > toTimeStamp(date))
        }).getOrElse(1===1))
        select(rule)
      )
      val rules = q.toList
      
      
      // Now that we have the opened CR, we must complete them
      val directives = from(Rules.directives)(directive => 
        where(directive.crid.in(rules.map(x => x.id)))
        select(directive)
      )
      val groups = from(Rules.groups)(group => 
        where(group.crid.in(rules.map(x => x.id)))
        select(group)
      )
      
      
      val (piSeq, groupSeq) = rules.size match {
        case 0 => (Seq(), Seq())
        case _ => (directives.toSeq, groups.toSeq)
      }
          
      rules.map ( rule => (rule, 
          groupSeq.filter(group => group.crid == rule.id),
          piSeq.filter(directive => directive.crid == rule.id)            
      ))
    }
    
  }
  
  
  def updateRules(rules : Seq[Rule], closable : Seq[String]) : Unit = {
    squerylConnectionProvider.ourTransaction {     
      // close the rules
      val q = update(Rules.rules)(rule => 
        where(rule.endTime.isNull and rule.ruleId.in(rules.map(x => x.id.value) ++ closable))
        set(rule.endTime := toTimeStamp(DateTime.now()))
      )
   
      rules.map( rule => {  
        val serialized = Rules.rules.insert(SerializedRules.toSerialized(rule))
        
        
        rule.directiveIds.map( directive => Rules.directives.insert(new SerializedRuleDirectives(serialized.id, directive.value)))
        
        rule.target.map(group => group match {
          case GroupTarget(groupId) => Rules.groups.insert(new SerializedRuleGroups(serialized.id, groupId.value))
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
    @Column("groupstatus") groupStatus: Int,
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
            isDynamicToSql(nodeGroup.isDynamic),
            new Timestamp(DateTime.now().getMillis), None )
  }
 
  def isDynamicToSql(boolean : Boolean) : Int = {
    boolean match {
      case true => 1;
      case false => 0;
    }
  }
  
  def fromSQLtoDynamic(value : Int) : Option[Boolean] = {
    value match {
      case 1 => Some(true)
      case 0 => Some(false)
      case _ => None
    }
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
            new Timestamp(DateTime.now().getMillis), None )
  }
}


object Nodes extends Schema {
  val nodes = table[SerializedNodes]("nodes")
  
  on(nodes)(t => declare( 
      t.id.is(autoIncremented("nodesid"), primaryKey)))
}

case class SerializedDirectives(
    @Column("directiveid") directiveId: String,
    @Column("directivename") directiveName: String,
    @Column("directivedescription") directiveDescription: String,
    @Column("priority") priority: Int,
    @Column("techniquename") techniqueName: String,
    @Column("techniquehumanname") techniqueHumanName: String,
    @Column("techniquedescription") techniqueDescription: String,
    @Column("techniqueversion") techniqueVersion: String,
    @Column("starttime") startTime: Timestamp,
    @Column("endtime") endTime: Timestamp
) extends KeyedEntity[Long]  {
  @Column("id")
  val id = 0L
}

object SerializedDirectives {
  def fromDirective(directive : Directive, 
      userPT : ActiveTechnique,
      technique : Technique) : SerializedDirectives = {
    new SerializedDirectives(directive.id.value,
            directive.name,
            directive.shortDescription,
            directive.priority,
            userPT.techniqueName.value,
            technique.name,
            technique.description,
            directive.techniqueVersion.toString,
            new Timestamp(DateTime.now().getMillis), null )
  }
}

object Directives extends Schema {
  val directives = table[SerializedDirectives]("directives")
  
  on(directives)(t => declare( 
      t.id.is(autoIncremented("directivesid"), primaryKey)))
}

case class SerializedRules(
    @Column("ruleid") ruleId: String,
    @Column("serial") serial: Int,
    @Column("name") name: String,
    @Column("shortdescription") shortDescription: String,
    @Column("longdescription") longDescription: String,
    @Column("isenabled") isEnabledStatus: Boolean,
    @Column("starttime") startTime: Timestamp,
    @Column("endtime") endTime: Timestamp
) extends KeyedEntity[Long]  {
  @Column("rulepkeyid")
  val id = 0L
}

case class SerializedRuleGroups(
    @Column("rulepkeyid") crid: Long,// really, the id (not the cr one)
    @Column("groupid") groupId: String
) extends KeyedEntity[CompositeKey2[Long,String]]  {
 
  def id = compositeKey(crid, groupId)
}

case class SerializedRuleDirectives(
    @Column("rulepkeyid") crid: Long,// really, the id (not the cr one)
    @Column("directiveid") directiveId: String
) extends KeyedEntity[CompositeKey2[Long,String]]  {
 
  def id = compositeKey(crid, directiveId)
}

object SerializedRules {
  def fromSerialized(rule : SerializedRules, 
                    crgr : Seq[SerializedRuleGroups],
                    crpi : Seq[SerializedRuleDirectives] ) : Rule = {
    Rule(
        RuleId(rule.ruleId),
        rule.name, 
        rule.serial,
        crgr.headOption.map(x => new GroupTarget(new NodeGroupId(x.groupId))), 
        crpi.map(x => new DirectiveId(x.directiveId)).toSet,
        rule.shortDescription,
        rule.longDescription,
        rule.isEnabledStatus,
        false
    )
    
  }

  def toSerialized(rule : Rule) : SerializedRules = {
    new SerializedRules(rule.id.value,
        rule.serial,
        rule.name,
        rule.shortDescription,
        rule.longDescription, 
        rule.isEnabledStatus,
        new Timestamp(DateTime.now().getMillis), null)
  }
  
}

object Rules extends Schema {
  val rules = table[SerializedRules]("rules")
  val groups = table[SerializedRuleGroups]("rulesgroupjoin")
  val directives = table[SerializedRuleDirectives]("rulesdirectivesjoin")
  
  on(rules)(t => declare(
      t.id.is(autoIncremented("rulesid"), primaryKey)))

}

