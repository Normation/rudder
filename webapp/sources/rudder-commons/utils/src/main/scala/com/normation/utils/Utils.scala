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

import net.liftweb.common._

/**
 * This is an utility object that
 * provides useful simple methods in a static way
 *
 * Most methods deals with null dereferencing and
 * null/empty Strings management.
 */
object Utils extends Loggable {


  /**
   * Compare two lists of string as if they were two
   * path in a tree (starting with the same root).
   */
  def recTreeStringOrderingCompare(a:List[String], b:List[String]) : Int = {
    (a,b) match {
      case (Nil, Nil) => 0
      case (Nil, _) => -1
      case (_ , Nil) => 1
      case (h1 :: t1 , h2 :: t2) => //lexical order on heads, recurse for tails on same head
         if(h1 == h2) recTreeStringOrderingCompare(t1,t2)
         else String.CASE_INSENSITIVE_ORDER.compare(h1, h2)
    }
  }

  /**
   * Empty test on string, return true is the String is null or "",
   * false otherwise
   */
  def isEmpty(s:String) = if(null == s || "" == s) true else false
}
