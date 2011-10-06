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

import org.junit.runner.RunWith
import org.specs2.mutable._
import org.specs2.runner._
import net.liftweb.common._
import com.normation.cfclerk.domain._

@RunWith(classOf[JUnitRunner])
class SectionValTest extends Specification with Loggable {

  
  "A simple section" should {
    /*
     * <section name="root">
     *   <section name="section1">
     *     <var name="var1" />
     *   </section>
     * </section>
     */
    val simpleSpec = SectionSpec(
        name = "root"
      , children = 
          SectionSpec(
              name = "section1"
            , children = 
                InputVariableSpec(
                    name = "var1"
                  , description = ""
                ) ::
                Nil
          ) ::
          Nil
    )
    
    val sectionVal = SectionVal(
        sections = Map(
            "section1" -> Seq(
                SectionVal(
                  variables = Map("var1" -> "var1A")    
                )
            )
        )
    )
  
    val emptySectionVal = SectionVal(
        sections = Map(
            "section1" -> Seq(
                SectionVal(
                  variables = Map("var1" -> "")    
                )
            )
        )
    )
    
    val mapOk = Map("var1" -> Seq("var1A"))
    
    "be mappable from the good map" in {
      sectionVal === SectionVal.piValToSectionVal(simpleSpec, mapOk)
    }
    
    "be idempotent starting from the map" in {
      mapOk ===  SectionVal.toMapVariables(SectionVal.piValToSectionVal(simpleSpec, mapOk))
    }
    
    "be idempotent starting from the sectionVal" in {
      sectionVal ===  SectionVal.piValToSectionVal(simpleSpec, SectionVal.toMapVariables(sectionVal))
    }
    
    "accept empty values for vars" in {
      SectionVal.piValToSectionVal(simpleSpec, Map()) === emptySectionVal
    }
    
    "ignore unspecified var name in the map of values" in {
      SectionVal.piValToSectionVal(simpleSpec, Map("foo" -> Seq("bar"))) === emptySectionVal
    }
  }
  
  
  "A section with multivalued subsection" should {
    
    /*
     * <section name="root">
     *   <section name="sec0">
     *     <var name="var0"/>
     *   </section>
     *  
     *   <section name="sec1" multi="true">
     *     <var name="var1"/>
     *     <section name="sec2">
     *       <var name="var2"/>
     *     </section>
     *   </section>
     *   
     *   <section name="sec3">
     *     <var name="var3"/>
     *     <section name="sec4" multi="true">
     *       <var name="var4"/>
     *       <section name="sec5">
     *         <var name="var5"/>
     *       </section>
     *     </section>
     *   </section>
     * </section>
     */
    val spec = SectionSpec(
        name = "root"
      , children = 
          SectionSpec(
              name = "sec0"
            , children = 
                InputVariableSpec(name = "var0", description = "" ) ::
                Nil
          ) ::
          SectionSpec(
              name = "sec1"
            , isMultivalued = true
            , children = 
                InputVariableSpec(name = "var1", description = "" ) ::
                SectionSpec(
                    name = "sec2"
                  , children = 
                      InputVariableSpec(name = "var2", description = "" ) ::
                      Nil
                ) ::
                Nil
              
          ) ::
          SectionSpec(
              name = "sec3"
            , children = 
                InputVariableSpec(name = "var3", description = "" ) ::
                SectionSpec(
                    name = "sec4"
                  , isMultivalued = true
                  , children = 
                      InputVariableSpec(name = "var4", description = "" ) ::
                      SectionSpec(
                          name = "sec5"
                        , children = 
                            InputVariableSpec(name = "var5", description = "" ) ::
                            Nil
                      ) ::
                      Nil
                ) ::
                Nil
              
          ) ::
          Nil
    )
    
    /*
     *   sec0 : var0 -> var0A
     *    
     *       sec1             sec3 : var3 = var3A
     *       /  \              |
     *   var1A   var1B        sec4------
     *     /      \           / \       \
     *   var2A    var2B   var4A  var4B  var4C
     *                      /     \       \
     *                  var5A    var5B    var5C
     */
    val mapOK1 = Map(
        "var0" -> Seq("var0A")
      , "var1" -> Seq("var1A", "var1B")
      , "var2" -> Seq("var2A", "var2B")
      , "var3" -> Seq("var3A")
      , "var4" -> Seq("var4A", "var4B", "var4C")
      , "var5" -> Seq("var5A", "var5B", "var5C")
    )
    
    val sectionVal = SectionVal(
        sections  = Map(
            "sec0" -> (
              SectionVal(
                  variables = Map("var0" -> "var0A")
              ) :: Nil
            ) 
         ,  "sec1" -> (
              SectionVal(
                  variables = Map("var1" -> "var1A")
                , sections = Map(
                    "sec2" -> (
                      SectionVal(
                        variables = Map("var2" -> "var2A")
                      ) :: 
                      Nil
                    )
                  )
              ) :: 
              SectionVal(
                  variables = Map("var1" -> "var1B")
                , sections = Map(
                    "sec2" -> (
                      SectionVal(
                        variables = Map("var2" -> "var2B")
                      ) :: 
                      Nil
                    )
                  )
              ) :: 
              Nil
            )
          , "sec3" -> (
              SectionVal(
                  variables = Map("var3" -> "var3A")
                , sections = Map(
                    "sec4" ->  (
                      SectionVal(
                          variables = Map("var4" -> "var4A")
                        , sections = Map(
                            "sec5" -> (
                              SectionVal(
                                  variables = Map("var5" -> "var5A")
                              ) :: Nil
                            )
                          )
                      ) :: 
                      SectionVal(
                          variables = Map("var4" -> "var4B")
                        , sections = Map(
                            "sec5" -> (
                              SectionVal(
                                  variables = Map("var5" -> "var5B")
                              ) :: Nil
                            )
                          )
                      ) :: 
                      SectionVal(
                          variables = Map("var4" -> "var4C")
                        , sections = Map(
                            "sec5" -> (
                              SectionVal(
                                  variables = Map("var5" -> "var5C")
                              ) :: Nil
                            )
                          )
                      ) :: 
                      Nil
                    )
                  )
              ) :: Nil
          )
       )
    )
    
    "be correctly map to a SectionVal" in {
      SectionVal.piValToSectionVal(spec,mapOK1) === sectionVal
    }
    
  }
}