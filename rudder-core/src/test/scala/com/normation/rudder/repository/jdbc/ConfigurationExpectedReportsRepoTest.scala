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

package com.normation.rudder.repository.jdbc


import org.junit.runner._
import org.specs2.mutable._
import org.specs2.runner._
import com.normation.rudder.domain.policies.PolicyInstanceId
import com.normation.rudder.domain.policies.ConfigurationRuleId
import com.normation.rudder.domain.reports._
import org.joda.time.DateTime

@RunWith(classOf[JUnitRunner])
class ConfigurationExpectedReportsRepoTest  extends Specification  {

   val service = new ConfigurationExpectedReportsJdbcRepository(null)
  
   val beginDate = DateTime.now()
   
   private val uniqueMapping = Seq(ExpectedConfRuleMapping(1,
       1,
       new ConfigurationRuleId("cr1"),
       11,
       new PolicyInstanceId("pi1"),
       "component",
       2,
       Seq("None", "None"),
       beginDate))
       
    private val secondMapping = Seq(ExpectedConfRuleMapping(1,
       1,
       new ConfigurationRuleId("cr1"),
       12, // only the serial change
       new PolicyInstanceId("pi1"),
       "component",
       2,
       Seq("None", "None"),
       beginDate))
 
   private val equaledUniqueMapping = new ConfigurationExpectedReports(
       new ConfigurationRuleId("cr1"),
       Seq(new PolicyExpectedReports(new PolicyInstanceId("pi1"),
           						Seq(new ComponentCard("component", 2, Seq("None", "None") ))
           			)),
       11,
       1, 
       Seq(),
       beginDate
   )
   private val multiLine =   buildExpectedConfRule("cr2", Seq("pi1", "pi2"), "comp")
   private val multiComponent =   buildExpectedConfRule("cr3", Seq("pi3"), "comp1") ++
   																buildExpectedConfRule("cr3", Seq("pi3"), "comp2") ++
   																buildExpectedConfRule("cr3", Seq("pi3"), "comp3")
   																
   private val multiPiComponent =   buildExpectedConfRule("cr3", Seq("pi1", "pi2"), "comp1") ++
   																buildExpectedConfRule("cr3", Seq("pi1", "pi4"), "comp2") 
   																
   
   
   private def buildExpectedConfRule(crId : String, piId : Seq[String], component : String) : Seq[ExpectedConfRuleMapping] = {
     piId.map(id => new ExpectedConfRuleMapping(1, 1, new ConfigurationRuleId(crId), 11, new PolicyInstanceId(id), component,1, Seq("None"), beginDate))
   }
   
   
   "a simple line in db" should {
     "generate only one ConfigurationExpectedReports" in {
       service.toConfigurationExpectedReports(uniqueMapping) === Seq(equaledUniqueMapping)
     }
   }
   
   "two lines with differents serial in db" should {
     "generate two ConfigurationExpectedReports" in {
       service.toConfigurationExpectedReports(uniqueMapping ++ secondMapping) must have size(2)
     }
   }
    
   "two simple lines in db, one CR, two PI, one component" should {
     val mapping = service.toConfigurationExpectedReports(multiLine)
     "generate only one ConfigurationExpectedReports" in {
       mapping.size == 1
     }
     "have two  PolicyExpectedReports" in {
       mapping.head.policyExpectedReports.length == 2
     }
     "have a serial of 11" in {
       mapping.head.serial == 11
     }
     "have the expected PI" in {
       mapping.head.policyExpectedReports.map(x => x.policyInstanceId) === Seq(new PolicyInstanceId("pi1"), new PolicyInstanceId("pi2"))
     }    
     
   }
   
   "three simple lines in db, one CR, one PI, three component" should {
     val mapping = service.toConfigurationExpectedReports(multiComponent)
     "generate only one ConfigurationExpectedReports" in {
       mapping.size == 1
     }
     "have one PolicyExpectedReports" in {
       mapping.head.policyExpectedReports.length == 1
     }
     "have a serial of 11" in {
       mapping.head.serial == 11
     }
     "have three component" in {
       mapping.head.policyExpectedReports.head.components.size == 3
     }    
     
   }
   
   "four lines in db, one CR, three PI, two component" should {
     val mapping = service.toConfigurationExpectedReports(multiPiComponent)
     "generate only one ConfigurationExpectedReports" in {
       mapping.size == 1
     }
     "have three PolicyExpectedReports" in {
       mapping.head.policyExpectedReports.length == 3
     }
     "have a serial of 11" in {
       mapping.head.serial == 11
     }
     "have pi1 with two components" in {
       mapping.head.policyExpectedReports.find(x => x.policyInstanceId == new PolicyInstanceId("pi1")).get.components.size == 2
     }  
     "have pi2 with one component" in {
       mapping.head.policyExpectedReports.find(x => x.policyInstanceId == new PolicyInstanceId("pi2")).get.components.size == 1
     } 
     "have pi4 with one component" in {
       mapping.head.policyExpectedReports.find(x => x.policyInstanceId == new PolicyInstanceId("pi4")).get.components.size == 1
     } 
     
   }
   
       
}