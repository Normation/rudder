/*
*************************************************************************************
* Copyright 2011 Normation SAS
*************************************************************************************
*
* Licensed under the Apache License, Version 2.0 (the "License");
* you may not use this file except in compliance with the License.
* You may obtain a copy of the License at
*
* http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.
*
*************************************************************************************
*/

package com.normation.ldap.sdk
package schema

import org.junit._
import org.junit.Assert._
import org.junit.runner.RunWith
import org.junit.runners.BlockJUnit4ClassRunner

@RunWith(classOf[BlockJUnit4ClassRunner])
class SchemaTest {

  val OC = new LDAPSchema()
  /*
   * Hierachy :
   * top
   *  |- L1_0
   *  |    |- L1_0_0
   *  |    |    `- L1_0_0_0
   *  |    |- L1_0_1
   *  |    `- L1_0_2
   *  |- L1_1
   *  `- L1_2
   *       `- L1_2_0
   */
  OC.+=("L1_0").+=("L1_1").+=("L1_2").
     +=("L1_0_0", sup = OC("L1_0")).+=("L1_0_1", sup = OC("L1_0")).+=("L1_0_2", sup = OC("L1_0")).
     +=("L1_0_0_0", sup =  OC("L1_0_0")).
     +=("L1_2_0", sup =  OC("L1_2"))

  @Test def demuxTest() {

    assertEquals(Set(), OC.demux())

    //top
    assertEquals(
        Set(OC("TOP")),
        OC.demux("top"))

    //same branch, only top
    assertEquals(
        Set(OC("L1_1")),
        OC.demux("l1_1","top"))

    //several time the same input
    assertEquals(
        Set(OC("L1_1")),
        OC.demux("l1_1","top","l1_1","top","l1_1"))

    //same branch, different levels
    assertEquals(
        Set(OC("L1_0_0_0")),
        OC.demux("top", "L1_0_0_0","L1_0","L1_0_0"))

    //several branches, nothing in common safe top
    assertEquals(
        Set(OC("L1_0"),OC("L1_1"),OC("L1_2")),
        OC.demux("top", "L1_0","L1_1","L1_2"))

    //several branches, several shared levels
    assertEquals(
        Set(OC("L1_0_0_0"),OC("L1_0_1"),OC("L1_0_2"),OC("L1_1"),OC("L1_2_0"),OC("L1_0_2")),
        OC.demux("top", "L1_0","L1_1","L1_2","L1_0_0","L1_0_1","L1_0_2","L1_2_0","L1_0_0_0"))
  }
}
