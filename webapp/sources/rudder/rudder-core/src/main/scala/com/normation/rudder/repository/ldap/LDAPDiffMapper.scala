/*
*************************************************************************************
* Copyright 2011 Normation SAS
*************************************************************************************
*
* This file is part of Rudder.
*
* Rudder is free software: you can redistribute it and/or modify
* it under the terms of the GNU General Public License as published by
* the Free Software Foundation, either version 3 of the License, or
* (at your option) any later version.
*
* In accordance with the terms of section 7 (7. Additional Terms.) of
* the GNU General Public License version 3, the copyright holders add
* the following Additional permissions:
* Notwithstanding to the terms of section 5 (5. Conveying Modified Source
* Versions) and 6 (6. Conveying Non-Source Forms.) of the GNU General
* Public License version 3, when you create a Related Module, this
* Related Module is not considered as a part of the work and may be
* distributed under the license agreement of your choice.
* A "Related Module" means a set of sources files including their
* documentation that, without modification of the Source Code, enables
* supplementary functions or services in addition to those offered by
* the Software.
*
* Rudder is distributed in the hope that it will be useful,
* but WITHOUT ANY WARRANTY; without even the implied warranty of
* MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
* GNU General Public License for more details.
*
* You should have received a copy of the GNU General Public License
* along with Rudder.  If not, see <http://www.gnu.org/licenses/>.

*
*************************************************************************************
*/

package com.normation.rudder.repository.ldap

import com.normation.cfclerk.domain._
import com.normation.inventory.domain._
import com.normation.inventory.ldap.core.LDAPConstants
import com.normation.inventory.ldap.core.LDAPConstants._
import com.normation.ldap.ldif.LDIFNoopChangeRecord
import com.normation.ldap.sdk._
import com.normation.rudder.api.ApiAccountKind.PublicApi
import com.normation.rudder.api._
import com.normation.rudder.domain.RudderLDAPConstants._
import com.normation.rudder.domain.nodes._
import com.normation.rudder.domain.parameters._
import com.normation.rudder.domain.policies._
import com.normation.rudder.repository.json.DataExtractor
import com.normation.rudder.rule.category.RuleCategoryId
import com.normation.rudder.services.queries._
import com.normation.utils.Control._
import com.unboundid.ldap.sdk.DN
import com.unboundid.ldap.sdk.ModificationType.ADD
import com.unboundid.ldap.sdk.ModificationType.DELETE
import com.unboundid.ldap.sdk.ModificationType.REPLACE
import com.unboundid.ldif.LDIFAddChangeRecord
import com.unboundid.ldif.LDIFChangeRecord
import com.unboundid.ldif.LDIFModifyChangeRecord
import com.unboundid.ldif.LDIFModifyDNChangeRecord
import net.liftweb.common._
import net.liftweb.util.Helpers._

import com.normation.errors._

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
                case "serial" => Full(diff.copy(modSerial = None)) // remove in Rudder 4.3 - kept for compat
                case A_RULE_TARGET =>
                  mod.getModificationType match {
                    case ADD | REPLACE | DELETE => //if there is no values, we have to put "none"
                      (sequence(mod.getValues()) { value =>
                        RuleTarget.unser(value)
                      }).map { targets =>
                        diff.copy(modTarget = Some(SimpleDiff(oldCr.targets, targets.toSet)))
                      }
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
                case A_RULE_CATEGORY =>
                  tryo(diff.copy(modCategory = Some(SimpleDiff(oldCr.categoryId , RuleCategoryId(mod.getAttribute().getValue)))))
                case A_SERIALIZED_TAGS =>
                  for {
                    tags <- DataExtractor.CompleteJson.unserializeTags(mod.getAttribute.getValue)
                  } yield {
                    diff.copy(modTags = Some(SimpleDiff(oldCr.tags.tags, tags.tags)))
                  }
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

  ///// Technique diff /////

  def modChangeRecords2TechniqueDiff(beforeChangeEntry: LDAPEntry, change: LDIFChangeRecord): Box[Option[ModifyTechniqueDiff]] = {
    if(change.getParsedDN == beforeChangeEntry.dn ) {
      change match {
        case modify:LDIFModifyChangeRecord =>
          for {
            oldTechnique <- mapper.entry2ActiveTechnique(beforeChangeEntry)
            diff <- pipeline(modify.getModifications(), ModifyTechniqueDiff(oldTechnique.id, oldTechnique.techniqueName)) { (mod, diff) =>
              mod.getAttributeName() match {
                case A_IS_ENABLED =>
                  tryo(diff.copy(modIsEnabled = Some(SimpleDiff(oldTechnique.isEnabled, mod.getAttribute().getValueAsBoolean))))
                case x => Failure("Unknown diff attribute: " + x)
              }
            }
          } yield {
            Some(diff)
          }

        case noop:LDIFNoopChangeRecord => Full(None)

        case _ => Failure("Bad change record type for requested action 'update technique': %s".format(change))
      }
    } else {
      Failure("The following change record does not belong to Technique entry '%s': %s".format(beforeChangeEntry.dn,change))
    }
  }

  ///// directive diff /////

  def modChangeRecords2DirectiveSaveDiff(
      ptName:TechniqueName
    , variableRootSection:SectionSpec
    , piDn:DN, oldPiEntry:Option[LDAPEntry]
    , change:LDIFChangeRecord
    , oldVariableRootSection:Option[SectionSpec]
  ) : Box[Option[DirectiveSaveDiff]] = {
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
                  val beforeRootSection = oldVariableRootSection.getOrElse(variableRootSection)
                  Full(diff.copy(modParameters = Some(SimpleDiff(
                      SectionVal.directiveValToSectionVal(beforeRootSection,oldPi.parameters),
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
                case A_POLICY_MODE =>
                  for {
                    policyMode <- PolicyMode.parseDefault(mod.getAttribute().getValue)
                  } yield {
                    diff.copy(modPolicyMode = Some(SimpleDiff(oldPi.policyMode,policyMode)))
                  }
                case A_SERIALIZED_TAGS =>
                  for {
                    tags <- DataExtractor.CompleteJson.unserializeTags(mod.getAttribute.getValue)
                  } yield {
                    diff.copy(modTags = Some(SimpleDiff(oldPi.tags.tags, tags.tags)))
                  }
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
         * We have to keep a track of moves beetween category, if not git  repository would not be synchronized with LDAP
         */
        case move:LDIFModifyDNChangeRecord =>
          logger.info("Group DN entry '%s' moved to '%s'".format(beforeChangeEntry.dn,move.getNewDN))
          val diff= mapper.entry2NodeGroup(beforeChangeEntry).map(oldGroup => ModifyNodeGroupDiff(oldGroup.id, oldGroup.name))
          Full(diff)

        case _ => Failure("Bad change record type for requested action 'update node group': %s".format(change))
      }
  }

  ///// Parameters diff /////
  def addChangeRecords2GlobalParameterDiff(
      parameterDN   : DN
    , change        : LDIFChangeRecord
  ) : Box[AddGlobalParameterDiff] = {
    if (change.getParsedDN == parameterDN ) {
      change match {
        case add:LDIFAddChangeRecord =>
          val e = LDAPEntry(add.toAddRequest().toEntry)
          for {
            param <- mapper.entry2Parameter(e)
          } yield AddGlobalParameterDiff(param)
        case _ => Failure("Bad change record type for requested action 'Add Global Parameter': %s".format(change))
      }

    } else {
      Failure("The following change record does not belong to Parameter entry '%s': %s".format(parameterDN,change))
    }
  }

  def modChangeRecords2GlobalParameterDiff(
      parameterName     : ParameterName
    , parameterDn       : DN
    , oldParam          : GlobalParameter
    , change            : LDIFChangeRecord
  ) : Box[Option[ModifyGlobalParameterDiff]] = {
    if(change.getParsedDN == parameterDn ) {
      //if oldParameterEntry is None, we want and addChange, else a modifyChange
      change match {
        case modify:LDIFModifyChangeRecord =>
          for {
            diff <- pipeline(modify.getModifications(), ModifyGlobalParameterDiff(parameterName)) { (mod,diff) =>
              mod.getAttributeName() match {
                case A_PARAMETER_VALUE =>
                  tryo(diff.copy(modValue = Some(SimpleDiff(oldParam.value, mod.getAttribute().getValue))))

                case A_DESCRIPTION =>
                  Full(diff.copy(modDescription = Some(SimpleDiff(oldParam.description, mod.getAttribute().getValue()))))

                case A_PARAMETER_OVERRIDABLE =>
                  tryo(diff.copy(modOverridable = Some(SimpleDiff(oldParam.overridable, mod.getAttribute().getValueAsBoolean))))
                case x => Failure("Unknown diff attribute: " + x)
              }
            }
          } yield {
            Some(diff)
          }

        case noop:LDIFNoopChangeRecord => Full(None)

        case _ =>  Failure("Bad change record type for requested action 'save Parameter': %s".format(change))
      }
    } else {
      Failure("The following change record does not belong to Parameter entry '%s': %s".format(parameterDn,change))
    }
  }

  // API Account diff
  def addChangeRecords2ApiAccountDiff(
      parameterDN   : DN
    , change        : LDIFChangeRecord
  ) : Box[AddApiAccountDiff] = {
    if (change.getParsedDN == parameterDN ) {
      change match {
        case add:LDIFAddChangeRecord =>
          val e = LDAPEntry(add.toAddRequest().toEntry)
          for {
            param <- mapper.entry2ApiAccount(e)
          } yield AddApiAccountDiff(param)
        case _ => Failure(s"Bad change record type for requested action 'Add Api Account': ${change}")
      }
    } else {
      Failure(s"The following change record does not belong to Parameter entry '${parameterDN}': ${change}")
    }
  }

  /**
   * Map a list of com.unboundid.ldif.LDIFChangeRecord into a
   * ApiAccountDiff.
   */
  def modChangeRecords2ApiAccountDiff(
      beforeChangeEntry : LDAPEntry
    , change            : LDIFChangeRecord
  ) : PureResult[Option[ModifyApiAccountDiff]] = {
    if(change.getParsedDN == beforeChangeEntry.dn ) {
      change match {
        case modify:LDIFModifyChangeRecord =>
          for {
            oldAccount <- mapper.entry2ApiAccount(beforeChangeEntry)
            diff       <- pipeline(modify.getModifications(), ModifyApiAccountDiff(oldAccount.id)) { (mod, diff) =>
              mod.getAttributeName() match {
                case A_NAME =>
                  tryo(diff.copy(modName = Some(SimpleDiff(oldAccount.name.value, mod.getAttribute().getValue()))))
                case A_API_TOKEN =>
                  Full(diff.copy(modToken = Some(SimpleDiff(oldAccount.token.value, mod.getAttribute().getValue()))))
                case A_DESCRIPTION =>
                  Full(diff.copy(modDescription = Some(SimpleDiff(oldAccount.description, mod.getAttribute().getValue()))))
                case A_IS_ENABLED =>
                  tryo(diff.copy(modIsEnabled = Some(SimpleDiff(oldAccount.isEnabled, mod.getAttribute().getValueAsBoolean))))
                case A_API_TOKEN_CREATION_DATETIME =>
                  val diffDate = GeneralizedTime.parse(mod.getAttribute().getValue()).map(_.dateTime)
                  tryo(diff.copy(modTokenGenerationDate = Some(SimpleDiff(oldAccount.tokenGenerationDate, diffDate.get))))
                case A_API_EXPIRATION_DATETIME =>
                  val expirationDate = oldAccount.kind match {
                    case PublicApi(_,date) => date
                    case _ => None
                  }
                  val diffDate = tryo(mod.getAttribute().getValue() match {
                    case "None" => None
                    case v => GeneralizedTime.parse(v).map(_.dateTime)
                  }).getOrElse(None)
                  tryo(diff.copy(modExpirationDate = Some(SimpleDiff(expirationDate, diffDate))))
                case A_API_AUTHZ_KIND =>
                  val oldAuthType = oldAccount.kind match {
                    case PublicApi(auth, _) =>
                      auth.kind.name
                    case kind =>
                      kind.kind.name
                  }
                  Full(diff.copy(modAccountKind = Some(SimpleDiff(oldAuthType, mod.getAttribute().getValue()))))
                case A_API_ACL =>
                  val oldAcl = oldAccount.kind match {
                    case PublicApi(ApiAuthorization.ACL(acl), _) =>
                      acl
                    case kind =>
                      Nil
                  }

                  for {
                    acl <- mapper.unserApiAcl(mod.getAttribute().getValue) match {
                             case Left(error) => Failure(error)
                             case Right(acl)  => Full(acl)
                           }
                  } yield {
                    diff.copy(modAccountAcl = Some(SimpleDiff(oldAcl, acl)))
                  }

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
}
