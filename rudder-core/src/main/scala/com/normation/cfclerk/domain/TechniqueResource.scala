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

package com.normation.cfclerk.domain

import com.normation.utils.HashcodeCaching




/**
 * Representation of a technique resource id.
 *
 * A resource may be either defined relatively to a technique, or relatively to
 * the configuration-repository directory (the git root directory).
 * Template filename extension is mandatory to be ".st".
 */
sealed trait TechniqueResourceId {
  def name: String
}

/**
 * A template resource whose path is relative to the technique directory (i.e, where the metadata.xml file is).
 * It can be a path like "subdirectory/myTemplate"
 * The name must not contain the extension.
 */
final case class TechniqueResourceIdByName(techniqueId: TechniqueId, name: String) extends TechniqueResourceId

/**
 * A template resource whose path is relative to the configuration-repository directory (i.e, the git root
 * directory)
 * ParentDirectories is the list of directory names from that root directory to the file.
 * For example, an empty list means that the template is in configuration-repository directory.
 * Name is an unix compliant file name, without the ".st" extension.
 */
final case class TechniqueResourceIdByPath(parentDirectories: List[String], name: String) extends TechniqueResourceId

final case class TechniqueFile(
    /*
     * This is the identifier of the file.
     * Path to the file depends upon the type of resource, either
     * relative to the technique or to the configuration-repository
     */
    id: TechniqueResourceId

    /*
     *  Path where to PUT the template (e.g. for resources for ips)
     *  This path is relative to the "cf-engine" root directory on the
     *  server.
     *  It must be the full path, with the name of the cf-engine promise.
     *  By default, it will be set to: ${POLICY NAME}/${template name}.cf
     */
  , outPath: String
)

/**
 * The Tml class holds the representation of a template, containing the template name,
 * and if the file must be included, and where it should be written
 *
 * The way it's meant to be used :
 * the template is fetch from the StringTemplateGroup using the path/template information
 * vars are replaced
 * it is written at outPath/name
 *
 */
final case class TechniqueTemplate(
    /*
     * This is the template identifier of the file.
     * The real file name in git will be derived from that name by adding
     * the template ".st" extension to the end of the name.
     * Path to the file depends upon the type of resource, either
     * relative to the technique or to the configuration-repository
     */
    id: TechniqueResourceId

    /*
     *  Path where to PUT the template (e.g. for resources for ips)
     *  This path is relative to the "cf-engine" root directory on the
     *  server.
     *  It must be the full path, with the name of the cf-engine promise.
     *  By default, it will be set to: ${POLICY NAME}/${template name}.cf
     */
  , outPath: String

    /*
     * Does this template must be included in the list of
     * file to include in promise.cf ?
     */
  , included: Boolean
)

object TechniqueTemplate {
  val templateExtension = ".st"
  val promiseExtension = ".cf"
}

