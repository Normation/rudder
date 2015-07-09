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

import net.liftweb.common._
import org.slf4j.{Logger,LoggerFactory}
import scala.xml._
import com.normation.utils.HashcodeCaching




/**
 * Representation of a template qualified name.
 * A template is linked to a policy, so that two
 * different policies can have templates with identical
 * names.
 *
 * A template qualified name has a string representation
 * that is by convention "policy name" / "template name".
 */
class Cf3PromisesFileTemplateId(val techniqueId:TechniqueId, val name:String) {
  override def toString() = techniqueId.toString + "/" + name

  override def equals(other:Any) = other match {
    case that:Cf3PromisesFileTemplateId => this.techniqueId  == that.techniqueId && this.name == that.name
    case _ => false
  }

  override lazy val hashCode = this.techniqueId.hashCode + 61 * this.name.hashCode
}

object Cf3PromisesFileTemplateId {
  def apply(techniqueId:TechniqueId, name:String) = new Cf3PromisesFileTemplateId(techniqueId, name)

  def unapply(str:String) : Option[(TechniqueId,String)] = {
    val parts = str.split("/").map(_.replaceAll("""\s""", ""))
    if(parts.size == 3 && parts(1).size > 0) Some((TechniqueId(TechniqueName(parts(0)), TechniqueVersion(parts(1))),parts(2)))
    else None
  }
}


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
case class Cf3PromisesFileTemplate(
  /*
   * This is the template identifier of the file.
   * The path of the matching template will be derived from that name by adding
   * the template extension to the end of the name.
   * (by default, ".st")
   */
  id      : Cf3PromisesFileTemplateId,
  included: Boolean, // by default, we include the template in the promises.cf
  /*
   *  Path where to PUT the template (e.g. for resources for ips)
   *  This path is relative to the "cf-engine" root directory on the
   *  server.
   *  It must be the full path, with the name of the cf-engine promise.
   *  By default, it will be set to: ${POLICY NAME}/${template name}.cf
   */
  outPath : String
) extends HashcodeCaching

object Cf3PromisesFileTemplate {
  val templateExtension = ".st"
  val promiseExtension = ".cf"
}

