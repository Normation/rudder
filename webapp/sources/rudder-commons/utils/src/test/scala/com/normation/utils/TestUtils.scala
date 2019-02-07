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

package com.normation.utils

import org.junit._
import org.junit.Assert._
import org.junit.runner.RunWith
import org.junit.runners.BlockJUnit4ClassRunner

import Utils._

@RunWith(classOf[BlockJUnit4ClassRunner])
class TestUtils {

  // String isEmpty

  @Test def nullIsEmpty() { assert(isEmpty(null)) }
  @Test def emptyIsEmpty() { assert(isEmpty("")) }
  @Test def somethingIsNotEmpty() { assert(!isEmpty("foo")) }


  //dereferincing

  class Deref {
    def some : Deref = new Deref
    def none : Deref = null
    def t = true
    def f = false
    def npe = throw new NullPointerException("Don't catch me!")
  }

  @Test
  def safeDeref1() {
    val a = new Deref
    assert(null == ?(a.none.some))
  }

  @Test
  def safeDeref2() {
    val a = new Deref
    assert(null == ?(a.some.none.some))
  }

  @Test
  def safeDeref1_1() {
    val a = new Deref
    assert(null != ?(a.some.some))
  }

  @Test
  def safeDeref2_1() {
    val a = new Deref
    assert(null != ?(a.some.some.some))
  }

  @Test(expected=classOf[NullPointerException])
  def dontCatchOtherNpe() {
    val n = new Deref
    ?(n.npe)
  }

  /*
   * Dereferencing in method parameter
   */


  @Test
  def assignementToNull() {

    val a : String = ?(new String((new Deref).none.toString))
    assert(a == null)
  }


  /*
   * deref option
   */
  @Test
  def safeSomeDeref() {
    val a = new Deref
    assert(None != ??(a.some.some))
  }

  @Test
  def safeNoneDeref() {
    val a = new Deref
    assert(None == ??(a.none.some))
  }

}
