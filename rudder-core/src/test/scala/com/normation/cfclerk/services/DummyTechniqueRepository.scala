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
import java.io.{ InputStream, File }
import net.liftweb.common._
import scala.collection.SortedSet

class DummyTechniqueRepository(policies: Seq[Technique] = Seq()) extends TechniqueRepository {

  var returnedVariable = collection.mutable.Set[VariableSpec]()
  val policy1 = Technique(TechniqueId(TechniqueName("policy1"), TechniqueVersion("1.0")), "policy1", "", Seq(), Seq(), Seq(Bundle("one")), TrackerVariableSpec(), SectionSpec(name="root", children=Seq(InputVariableSpec("$variable1", "a variable1"))), None)

  val sections = SectionSpec(name="root", children=Seq(InputVariableSpec("$variable2", "a variable2", multivalued = true), InputVariableSpec("$variable22", "a variable22")))
  val policy2 = Technique(TechniqueId(TechniqueName("policy2"), TechniqueVersion("1.0")), "policy2", "", Seq(), Seq(), Seq(Bundle("two")), TrackerVariableSpec(), sections, None)

  val sections3 = SectionSpec(name="root", children=Seq(InputVariableSpec("$variable3", "a variable3")))
  val policy3 = Technique(TechniqueId(TechniqueName("policy3"), TechniqueVersion("1.0")), "policy3", "", Seq(), Seq(), Seq(Bundle("three")), TrackerVariableSpec(), sections3, None)

  val sections4 = SectionSpec(name="root", children=Seq(InputVariableSpec("$variable4", "an variable4")))
  val policy4 = Technique(TechniqueId(TechniqueName("policy4"), TechniqueVersion("1.0")), "policy4", "", Seq(), Seq(), Seq(Bundle("four")), TrackerVariableSpec(), sections4, None)

  val sectionsFoo = SectionSpec(name="root", children=Seq(InputVariableSpec("$bar", "bar")))
  val foo = Technique(TechniqueId(TechniqueName("foo"), TechniqueVersion("1.0")), "foo", "", Seq(), Seq(), Seq(Bundle("foo")), TrackerVariableSpec(), sectionsFoo, None)

  val policyMap = Map(policy1.id -> policy1,
    policy2.id -> policy2,
    policy3.id -> policy3,
    policy4.id -> policy4,
    foo.id -> foo) ++ policies.map(p => (p.id, p))

  def this() = this(Seq()) //Spring need that...

  override def getMetadataContent[T](techniqueId: TechniqueId)(useIt: Option[InputStream] => T): T = ???
  override def getTemplateContent[T](id: TechniqueResourceId)(useIt: Option[InputStream] => T): T = ???
  override def getFileContent[T](id: TechniqueResourceId)(useIt: Option[InputStream] => T): T = ???
  override def getReportingDetailsContent[T](techniqueId: TechniqueId)(useIt: Option[InputStream] => T): T = ???
  override def getAll(): Map[TechniqueId, Technique] = { policyMap }

  override def get(policyName: TechniqueId): Option[Technique] = {
    policyMap.get(policyName)
  }

  override def getByIds(policiesName: Seq[TechniqueId]): Seq[Technique] = {
    policiesName.map(x => policyMap(x))
  }

  override def getLastTechniqueByName(policyName: TechniqueName): Option[Technique] = {
    policyMap.get(TechniqueId(policyName, TechniqueVersion("1.0")))
  }

  override def getByName(policyName: TechniqueName) = ???

  override def getTechniquesInfo = ???

  override def getTechniqueVersions(name:TechniqueName) : SortedSet[TechniqueVersion] = SortedSet.empty[TechniqueVersion]

  override def getTechniqueLibrary: RootTechniqueCategory = null
  override def getTechniqueCategory(id: TechniqueCategoryId): Box[TechniqueCategory] = null
  override def getParentTechniqueCategory_forTechnique(id: TechniqueId): Box[TechniqueCategory] = null

}
