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

package com.normation.rudder.repository.ldap

import com.unboundid.ldap.sdk.DN
import com.normation.utils.Utils
import com.normation.utils.Control._
import com.normation.inventory.domain._
import com.normation.inventory.ldap.core.InventoryDit
import com.normation.inventory.ldap.core.LDAPConstants
import LDAPConstants._
import com.normation.ldap.sdk._
import com.normation.cfclerk.domain._
import com.normation.cfclerk.services._
import com.normation.rudder.domain.Constants._
import com.normation.rudder.domain.RudderLDAPConstants._
import com.normation.rudder.domain.{NodeDit,RudderDit}
import com.normation.rudder.domain.servers._
import com.normation.rudder.domain.nodes.Node
import com.normation.rudder.domain.queries._
import com.normation.rudder.domain.policies._
import com.normation.rudder.domain.nodes._
import com.normation.rudder.domain.eventlog.DirectiveEventLog
import com.normation.rudder.services.queries._
import org.joda.time.Duration
import org.joda.time.DateTime
import net.liftweb.common._
import Box._
import net.liftweb.util.Helpers._
import scala.xml.{Text,NodeSeq}
import com.normation.exceptions.{BusinessException,TechnicalException}
import com.normation.rudder.services.policies.VariableBuilderService
import net.liftweb.json.JsonAST.JObject
import com.unboundid.ldif.LDIFChangeRecord
import com.unboundid.ldif.LDIFDeleteChangeRecord
import com.unboundid.ldif.LDIFAddChangeRecord
import com.unboundid.ldif.LDIFModifyChangeRecord
import com.unboundid.ldif.LDIFModifyDNChangeRecord
import com.normation.ldap.ldif.LDIFNoopChangeRecord
import com.unboundid.ldap.sdk.ModificationType.{ADD, DELETE, REPLACE}

class LDAPDiffMapper(
    mapper         : LDAPEntityMapper 
  , cmdbQueryParser: CmdbQueryParser
) extends Loggable {
  ///////////////////////////////////////////////////////////////////////////////////////
  
  ///// Diff mapping /////
  
  ///////////////////////////////////////////////////////////////////////////////////////
  
  
  ///// rule diff /////
  
  def addChangeRecords2RuleDiff(crDn:DN, change:LDIFChangeRecord) : Box[AddRuleDiff] = {
    if(change.getParsedDN == crDn ) {
      change match {
        case add:LDIFAddChangeRecord => 
          val e = LDAPEntry(add.toAddRequest().toEntry)
          for {
            rule <- mapper.entry2Rule(e)
          } yield AddRuleDiff(rule)
        case _ => Failure("Bad change record type for requested action 'add rule': %s".format(change))
      }
        
    } else {
      Failure("The following change record does not belong to Rule entry '%s': %s".format(crDn,change))
    }
  }
  
  
  /**
   * Map a list of com.unboundid.ldif.LDIFChangeRecord into a
   * RuleDiff.
   * 
   * If several changes are applied on the same monovalued attribute, 
   * it's an error. 
   */
  def modChangeRecords2RuleDiff(beforeChangeEntry:LDAPEntry, change:LDIFChangeRecord) : Box[Option[ModifyRuleDiff]] = {
    if(change.getParsedDN == beforeChangeEntry.dn ) {
      change match {
        case modify:LDIFModifyChangeRecord => 
          for {
            oldCr <- mapper.entry2Rule(beforeChangeEntry)
            diff <- pipeline(modify.getModifications(), ModifyRuleDiff(oldCr.id, oldCr.name)) { (mod, diff) =>
              mod.getAttributeName() match {
                case A_SERIAL => 
                  tryo(diff.copy(modSerial = Some(SimpleDiff(oldCr.serial, mod.getAttribute().getValueAsInteger()))))
                case A_RULE_TARGET => 
                  mod.getModificationType match {
                    case ADD | REPLACE if(mod.getAttribute.getValues.size > 0) => //if there is no values, we have to put "none" 
                      for {
                        target <- mapper.entry2OptTarget(Some(mod.getAttribute().getValue))
                      } yield {
                        diff.copy(modTarget = Some(SimpleDiff(oldCr.target,target)))
                      }
                    case ADD | REPLACE | DELETE => //case for add/replace without values
                      Full(diff.copy(modTarget = Some(SimpleDiff(oldCr.target,None))))
                    case _ => Failure("Bad modification type for modification '%s' in change record: '%s'".format(mod, change))
                  }
                case A_DIRECTIVE_UUID => 
                  Full(diff.copy(modDirectiveIds = Some(SimpleDiff(oldCr.directiveIds, mod.getValues.map( DirectiveId(_) ).toSet))))
                case A_NAME =>
                  Full(diff.copy(modName = Some(SimpleDiff(oldCr.name, mod.getAttribute().getValue))))
                case A_DESCRIPTION => 
                  Full(diff.copy(modShortDescription = Some(SimpleDiff(oldCr.shortDescription, mod.getAttribute().getValue()))))
                case A_LONG_DESCRIPTION => 
                  Full(diff.copy(modLongDescription = Some(SimpleDiff(oldCr.longDescription, mod.getAttribute().getValue()))))
                case A_IS_ENABLED => 
                  tryo(diff.copy(modIsActivatedStatus = Some(SimpleDiff(oldCr.isEnabledStatus, mod.getAttribute().getValueAsBoolean))))
                case A_IS_SYSTEM => 
                  tryo(diff.copy(modIsSystem = Some(SimpleDiff(oldCr.isSystem,mod.getAttribute().getValueAsBoolean))))
                case x => Failure("Unknown diff attribute: " + x)
              }
            }
          } yield {
            Some(diff)
          }
        
        case noop:LDIFNoopChangeRecord => Full(None)
          
        case _ => Failure("Bad change record type for requested action 'update rule': %s".format(change))
      }
    } else {
      Failure("The following change record does not belong to Rule entry '%s': %s".format(beforeChangeEntry.dn,change))
    }
  }

  
  ///// directive diff /////

  def modChangeRecords2DirectiveSaveDiff(ptName:TechniqueName, variableRootSection:SectionSpec, piDn:DN, oldPiEntry:Option[LDAPEntry], change:LDIFChangeRecord) : Box[Option[DirectiveSaveDiff]] = {
    if(change.getParsedDN == piDn ) {
      //if oldPI is None, we want and addChange, else a modifyChange
      (change, oldPiEntry) match {
        case (add:LDIFAddChangeRecord, None) => 
          val e = LDAPEntry(add.toAddRequest().toEntry)
            for {
              directive <- mapper.entry2Directive(e)
            } yield Some(AddDirectiveDiff(ptName, directive))

        case (modify:LDIFModifyChangeRecord, Some(beforeChangeEntry)) => 
          for {
            oldPi <- mapper.entry2Directive(beforeChangeEntry)
            diff <- pipeline(modify.getModifications(), ModifyDirectiveDiff(ptName, oldPi.id, oldPi.name)) { (mod,diff) =>
              mod.getAttributeName() match {
                case A_TECHNIQUE_VERSION =>
                  tryo(diff.copy(modTechniqueVersion = Some(SimpleDiff(oldPi.techniqueVersion, TechniqueVersion(mod.getAttribute().getValue)))))
                case A_DIRECTIVE_VARIABLES =>
                  Full(diff.copy(modParameters = Some(SimpleDiff(
                      SectionVal.directiveValToSectionVal(variableRootSection,oldPi.parameters), 
                      SectionVal.directiveValToSectionVal(variableRootSection,parsePolicyVariables(mod.getAttribute().getValues)))
                  )))
                case A_NAME =>
                  Full(diff.copy(modName = Some(SimpleDiff(oldPi.name, mod.getAttribute().getValue))))
                case A_DESCRIPTION => 
                  Full(diff.copy(modShortDescription = Some(SimpleDiff(oldPi.shortDescription, mod.getAttribute().getValue()))))
                case A_LONG_DESCRIPTION => 
                  Full(diff.copy(modLongDescription = Some(SimpleDiff(oldPi.longDescription, mod.getAttribute().getValue()))))
                case A_PRIORITY => 
                  tryo(diff.copy(modPriority = Some(SimpleDiff(oldPi.priority, mod.getAttribute().getValueAsInteger))))
                case A_IS_ENABLED => 
                  tryo(diff.copy(modIsActivated = Some(SimpleDiff(oldPi.isEnabled, mod.getAttribute().getValueAsBoolean))))
                case A_IS_SYSTEM => 
                  tryo(diff.copy(modIsSystem = Some(SimpleDiff(oldPi.isSystem,mod.getAttribute().getValueAsBoolean))))
                case x => Failure("Unknown diff attribute: " + x)
              }
            }
          } yield {
            Some(diff)
          }
          
        case (noop:LDIFNoopChangeRecord, _) => Full(None) 
          
        case _ =>  Failure("Bad change record type for requested action 'save directive': %s".format(change))
      }
    } else {
      Failure("The following change record does not belong to directive entry '%s': %s".format(piDn,change))
    }
  }  
  
  
  ///// Node group diff /////

  def addChangeRecords2NodeGroupDiff(groupDN:DN, change:LDIFChangeRecord) : Box[AddNodeGroupDiff] = {
    if(change.getParsedDN == groupDN ) {
      change match {
        case add:LDIFAddChangeRecord => 
          val e = LDAPEntry(add.toAddRequest().toEntry)
          for {
            group<- mapper.entry2NodeGroup(e)
          } yield AddNodeGroupDiff(group)
        case _ => Failure("Bad change record type for requested action 'add node group': %s".format(change))
      }
        
    } else {
      Failure("The following change record does not belong to Node Group entry '%s': %s".format(groupDN,change))
    }
  }
  

  def modChangeRecords2NodeGroupDiff(beforeChangeEntry:LDAPEntry, change:LDIFChangeRecord) : Box[Option[ModifyNodeGroupDiff]] = {
      change match {
        case modify:LDIFModifyChangeRecord => 
          for {
            oldGroup <- mapper.entry2NodeGroup(beforeChangeEntry)
            diff <- pipeline(modify.getModifications(), ModifyNodeGroupDiff(oldGroup.id, oldGroup.name)) { (mod, diff) =>
              mod.getAttributeName() match {
                case A_NAME =>
                  Full(diff.copy(modName = Some(SimpleDiff(oldGroup.name, mod.getAttribute().getValue))))
                case A_NODE_UUID =>
                  Full(diff.copy(modNodeList = Some(SimpleDiff(oldGroup.serverList, mod.getValues.map(x => NodeId(x) ).toSet ) ) ) )
                case A_QUERY_NODE_GROUP =>
                  mod.getModificationType match {
                    case ADD | REPLACE if(mod.getAttribute.getValues.size > 0) => //if there is no values, we have to put "none" 
                      for {
                        query <- cmdbQueryParser(mod.getAttribute().getValue)
                      } yield {
                        diff.copy(modQuery = Some(SimpleDiff(oldGroup.query, Some(query))))
                      }
                    case ADD | REPLACE | DELETE => //case for add/replace without values
                      Full(diff.copy(modQuery = Some(SimpleDiff(oldGroup.query, None))))
                    case _ => Failure("Bad operation type for attribute '%s' in change record '%s'".format(mod, change))
                  }
                case A_DESCRIPTION => 
                  Full(diff.copy(modDescription = Some(SimpleDiff(oldGroup.description, mod.getAttribute().getValue))))
                case A_IS_DYNAMIC => 
                  tryo(diff.copy(modIsDynamic = Some(SimpleDiff(oldGroup.isDynamic, mod.getAttribute().getValueAsBoolean))))
                case A_IS_ENABLED => 
                  tryo(diff.copy(modIsActivated = Some(SimpleDiff(oldGroup.isEnabled, mod.getAttribute().getValueAsBoolean))))
                case A_IS_SYSTEM => 
                  tryo(diff.copy(modIsSystem = Some(SimpleDiff(oldGroup.isSystem,mod.getAttribute().getValueAsBoolean))))
                case x => Failure("Unknown diff attribute: " + x)
              }
            }
          } yield {
            Some(diff)
          }
          
        case noop:LDIFNoopChangeRecord => Full(None)
        
        /*
         * We don't keep trace of group modification, just log it
         */
        case move:LDIFModifyDNChangeRecord => 
          logger.info("Group DN entry '%s' moved to '%s'".format(beforeChangeEntry.dn,move.getNewDN))
          Full(None)
          
        case _ => Failure("Bad change record type for requested action 'update node group': %s".format(change))
      }
  }

}