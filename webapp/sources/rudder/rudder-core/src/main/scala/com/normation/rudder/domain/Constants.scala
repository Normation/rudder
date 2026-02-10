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

package com.normation.rudder.domain

import com.normation.inventory.domain.NodeId

object Constants {

  val ROOT_POLICY_SERVER_ID: NodeId = NodeId("root")

  /////////// PATH for generated promises ///////////////

  /**
   * We don't have any simple way to tell a node to not look for
   * its promises elsewhere than where it was configured initially
   * (in its initial promises)
   * So that path must be hardcoded until we have a mean to say to a
   * node "if you don't find you specific promises, go look here
   * for a shared failsafe that will help you".
   */
  val NODE_PROMISES_PARENT_DIR_BASE = "/var/rudder/"
  val NODE_PROMISES_PARENT_DIR      = "share"

  val CFENGINE_COMMUNITY_PROMISES_PATH = "/var/rudder/cfengine-community/inputs"

  val GENERATED_PROPERTY_DIR   = "properties.d"
  val GENERATED_PROPERTY_FILE  = "properties.json"
  val GENERATED_PARAMETER_FILE = "rudder-parameters.json"

  /////////////////////////////////////////////////

  val TECHLIB_MINIMUM_UPDATE_INTERVAL = 1 // in minutes

  val DYNGROUP_MINIMUM_UPDATE_INTERVAL = 1 // in minutes

  val XML_FILE_FORMAT_1_0 = "1.0"
  // for 2 and above, we *only* use integer number
  val XML_FILE_FORMAT_2   = 2
  val XML_FILE_FORMAT_3   = 3
  val XML_FILE_FORMAT_4   = 4
  val XML_FILE_FORMAT_5   = 5
  val XML_FILE_FORMAT_6   = 6

  /*
   * WARNING: we can't change that file format if we want to
   * be able to provid git-base synchronisation between
   * major Rudder version. Either relaxe the fileFormat
   * verification (i.e: "current file format equals or inferior to
   * the one of the file" - ensuring that all mod are forward compatible
   * (i.e: only add things)), or create a post-archive restore - pre reload
   * archive that sed the fileFormat (erk).
   * See https://www.rudder-project.org/redmine/issues/11155
   * for more information.
   */
  val XML_CURRENT_FILE_FORMAT = XML_FILE_FORMAT_6

  val CONFIGURATION_RULES_ARCHIVE_TAG = "#rules-archive"
  val RULE_CATEGORY_ARCHIVE_TAG       = "#rule-categories-archive"
  val GROUPS_ARCHIVE_TAG              = "#groups-archive"
  val POLICY_LIBRARY_ARCHIVE_TAG      = "#directives-archive"
  val FULL_ARCHIVE_TAG                = "#full-archive"
  val PARAMETERS_ARCHIVE_TAG          = "#parameters-archive"

  ///// XML tag names for directive, categories, etc

  val XML_TAG_RULE                      = "rule"
  val XML_TAG_RULE_CATEGORY             = "ruleCategory"
  val XML_TAG_ACTIVE_TECHNIQUE_CATEGORY = "activeTechniqueCategory"
  val XML_TAG_ACTIVE_TECHNIQUE          = "activeTechnique"
  val XML_TAG_DIRECTIVE                 = "directive"
  val XML_TAG_EDITOR_TECHNIQUE          = "technique"
  val XML_TAG_NODE_GROUP_CATEGORY       = "nodeGroupCategory"
  val XML_TAG_NODE_GROUP                = "nodeGroup"
  val XML_TAG_DEPLOYMENT_STATUS         = "deploymentStatus"
  val XML_TAG_CHANGE_REQUEST            = "changeRequest"
  val XML_TAG_GLOBAL_PARAMETER          = "globalParameter"
  val XML_TAG_API_ACCOUNT               = "apiAccount"
  val XML_TAG_GLOBAL_PROPERTY           = "globalProperty"

  val HOOKS_IGNORE_FILE_NAME = "non-exec-extensions.txt"

  // for secret variable
  val XML_TAG_SECRET = "secret"
}
