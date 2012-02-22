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

package com.normation.rudder.web.services

import org.junit.Test
import org.junit._
import org.junit.Assert._
import org.junit.runner.RunWith
import org.junit.runners.BlockJUnit4ClassRunner
import org.junit.runner._
import org.specs2.mutable._
import org.specs2.runner._

import com.normation.rudder.web.model.SectionField
import com.normation.cfclerk.domain._
import com.normation.rudder.web.model._
import org.springframework.context.{ ApplicationContext, ApplicationContextAware }
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import com.normation.rudder.domain.policies.RuleVal
import org.springframework.context.annotation.{ Bean, Configuration, Import }
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Lazy
import org.springframework.context.annotation.{ Bean, Configuration, Import, ImportResource }
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.{ ApplicationContext, ApplicationContextAware }
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import org.springframework.core.io.ClassPathResource
import com.normation.spring._

@RunWith(classOf[JUnitRunner])
class Section2FieldServiceTest extends Specification {

  // <sections>
  //   <section name="multSect" multivalued="true">
  //     <section name="innerSect">
  //       <select>
  //       ...
  //       <policyinstance>
  //     </section>
  //     <select>
  //       <name>selectInMultSect</name>
  //     </select>
  //   </section>
  //   <input>
  //     <name>inputInRoot</name>
  //   </input>
  // </sections>
  object Sections {
    val rootSectField = RootSectionField()
    val multSect = rootSectField.getAllSectionFields(1)
    val innerSect = multSect.getAllSectionFields(2)
  }

  "multSect" should {
    implicit val multSect = Sections.multSect
    beMultivalued
    haveName("multSect")
    haveNbChildren(3)
  }

  "innerSect" should {
    implicit val innerSect = Sections.innerSect
    haveName("innerSect")
    haveNbChildren(3)
  }

  def haveNbChildren(nbChildren: Int)(implicit section: SectionField) = {
    "have %d children".format(nbChildren) in {
      section.childFields.size mustEqual nbChildren
    }
  }

  def beMultivalued(implicit section: SectionField) = {
    "be multivalued" in {
      section.isMultivalued
    }
  }
  def haveName(name: String)(implicit section: SectionField) = {
    "have name '%s'".format(name) in {
      section.name mustEqual name
    }
  }

  def haveId(id: String)(implicit varField: DirectiveField) = {
    "have id '%s'".format(id) in {
      varField.id mustEqual id
    }
  }

  def haveAllVars(implicit section: SectionField) = {
    "have all kinds of variable" in {
      val vars = section.childFields.collect { case v: DirectiveField => v }
      isSelect(vars(0))

    }
  }

  def isSelect(varField: DirectiveField) = {
    "is a select variable" in {
      varField must beAnInstanceOf[SelectField]
    }
  }

  def isText(varField: DirectiveField) = {
    "is an input variable" in {
      varField must beAnInstanceOf[TextField]
    }
  }

  def allVars(): Seq[SectionVariableSpec] = {
    SelectVariableSpec("select", "selectDesc") ::
      SelectOneVariableSpec("selectOne", "selectOneDesc") ::
      InputVariableSpec("input", "inputDesc") ::
      Nil
  }

  object RootSectionField {

    def apply() = {
      val appContext = new ScalaAnnotationConfigApplicationContext {}
      appContext.setToNewContext(classOf[ConfigSection2FieldService])

      import appContext._

      val service = inject[Section2FieldService]
      val rootSectSpec = createRootSectionSpec
      service.createSectionField(rootSectSpec,Map(),true)
    }

    def createRootSectionSpec = {
      val innerMultSect = SectionSpec("innerMultSect", isMultivalued = true, children = allVars())
      val innerSect = SectionSpec("innerSect", children = allVars())
      val selectInMultSect = SelectVariableSpec("selectInMultSect", "selectInMultSectDesc")

      val childrenMultSect = Seq(innerMultSect, innerSect, selectInMultSect)
      val multSect = SectionSpec("multSect", isMultivalued = true, children = childrenMultSect)

      val inputInRoot = InputVariableSpec("inputInRoot", "inputInRootDesc")

      val rootSect = SectionSpec("rootSect", children = Seq(inputInRoot, multSect))
      rootSect
    }
  }
  
}

@Configuration
class ConfigSection2FieldService {

  object FieldFactoryImpl extends DirectiveFieldFactory {
    //only one field

    override def forType(v: VariableSpec, id: String): DirectiveField = {
      v match {
        case selectOne: SelectOneVariableSpec => new SelectOneField(id, selectOne.valueslabels)
        case select: SelectVariableSpec => new SelectField(id, select.valueslabels)
        case input: InputVariableSpec => v.constraint.typeName.toLowerCase match {
          case "uploadedfile" => new UploadedFileField("")(id)
          case "destinationfullpath" => default(id)
          case "perm" => new FilePermsField(id)
          case "boolean" => new CheckboxField(id)
          case "size" => new InputSizeField(id)
          case _ => default(id)
        }
        case _ =>
          default(id)
      }
    }

    override def default(id: String) = new TextField(id)
  }

  @Bean
  def section2FieldService: Section2FieldService = {
      def translators = {
        val t = new Translators()
        t.add(StringTranslator)
        t.add(FilePermsTranslator)
        t.add(FileTranslator)
        t.add(DestinationFileTranslator)
        t.add(SelectFieldTranslator)
        t
      }
    new Section2FieldService(FieldFactoryImpl, translators)
  }
}

