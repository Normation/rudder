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

package com.normation.rudder.domain.policies

import scala.collection.mutable.Buffer
import scala.xml._
import com.normation.utils.Utils.nonEmpty
import com.normation.cfclerk.domain.TechniqueVersion
import com.normation.utils.HashcodeCaching
import com.normation.cfclerk.domain.SectionSpec
import com.normation.cfclerk.domain.Technique


case class DirectiveId(value:String) extends HashcodeCaching

/**
 * Define a directive.
 *
 * From a business point of view, a directive is a general
 * policy about your infrastructure, like "our password must be
 * 10 chars, mixed symbol, case, number".
 *
 * In Rudder, a Directive is derived from a technique on which
 * we are going to bind parameter to values matching the business
 * directive. For example, in our example, it could be
 * "Unix Password management with passwd"
 *
 * A directive also keep other information, like the priority
 * of that directive compared to other directive derived from the
 * the same technique.
 *
 */
case class Directive(
  id:DirectiveId,

  //TODO: why not keeping techniqueName here ? data duplication ?

  /**
   * They reference one and only one Technique version
   */
  techniqueVersion:TechniqueVersion,

  /**
   * The list or parameters with their values.
   * TODO: I really would like to be able to not allow to set bad parameter here,
   *       what mean parameter that are not in the technique.
   *       For now, say it's done by construction.
   */
  parameters:Map[String, Seq[String]],

  /**
   * A human readable name for that directive,
   * typically used for CSV/grid header
   * i.e: "SEC-042 Debian Etch"
   * Can not be empty nor null.
   */
  name:String,

  /**
   * Short description, typically used as field description
   * Can not be empty nor null.
   */
  shortDescription:String,

  /**
   * A long, detailed description, typically used for
   * tooltip. It allows reach content.
   * Can be empty (and is by default).
   */
  longDescription:String = "",

  /**
   * For policies which allows only one configured instance at
   * a given time for a given node, priority allows to choose
   * the policy to deploy.
   * Higher priority is better, default is 5
   */
  priority:Int = 5,

  /**
   * Define if the policy is activated.
   * If it is not, configuration based on that policy should not be considered
   * for deployment on nodes.
   */
  isEnabled:Boolean = false,

  isSystem:Boolean = false
) extends HashcodeCaching


final case class SectionVal(
    sections  : Map[String, Seq[SectionVal]] = Map() //name -> values
  , variables : Map[String, String]          = Map() //name -> values
) extends HashcodeCaching

object SectionVal {
  val ROOT_SECTION_NAME = "sections"


  def toXml(sv:SectionVal, sectionName:String = ROOT_SECTION_NAME): Node = {
    <section name={sectionName}>
      { //variables
        sv.variables.toSeq.sortBy(_._1).map { case (variable,value) =>
          <var name={variable}>{value}</var>
        } ++
        //section
        (for {
          (sectionName, sectionIterations) <- sv.sections.toSeq.sortBy(_._1)
          sectionValue <- sectionIterations
        } yield {
          this.toXml(sectionValue,sectionName)
        })
      }
    </section>
  }

  def directiveValToSectionVal(rootSection:SectionSpec, allValues:Map[String,Seq[String]]) : SectionVal = {
    /*
     * build variables with a parent section multivalued.
     */
    def buildMonoSectionWithMultivaluedParent(spec:SectionSpec, index:Int) : SectionVal = {
      if(spec.isMultivalued) throw new RuntimeException("We found a multivalued subsection of a multivalued section: " + spec)

      //variable for that section: Map[String, String]
      val variables = spec.getDirectVariables.map { vspec =>
        (vspec.name, allValues(vspec.name)(index))
      }.toMap

      /*
       * Get subsection. We can have several, all mono-valued
       */
      val subsections = spec.getDirectSections.map { sspec =>
         (sspec.name, Seq(buildMonoSectionWithMultivaluedParent(sspec,index)))
      }.toMap

      SectionVal(subsections, variables)

    }

    def buildMultiSectionWithoutMultiParent(spec:SectionSpec) : Seq[SectionVal] = {
      if(!spec.isMultivalued) throw new RuntimeException("We found a monovalued section where a multivalued section was asked for: " + spec)

      // find the number of iteration for that multivalued section.
      // try with a direct variable, and if the section has no direct variable, with the first direct section with a variable
      val cardinal = {
        val name = spec.getDirectVariables.toList match {
          case v :: tail => v.name
          case _ => //look for the first section with a var
            spec.getDirectSections.find { s => s.getDirectVariables.nonEmpty }.map { s =>
              s.getDirectVariables.head.name
            }.getOrElse("NO VARIABLE !!!") //used name should not be a key
        }
        allValues.get(name).map( _.size ).getOrElse(0)
      }

      //find variable of that section
      val multiVariables : Seq[Map[String,String]] = {
        for {
          i <- 0 until cardinal
        } yield {
          spec.getDirectVariables.map { vspec => (vspec.name, allValues(vspec.name)(i)) }.toMap
        }
      }

      //build subsections:
      val multiSections : Seq[Map[String, SectionVal]] = {
        for {
          i <- 0 until cardinal
        } yield {
          spec.getDirectSections.map { sspec =>
            ( sspec.name, buildMonoSectionWithMultivaluedParent(sspec, i) )
          }.toMap
        }
      }

      for {
        i <- 0 until cardinal
      } yield {
        //here, children section must be with a cardinal of 1 (monovalued)
        val sections = multiSections(i).map { case(k,s) => (k,Seq(s)) }.toMap
        SectionVal(sections, multiVariables(i))
      }
    }

    def buildMonoSectionWithoutMultivaluedParent(spec:SectionSpec) : SectionVal = {
      val variables = spec.getDirectVariables.map { vspec =>
        //we can have a empty value for a variable, for non mandatory ones
        (vspec.name, allValues.getOrElse(vspec.name,Seq(""))(0))
      }.toMap

      val sections = spec.getDirectSections.map { vspec =>
        if(vspec.isMultivalued) {
          (vspec.name, buildMultiSectionWithoutMultiParent(vspec))
        } else {
          (vspec.name, Seq(buildMonoSectionWithoutMultivaluedParent(vspec)))
        }
      }.toMap

      SectionVal(sections,variables)
    }

    buildMonoSectionWithoutMultivaluedParent(rootSection)
  }

  def toMapVariables(sv:SectionVal) : Map[String,Seq[String]] = {
    import scala.collection.mutable.{Map, Buffer}
    val res = Map[String, Buffer[String]]()

    def recToMap(sec:SectionVal) : Unit = {
      sec.variables.foreach { case (name,value) =>
        res.getOrElseUpdate(name, Buffer()).append(value)
      }
      sec.sections.foreach { case (_, sections) =>
        sections.foreach { recToMap( _ ) }
      }
    }

    recToMap(sv)
    res.map { case (k,buf) => (k,buf.toSeq) }.toMap
  }
}