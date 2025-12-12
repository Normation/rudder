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

import cats.implicits.*
import com.normation.cfclerk.domain.*
import com.normation.errors.*
import com.normation.inventory.domain.*
import com.normation.inventory.ldap.core.InventoryMappingRudderError as Err
import com.normation.inventory.ldap.core.LDAPConstants.*
import com.normation.ldap.ldif.LDIFNoopChangeRecord
import com.normation.ldap.sdk.*
import com.normation.rudder.api.*
import com.normation.rudder.api.ApiAccountKind.PublicApi
import com.normation.rudder.domain.RudderLDAPConstants.*
import com.normation.rudder.domain.logger.ApplicationLogger
import com.normation.rudder.domain.nodes.*
import com.normation.rudder.domain.policies.*
import com.normation.rudder.domain.properties.AddGlobalParameterDiff
import com.normation.rudder.domain.properties.GenericProperty
import com.normation.rudder.domain.properties.GlobalParameter
import com.normation.rudder.domain.properties.GroupProperty
import com.normation.rudder.domain.properties.InheritMode
import com.normation.rudder.domain.properties.ModifyGlobalParameterDiff
import com.normation.rudder.domain.properties.PropertyProvider
import com.normation.rudder.domain.properties.Visibility
import com.normation.rudder.rule.category.RuleCategoryId
import com.normation.rudder.services.queries.*
import com.normation.rudder.tenants.SecurityTag
import com.unboundid.ldap.sdk.DN
import com.unboundid.ldap.sdk.Modification
import com.unboundid.ldap.sdk.ModificationType.ADD
import com.unboundid.ldap.sdk.ModificationType.DELETE
import com.unboundid.ldap.sdk.ModificationType.REPLACE
import com.unboundid.ldif.LDIFAddChangeRecord
import com.unboundid.ldif.LDIFChangeRecord
import com.unboundid.ldif.LDIFModifyChangeRecord
import com.unboundid.ldif.LDIFModifyDNChangeRecord
import net.liftweb.common.*
import scala.util.control.NonFatal
import zio.json.*

class LDAPDiffMapper(
    mapper:          LDAPEntityMapper,
    cmdbQueryParser: CmdbQueryParser & RawStringQueryParser
) extends Loggable {

  // utility method for safe non null
  private def nonNull[A, B](a: PureResult[A], b: B)(f: (A, B) => A): PureResult[A] = {
    b match {
      case null  => a
      case value => a.map(x => f(x, b))
    }
  }

  private def updateSecurityTag[A](a: PureResult[A], b: String)(f: (A, Option[SecurityTag]) => A): PureResult[A] = {
    b match {
      case null  => a.map(x => f(x, None))
      case value => value.fromJson[SecurityTag].left.map(Inconsistency(_)).flatMap(tag => a.map(x => f(x, Some(tag))))
    }
  }

  ///////////////////////////////////////////////////////////////////////////////////////

  ///// Diff mapping /////

  ///////////////////////////////////////////////////////////////////////////////////////

  ///// rule diff /////

  def addChangeRecords2RuleDiff(crDn: DN, change: LDIFChangeRecord): PureResult[AddRuleDiff] = {
    if (change.getParsedDN == crDn) {
      change match {
        case add: LDIFAddChangeRecord =>
          val e = LDAPEntry(add.toAddRequest().toEntry)
          for {
            rule <- mapper.entry2Rule(e)
          } yield AddRuleDiff(rule)
        case _ => Left(Unexpected(s"Bad change record type for requested action 'add rule': ${change}"))
      }

    } else {
      Left(Unexpected(s"The following change record does not belong to Rule entry '${crDn}': ${change}"))
    }
  }

  /**
   * Map a list of com.unboundid.ldif.LDIFChangeRecord into a
   * RuleDiff.
   *
   * If several changes are applied on the same monovalued attribute,
   * it's an error.
   */
  def modChangeRecords2RuleDiff(beforeChangeEntry: LDAPEntry, change: LDIFChangeRecord): PureResult[Option[ModifyRuleDiff]] = {
    if (change.getParsedDN == beforeChangeEntry.dn) {
      change match {
        case modify: LDIFModifyChangeRecord =>
          for {
            oldCr <- mapper.entry2Rule(beforeChangeEntry)
            diff  <- modify.getModifications().foldLeft(ModifyRuleDiff(oldCr.id, oldCr.name).asRight[RudderError]) { (diff, mod) =>
                       mod.getAttributeName() match {
                         case "serial"           => diff.map(_.copy(modSerial = None)) // remove in Rudder 4.3 - kept for compat
                         case A_RULE_TARGET      =>
                           mod.getModificationType match {
                             case ADD | REPLACE | DELETE => // if there is no values, we have to put "none"
                               mod.getValues().toList.traverse(value => RuleTarget.unser(value)) match {
                                 case Left(_)        => diff
                                 case Right(targets) =>
                                   diff.map(_.copy(modTarget = Some(SimpleDiff(oldCr.targets, targets.toSet))))
                               }
                             case x                      =>
                               Left(
                                 Err
                                   .UnexpectedObject(s"Bad change record type for requested action 'update rule': ${mod.toString}")
                               )
                           }
                         case A_DIRECTIVE_UUID   =>
                           diff.map(
                             _.copy(modDirectiveIds = {
                               Some(
                                 SimpleDiff(
                                   oldCr.directiveIds,
                                   mod.getValues.map(x => DirectiveId.parse(x).getOrElse(DirectiveId(DirectiveUid("")))).toSet
                                 )
                               )
                             })
                           )
                         case A_NAME             =>
                           diff.map(_.copy(modName = Some(SimpleDiff(oldCr.name, mod.getOptValueDefault("")))))
                         case A_DESCRIPTION      =>
                           diff.map(
                             _.copy(modShortDescription = Some(SimpleDiff(oldCr.shortDescription, mod.getOptValueDefault(""))))
                           )
                         case A_LONG_DESCRIPTION =>
                           diff
                             .map(_.copy(modLongDescription = Some(SimpleDiff(oldCr.longDescription, mod.getOptValueDefault("")))))
                         case A_IS_ENABLED       =>
                           nonNull(diff, mod.getAttribute().getValueAsBoolean) { (d, value) =>
                             d.copy(modIsActivatedStatus = Some(SimpleDiff(oldCr.isEnabledStatus, value)))
                           }
                         case A_IS_SYSTEM        =>
                           nonNull(diff, mod.getAttribute().getValueAsBoolean) { (d, value) =>
                             d.copy(modIsSystem = Some(SimpleDiff(oldCr.isSystem, value)))
                           }
                         case A_RULE_CATEGORY    =>
                           nonNull(diff, mod.getOptValueDefault("")) { (d, value) =>
                             d.copy(modCategory = Some(SimpleDiff(oldCr.categoryId, RuleCategoryId(value))))
                           }
                         case A_SERIALIZED_TAGS  =>
                           for {
                             d    <- diff
                             tags <- Tags.parse(mod.getOptValue())
                           } yield {
                             d.copy(modTags = Some(SimpleDiff(oldCr.tags.tags, tags.tags)))
                           }
                         case A_SECURITY_TAG     =>
                           updateSecurityTag(diff, mod.getAttribute.getValue) { (d, t) =>
                             d.copy(modSecurityTag = Some(SimpleDiff(oldCr.security, t)))
                           }
                         case x                  => Left(Err.UnexpectedObject("Unknown diff attribute: " + x))
                       }
                     }
          } yield {
            Some(diff)
          }

        case noop: LDIFNoopChangeRecord => Right(None)

        case _ => Left(Err.UnexpectedObject(s"Bad change record type for requested action 'update rule': ${change}"))
      }
    } else {
      Left(
        Err.UnexpectedObject(s"The following change record does not belong to Rule entry '${beforeChangeEntry.dn}': ${change}")
      )
    }
  }

  ///// Technique diff /////

  def modChangeRecords2TechniqueDiff(
      beforeChangeEntry: LDAPEntry,
      change:            LDIFChangeRecord
  ): PureResult[Option[ModifyTechniqueDiff]] = {
    if (change.getParsedDN == beforeChangeEntry.dn) {
      change match {
        case modify: LDIFModifyChangeRecord =>
          for {
            oldTechnique <- mapper.entry2ActiveTechnique(beforeChangeEntry)
            diff         <-
              modify
                .getModifications()
                .foldLeft(ModifyTechniqueDiff(oldTechnique.id, oldTechnique.techniqueName).asRight[RudderError]) { (diff, mod) =>
                  mod.getAttributeName() match {
                    case A_IS_ENABLED   =>
                      mod.getAttribute().getValueAsBoolean match {
                        case null  => diff
                        case value => diff.map(_.copy(modIsEnabled = Some(SimpleDiff(oldTechnique.isEnabled, value))))
                      }
                    case A_SECURITY_TAG =>
                      updateSecurityTag(diff, mod.getAttribute.getValue) { (d, t) =>
                        d.copy(modSecurityTag = Some(SimpleDiff(oldTechnique.security, t)))
                      }
                    case x              => Left(Err.UnexpectedObject("Unknown diff attribute: " + x))
                  }
                }
          } yield {
            Some(diff)
          }

        case noop: LDIFNoopChangeRecord => Right(None)

        case _ => Left(Err.UnexpectedObject("Bad change record type for requested action 'update technique': %s".format(change)))
      }
    } else {
      Left(
        Err.UnexpectedObject(
          "The following change record does not belong to Technique entry '%s': %s".format(beforeChangeEntry.dn, change)
        )
      )
    }
  }

  implicit class LDAPModification(mod: Modification) {
    def getOptValue():                       Option[String] = {
      mod.getAttribute.getValue match {
        case null => None
        case s    => Some(s)
      }
    }
    def getOptValueDefault(default: String): String         =
      getOptValue().getOrElse(default)
  }
  ///// directive diff /////

  def modChangeRecords2DirectiveSaveDiff(
      ptName:                 TechniqueName,
      variableRootSection:    SectionSpec,
      piDn:                   DN,
      oldPiEntry:             Option[LDAPEntry],
      change:                 LDIFChangeRecord,
      oldVariableRootSection: Option[SectionSpec]
  ): PureResult[Option[DirectiveSaveDiff]] = {
    if (change.getParsedDN == piDn) {
      // if oldPI is None, we want and addChange, else a modifyChange
      (change, oldPiEntry) match {
        case (add: LDIFAddChangeRecord, None) =>
          val e = LDAPEntry(add.toAddRequest().toEntry)
          for {
            directive <- mapper.entry2Directive(e)
          } yield Some(AddDirectiveDiff(ptName, directive))

        case (modify: LDIFModifyChangeRecord, Some(beforeChangeEntry)) =>
          for {
            oldPi <- mapper.entry2Directive(beforeChangeEntry)
            diff  <- modify
                       .getModifications()
                       .foldLeft(ModifyDirectiveDiff.emptyMod(ptName, oldPi.id, oldPi.name).asRight[RudderError]) { (diff, mod) =>
                         mod.getAttributeName() match {
                           case A_TECHNIQUE_VERSION   =>
                             mod.getAttribute().getValue match {
                               case null    => diff
                               case version =>
                                 for {
                                   d <- diff
                                   v <- TechniqueVersion.parse(version).leftMap(Unexpected.apply)
                                 } yield {
                                   d.copy(modTechniqueVersion = Some(SimpleDiff(oldPi.techniqueVersion, v)))
                                 }
                             }
                           case A_DIRECTIVE_VARIABLES =>
                             val beforeRootSection = oldVariableRootSection.getOrElse(variableRootSection)
                             diff.map(
                               _.copy(modParameters = {
                                 Some(
                                   SimpleDiff(
                                     SectionVal.directiveValToSectionVal(beforeRootSection, oldPi.parameters),
                                     SectionVal.directiveValToSectionVal(
                                       variableRootSection,
                                       parsePolicyVariables(mod.getAttribute().getValues.toSeq)
                                     )
                                   )
                                 )
                               })
                             )
                           case A_NAME                =>
                             nonNull(diff, mod.getOptValueDefault("")) { (d, value) =>
                               d.copy(modName = Some(SimpleDiff(oldPi.name, value)))
                             }
                           case A_DESCRIPTION         =>
                             nonNull(diff, mod.getOptValueDefault("")) { (d, value) =>
                               d.copy(modShortDescription = Some(SimpleDiff(oldPi.shortDescription, value)))
                             }
                           case A_LONG_DESCRIPTION    =>
                             nonNull(diff, mod.getOptValueDefault("")) { (d, value) =>
                               d.copy(modLongDescription = Some(SimpleDiff(oldPi.longDescription, value)))
                             }
                           case A_PRIORITY            =>
                             nonNull(diff, mod.getAttribute.getValueAsInteger()) { (d, value) =>
                               d.copy(modPriority = Some(SimpleDiff(oldPi.priority, value)))
                             }
                           case A_IS_ENABLED          =>
                             nonNull(diff, mod.getAttribute.getValueAsBoolean()) { (d, value) =>
                               d.copy(modIsActivated = Some(SimpleDiff(oldPi.isEnabled, value)))
                             }
                           case A_IS_SYSTEM           =>
                             nonNull(diff, mod.getAttribute.getValueAsBoolean()) { (d, value) =>
                               d.copy(modIsSystem = Some(SimpleDiff(oldPi.isSystem, value)))
                             }
                           case A_POLICY_MODE         =>
                             for {
                               d          <- diff
                               policyMode <- PolicyMode.parseDefault(mod.getOptValueDefault(""))
                             } yield {
                               d.copy(modPolicyMode = Some(SimpleDiff(oldPi.policyMode, policyMode)))
                             }
                           case A_SERIALIZED_TAGS     =>
                             for {
                               d    <- diff
                               tags <- Tags.parse(mod.getOptValue())
                             } yield {
                               d.copy(modTags = Some(SimpleDiff(oldPi.tags, tags)))
                             }
                           case A_SECURITY_TAG        =>
                             updateSecurityTag(diff, mod.getAttribute.getValue) { (d, t) =>
                               d.copy(modSecurityTag = Some(SimpleDiff(oldPi.security, t)))
                             }
                           case x                     => Left(Err.UnexpectedObject("Unknown diff attribute: " + x))
                         }
                       }
          } yield {
            Some(diff)
          }
        case (noop: LDIFNoopChangeRecord, _)                           => Right(None)

        case _ => Left(Err.UnexpectedObject(s"Bad change record type for requested action 'save directive': ${change}"))
      }
    } else {
      Left(Err.UnexpectedObject(s"The following change record does not belong to directive entry '${piDn}': ${change}"))
    }
  }

  ///// Node group diff /////

  def addChangeRecords2NodeGroupDiff(groupDN: DN, change: LDIFChangeRecord): PureResult[AddNodeGroupDiff] = {
    if (change.getParsedDN == groupDN) {
      change match {
        case add: LDIFAddChangeRecord =>
          val e = LDAPEntry(add.toAddRequest().toEntry)
          for {
            group <- mapper.entry2NodeGroup(e)
          } yield AddNodeGroupDiff(group)
        case _ => Left(Err.UnexpectedObject(s"Bad change record type for requested action 'add node group': ${change}"))
      }

    } else {
      Left(Err.UnexpectedObject(s"The following change record does not belong to Node Group entry '${groupDN}': ${change}"))
    }
  }

  def modChangeRecords2NodeGroupDiff(
      beforeChangeEntry: LDAPEntry,
      change:            LDIFChangeRecord
  ): PureResult[Option[ModifyNodeGroupDiff]] = {
    change match {
      case modify: LDIFModifyChangeRecord =>
        for {
          oldGroup <- mapper.entry2NodeGroup(beforeChangeEntry)
          diff     <- modify.getModifications().foldLeft(ModifyNodeGroupDiff(oldGroup.id, oldGroup.name).asRight[RudderError]) {
                        (diff, mod) =>
                          mod.getAttributeName() match {
                            case A_NAME             =>
                              nonNull(diff, mod.getOptValueDefault("")) { (d, value) =>
                                d.copy(modName = Some(SimpleDiff(oldGroup.name, mod.getOptValueDefault(""))))
                              }
                            case A_NODE_UUID        =>
                              diff.map(
                                _.copy(modNodeList = Some(SimpleDiff(oldGroup.serverList, mod.getValues.map(x => NodeId(x)).toSet)))
                              )
                            case A_QUERY_NODE_GROUP =>
                              mod.getModificationType match {
                                case ADD | REPLACE
                                    if (mod.getAttribute.getValues.nonEmpty) => // if there is no values, we have to put "none"
                                  for {
                                    d     <- diff
                                    query <- cmdbQueryParser(mod.getOptValueDefault("")).toPureResult
                                  } yield {
                                    d.copy(modQuery = Some(SimpleDiff(oldGroup.query, Some(query))))
                                  }
                                case ADD | REPLACE | DELETE => // case for add/replace without values
                                  diff.map(_.copy(modQuery = Some(SimpleDiff(oldGroup.query, None))))
                                case _                      =>
                                  Left(
                                    Err.UnexpectedObject(
                                      "Bad operation type for attribute '%s' in change record '%s'".format(mod, change)
                                    )
                                  )
                              }
                            case A_DESCRIPTION      =>
                              diff.map(_.copy(modDescription = Some(SimpleDiff(oldGroup.description, mod.getOptValueDefault("")))))
                            case A_IS_DYNAMIC       =>
                              nonNull(diff, mod.getAttribute().getValueAsBoolean) { (d, value) =>
                                d.copy(modIsDynamic = Some(SimpleDiff(oldGroup.isDynamic, value)))
                              }
                            case A_IS_ENABLED       =>
                              nonNull(diff, mod.getAttribute().getValueAsBoolean) { (d, value) =>
                                d.copy(modIsActivated = Some(SimpleDiff(oldGroup.isEnabled, value)))
                              }
                            case A_IS_SYSTEM        =>
                              nonNull(diff, mod.getAttribute().getValueAsBoolean) { (d, value) =>
                                d.copy(modIsSystem = Some(SimpleDiff(oldGroup.isSystem, value)))
                              }
                            case A_JSON_PROPERTY    =>
                              for {
                                d    <- diff
                                // ignore invalid properties, don't make group unusable
                                props = mod
                                          .getAttribute()
                                          .getValues
                                          .toList
                                          .flatMap(s => {
                                            GroupProperty.unserializeLdapGroupProperty(s) match {
                                              case Right(p)  => Some(p)
                                              case Left(err) =>
                                                ApplicationLogger
                                                  .error(s"Group has an invalid property that will be ignore: ${err.fullMsg}")
                                                None
                                            }
                                          })
                              } yield {
                                d.copy(modProperties = Some(SimpleDiff(oldGroup.properties, props)))
                              }
                            case A_SECURITY_TAG     =>
                              updateSecurityTag(diff, mod.getAttribute.getValue) { (d, t) =>
                                d.copy(modSecurityTag = Some(SimpleDiff(oldGroup.security, t)))
                              }
                            case x                  => Left(Err.UnexpectedObject("Unknown diff attribute: " + x))
                          }
                      }
        } yield {
          Some(diff)
        }

      case noop: LDIFNoopChangeRecord => Right(None)

      /*
       * We have to keep a track of moves beetween category, if not git repository would not be synchronized with LDAP
       */
      case move: LDIFModifyDNChangeRecord =>
        logger.info("Group DN entry '%s' moved to '%s'".format(beforeChangeEntry.dn, move.getNewDN))
        try {
          val oldCat = mapper.dn2NodeGroupCategoryId(beforeChangeEntry.dn.getParent)
          val newCat = mapper.dn2NodeGroupCategoryId(move.getNewDN.getParent)
          mapper
            .entry2NodeGroup(beforeChangeEntry)
            .map(oldGroup =>
              Some(ModifyNodeGroupDiff(oldGroup.id, oldGroup.name, modCategory = Some(SimpleDiff(oldCat, newCat))))
            )
        } catch {
          case NonFatal(ex) => Left(Err.UnexpectedObject(s"Error when trying to parse a node group move entry: ${ex.getMessage}"))
        }
      case _ => Left(Err.UnexpectedObject("Bad change record type for requested action 'update node group': %s".format(change)))
    }
  }

  ///// Parameters diff /////
  def addChangeRecords2GlobalParameterDiff(
      parameterDN: DN,
      change:      LDIFChangeRecord
  ): PureResult[AddGlobalParameterDiff] = {
    if (change.getParsedDN == parameterDN) {
      change match {
        case add: LDIFAddChangeRecord =>
          val e = LDAPEntry(add.toAddRequest().toEntry)
          for {
            param <- mapper.entry2Parameter(e)
          } yield AddGlobalParameterDiff(param)
        case _ =>
          Left(Err.UnexpectedObject("Bad change record type for requested action 'Add Global Parameter': %s".format(change)))
      }

    } else {
      Left(
        Err.UnexpectedObject(
          "The following change record does not belong to Parameter entry '%s': %s".format(parameterDN, change)
        )
      )
    }
  }

  def modChangeRecords2GlobalParameterDiff(
      parameterName: String,
      parameterDn:   DN,
      oldParam:      GlobalParameter,
      change:        LDIFChangeRecord
  ): PureResult[Option[ModifyGlobalParameterDiff]] = {
    if (change.getParsedDN == parameterDn) {
      // if oldParameterEntry is None, we want and addChange, else a modifyChange
      change match {
        case modify: LDIFModifyChangeRecord =>
          for {
            diff <-
              modify.getModifications().foldLeft(ModifyGlobalParameterDiff(parameterName).asRight[RudderError]) { (diff, mod) =>
                mod.getAttributeName() match {
                  case A_PARAMETER_VALUE   =>
                    for {
                      d <- diff
                      v <- GenericProperty.parseValue(mod.getOptValueDefault(""))
                    } yield {
                      d.copy(modValue = Some(SimpleDiff(oldParam.value, v)))
                    }
                  case A_DESCRIPTION       =>
                    nonNull(diff, mod.getOptValueDefault("")) { (d, value) =>
                      d.copy(modDescription = Some(SimpleDiff(oldParam.description, value)))
                    }
                  case A_PROPERTY_PROVIDER =>
                    nonNull(diff, mod.getAttribute().getValue) { (d, value) =>
                      d.copy(modProvider = Some(SimpleDiff(oldParam.provider, Some(PropertyProvider(value)))))
                    }
                  case A_INHERIT_MODE      =>
                    nonNull(diff, mod.getAttribute().getValue) { (d, value) =>
                      d.copy(modInheritMode = Some(SimpleDiff(oldParam.inheritMode, InheritMode.parseString(value).toOption)))
                    }
                  case A_VISIBILITY        =>
                    nonNull(diff, mod.getAttribute().getValue) { (d, value) =>
                      d.copy(modVisibility =
                        Some(SimpleDiff(Some(oldParam.visibility), Visibility.withNameInsensitiveEither(value).toOption))
                      )
                    }
                  case "overridable"       => diff // ignore, it's for cleaning
                  case A_SECURITY_TAG      =>
                    updateSecurityTag(diff, mod.getAttribute.getValue) { (d, t) =>
                      d.copy(modSecurityTag = Some(SimpleDiff(oldParam.security, t)))
                    }
                  case x                   => Left(Err.UnexpectedObject("Unknown diff attribute: " + x))
                }
              }
          } yield {
            Some(diff)
          }

        case noop: LDIFNoopChangeRecord => Right(None)

        case _ => Left(Err.UnexpectedObject("Bad change record type for requested action 'save Parameter': %s".format(change)))
      }
    } else {
      Left(
        Err.UnexpectedObject(
          "The following change record does not belong to Parameter entry '%s': %s".format(parameterDn, change)
        )
      )
    }
  }

  // API Account diff
  def addChangeRecords2ApiAccountDiff(
      parameterDN: DN,
      change:      LDIFChangeRecord
  ): PureResult[AddApiAccountDiff] = {
    if (change.getParsedDN == parameterDN) {
      change match {
        case add: LDIFAddChangeRecord =>
          val e = LDAPEntry(add.toAddRequest().toEntry)
          for {
            param <- mapper.entry2ApiAccount(e)
          } yield AddApiAccountDiff(param)
        case _ => Left(Err.UnexpectedObject(s"Bad change record type for requested action 'Add Api Account': ${change}"))
      }
    } else {
      Left(Err.UnexpectedObject(s"The following change record does not belong to Parameter entry '${parameterDN}': ${change}"))
    }
  }

  /**
   * Map a list of com.unboundid.ldif.LDIFChangeRecord into a
   * ApiAccountDiff.
   */
  def modChangeRecords2ApiAccountDiff(
      beforeChangeEntry: LDAPEntry,
      change:            LDIFChangeRecord
  ): PureResult[Option[ModifyApiAccountDiff]] = {
    if (change.getParsedDN == beforeChangeEntry.dn) {
      change match {
        case modify: LDIFModifyChangeRecord =>
          for {
            oldAccount <- mapper.entry2ApiAccount(beforeChangeEntry)
            diff       <- modify.getModifications().foldLeft(ModifyApiAccountDiff(oldAccount.id).asRight[RudderError]) { (diff, mod) =>
                            mod.getAttributeName() match {
                              case A_NAME                        =>
                                nonNull(diff, mod.getOptValueDefault("")) { (d, value) =>
                                  d.copy(modName = Some(SimpleDiff(oldAccount.name.value, value)))
                                }
                              case A_API_TOKEN                   =>
                                nonNull(diff, mod.getOptValueDefault("")) { (d, value) =>
                                  d.copy(modToken = {
                                    Some(
                                      SimpleDiff(oldAccount.accountToken.flatMap(_.hash).flatMap(_.exposeHash()).getOrElse(""), value)
                                    )
                                  })
                                }
                              case A_DESCRIPTION                 =>
                                nonNull(diff, mod.getOptValueDefault("")) { (d, value) =>
                                  d.copy(modDescription = Some(SimpleDiff(oldAccount.description, value)))
                                }
                              case A_IS_ENABLED                  =>
                                nonNull(diff, mod.getAttribute().getValueAsBoolean) { (d, value) =>
                                  d.copy(modIsEnabled = Some(SimpleDiff(oldAccount.isEnabled, value)))
                                }
                              case A_API_TOKEN_CREATION_DATETIME =>
                                nonNull(diff, mod.getOptValueDefault("")) { (d, value) =>
                                  val diffDate = GeneralizedTime.parse(value).map(_.instant)
                                  d.copy(modTokenGenerationDate = {
                                    diffDate
                                      .map(date => (SimpleDiff(oldAccount.tokenGenerationDate, date)))
                                  })
                                }
                              case A_CREATION_DATETIME           =>
                                nonNull(diff, mod.getOptValueDefault("")) { (d, value) =>
                                  val diffDate = GeneralizedTime.parse(value).map(_.instant)
                                  d.copy(modCreationDate = diffDate.map(date => (SimpleDiff(oldAccount.creationDate, date))))
                                }
                              case A_API_EXPIRATION_DATETIME     =>
                                val expirationDate = oldAccount.kind match {
                                  case PublicApi(_, policy) => policy.expirationDate
                                  case _                    => None
                                }
                                val diffDate       = {
                                  try {
                                    mod.getOptValueDefault("") match {
                                      case "None" => None
                                      case v      => GeneralizedTime.parse(v).map(_.instant)
                                    }
                                  } catch {
                                    case ex: Exception => None
                                  }
                                }
                                diff.map(_.copy(modExpirationDate = Some(SimpleDiff(expirationDate, diffDate))))
                              case A_API_KIND                    =>
                                diff.map(_.copy(modAPIKind = Some(SimpleDiff(oldAccount.kind.kind.name, mod.getOptValueDefault("")))))
                              case A_API_AUTHZ_KIND              =>
                                val oldAuthType = oldAccount.kind match {
                                  case PublicApi(auth, _) =>
                                    auth.kind.name
                                  case kind               =>
                                    kind.kind.name
                                }
                                diff.map(_.copy(modAccountKind = Some(SimpleDiff(oldAuthType, mod.getOptValueDefault("")))))
                              case A_API_ACL                     =>
                                val oldAcl = oldAccount.kind match {
                                  case PublicApi(ApiAuthorization.ACL(acl), _) =>
                                    acl
                                  case kind                                    =>
                                    Nil
                                }

                                for {
                                  d   <- diff
                                  acl <- mapper.unserApiAcl(mod.getOptValueDefault("")).leftMap(error => Err.UnexpectedObject(error))
                                } yield {
                                  d.copy(modAccountAcl = Some(SimpleDiff(oldAcl, acl)))
                                }
                              case A_API_TENANT                  =>
                                diff.map(
                                  _.copy(modAPITenants = Some(SimpleDiff(oldAccount.tenants.serialize, mod.getOptValueDefault("-"))))
                                )

                              case x => Left(Err.UnexpectedObject("Unknown diff attribute: " + x))
                            }
                          }
          } yield {
            Some(diff)
          }

        case noop: LDIFNoopChangeRecord => Right(None)

        case _ => Left(Err.UnexpectedObject("Bad change record type for requested action 'update rule': %s".format(change)))
      }
    } else {
      Left(
        Err.UnexpectedObject(
          "The following change record does not belong to Rule entry '%s': %s".format(beforeChangeEntry.dn, change)
        )
      )
    }
  }
}
