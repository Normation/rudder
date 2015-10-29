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

package com.normation.cfclerk.services

import com.normation.cfclerk.domain._
import scala.collection.mutable.{Map => MutMap}
import java.io.InputStream
import scala.collection.SortedSet

/**
 * Dummy knowledge reader, to test the prototyped PathComputer
 * @author nicolas
 *
 */
class DummyTechniqueReader(policies:Seq[Technique]=Seq(Technique(TechniqueId(TechniqueName("dummy"), TechniqueVersion("1.0")),"dummy", "DESCRIPTION",Seq(), Seq(), TrackerVariableSpec(), SectionSpec("ROOT"), None))) extends TechniqueReader {

  def this() = this(Seq()) //Spring need that...

  val rootCategoryId = RootTechniqueCategoryId / "rootCategory"

  //they are all under root
  def readTechniques(): TechniquesInfo = {
    val packagesPath = MutMap[TechniqueId,TechniqueCategoryId]()
    val packages = MutMap[TechniqueName , collection.immutable.SortedMap[TechniqueVersion,Technique]]()

    var rootCategory = RootTechniqueCategory(
        "Root category",
        "The main category under witch all other are",
        Set(), SortedSet(), true
    )

    for {
      p <- policies
    } {
      packagesPath(p.id) =  rootCategoryId
      packages(p.id.name) = (packages.getOrElse(p.id.name,collection.immutable.SortedMap.empty[TechniqueVersion,Technique]) + (p.id.version -> p))
      rootCategory = rootCategory.copy( packageIds = rootCategory.packageIds + p.id)
    }

    TechniquesInfo(rootCategory, packagesPath.toMap, packages.toMap, Map(), Map())
  }

  def getMetadataContent[T](techniqueId: TechniqueId)(useIt : Option[InputStream] => T) : T = useIt(None)
  def checkreportingDescriptorExistence(techniqueId: TechniqueId) : Boolean = false
  def getReportingDetailsContent[T](techniqueId: TechniqueId)(useIt : Option[InputStream] => T) : T = useIt(None)
  def getTemplateContent[T](templateName: Cf3PromisesFileTemplateId)(useIt : Option[InputStream] => T) : T = useIt(None)
  def getModifiedTechniques : Map[TechniqueName, TechniquesLibraryUpdateType] = Map()

}
