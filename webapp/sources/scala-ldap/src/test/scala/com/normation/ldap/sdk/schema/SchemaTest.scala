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

import org.junit.runner.RunWith
import org.specs2.mutable._
import org.specs2.runner.JUnitRunner

@RunWith(classOf[JUnitRunner])
class SchemaTest extends Specification {

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
  OC.createObjectClass("L1_0")
  OC.createObjectClass("L1_1")
  OC.createObjectClass("L1_2")
  OC.createObjectClass("L1_0_0", sup = OC("L1_0"))
  OC.createObjectClass("L1_0_1", sup = OC("L1_0"))
  OC.createObjectClass("L1_0_2", sup = OC("L1_0"))
  OC.createObjectClass("L1_0_0_0", sup = OC("L1_0_0"))
  OC.createObjectClass("L1_2_0", sup = OC("L1_2"))

  "top" >> (
    (OC.demux() must beEmpty) and
    // top
    (OC.demux("top") must containTheSameElementsAs(Seq(OC("TOP"))))
  )

  "same branch, only top" >>
  (OC.demux("l1_1", "top") must containTheSameElementsAs(Seq(OC("L1_1"))))

  "several time the same input" >>
  (OC.demux("l1_1", "top", "l1_1", "top", "l1_1") must containTheSameElementsAs(Seq(OC("L1_1"))))

  "same branch, different levels" >>
  (OC.demux("top", "L1_0_0_0", "L1_0", "L1_0_0") must containTheSameElementsAs(Seq(OC("L1_0_0_0"))))

  "several branches, nothing in common safe top" >>
  (OC.demux("top", "L1_0", "L1_1", "L1_2") must containTheSameElementsAs(Seq(OC("L1_0"), OC("L1_1"), OC("L1_2"))))

  "several branches, several shared levels" >>
  (OC.demux("top", "L1_0", "L1_1", "L1_2", "L1_0_0", "L1_0_1", "L1_0_2", "L1_2_0", "L1_0_0_0") must
  containTheSameElementsAs(Seq(OC("L1_0_0_0"), OC("L1_0_1"), OC("L1_0_2"), OC("L1_1"), OC("L1_2_0"))))
}
