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

package com.normation.cfclerk.xmlparsers

object CfclerkXmlConstants {

  /* name of the String Template variable used for the tracking variable */
  val TRACKINGKEY = "TRACKINGKEY"

  /* name of the component key for a monovalued section */
  val DEFAULT_COMPONENT_KEY = "None"

  val SECTION_ROOT_NAME = "SECTIONS"


  val RUDDER_CONFIGURATION_REPOSITORY = "RUDDER_CONFIGURATION_REPOSITORY"

  /* tag names */

  //top level and general
  val TECHNIQUE_ROOT = "TECHNIQUE"
  val TECHNIQUE_NAME = "name" //it's an attribute
  val TECHNIQUE_DESCRIPTION = "DESCRIPTION"
  val TECHNIQUE_IS_MULTIINSTANCE = "MULTIINSTANCE"
  val TECHNIQUE_LONG_DESCRIPTION = "LONG_DESCRIPTION"
  val TECHNIQUE_IS_SYSTEM = "SYSTEM"
  val TECHNIQUE_DEPRECATION_INFO = "DEPRECATED"

  //bundles
  val BUNDLES_ROOT = "BUNDLES"
  val BUNDLE_NAME = "NAME"

  //promise templates / files
  val PROMISE_TEMPLATES_ROOT = "TMLS"
  val PROMISE_TEMPLATE = "TML"
  val FILES = "FILES"
  val FILE = "FILE"

  val PROMISE_TEMPLATE_NAME = "name" //attribute
  val PROMISE_TEMPLATE_OUTPATH = "OUTPATH"
  val PROMISE_TEMPLATE_INCLUDED = "INCLUDED"

  //tracking variable
  val TRACKINGVAR = "TRACKINGVARIABLE"
  val TRACKINGVAR_SIZE = "SAMESIZEAS"

  //system vars
  val SYSTEMVARS_ROOT = "SYSTEMVARS"
  val SYSTEMVAR_NAME = "NAME"

  //compatibility
  val COMPAT_TAG = "COMPATIBLE"
  val COMPAT_OS = "OS"
  val COMPAT_AGENT = "AGENT"


  //sections
  val SECTIONS_ROOT = "SECTIONS"
  val SECTION = "SECTION"
  val SECTION_NAME = "name" //attribute of SECTION
  val SECTION_IS_MULTIVALUED = "multivalued" //attribute of SECTION
  val SECTION_DISPLAYPRIORITY = "displayPriority"//attribute of SECTION
  val SECTION_DESCRIPTION = "DESCRIPTION"
  val SECTION_IS_COMPONENT = "component"
  val SECTION_COMPONENT_KEY = "componentKey"

  //section variables
  val INPUT = "INPUT"
  val SELECT1 = "SELECT1"
  val SELECT = "SELECT"
  val REPORT_KEYS = "REPORTKEYS"

  val VAR_NAME = "NAME"
  val VAR_DESCRIPTION = "DESCRIPTION"
  val VAR_LONG_DESCRIPTION = "LONGDESCRIPTION"
  val VAR_IS_UNIQUE_VARIABLE = "UNIQUEVARIABLE"
  val VAR_IS_MULTIVALUED = "MULTIVALUED"
  val VAR_CONSTRAINT = "CONSTRAINT"
  val VAR_IS_CHECKED = "CHECKED"
  val REPORT_KEYS_VALUE = "VALUE"
  val CONSTRAINT_ITEM = "ITEM"
  val CONSTRAINT_ITEM_VALUE = "VALUE"
  val CONSTRAINT_ITEM_LABEL = "LABEL"
  val CONSTRAINT_TYPE = "TYPE"
  val CONSTRAINT_MAYBEEMPTY = "MAYBEEMPTY"
  val CONSTRAINT_DEFAULT = "DEFAULT"
  val CONSTRAINT_REGEX = "REGEX"
  val CONSTRAINT_PASSWORD_HASH = "PASSWORDHASH"

}
