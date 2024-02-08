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

import Control.traverse
import net.liftweb.common._
import org.junit._
import org.junit.runner.RunWith
import org.junit.runners.BlockJUnit4ClassRunner

@RunWith(classOf[BlockJUnit4ClassRunner])
class TestControl {

  def msg(i: Int): String    = s"failed for ${i}"
  val l:           List[Int] = List(1, 2, 3, 4, 5, 6, 7, 8, 9)

  @Test
  def stopSequenceInOrder(): Unit = {
    val res = traverse(l)(i => if (i % 4 == 0) Failure(msg(i)) else Full(i))
    assert(res == Failure(msg(4)), "Non parallele sequence traversal should process element in order and failed on first error")
  }

  @Test
  def validSequenceKeepOrder(): Unit = {
    val res = traverse(l)(i => Full(i + 10))
    l.zip(res.openOrThrowException("Should succeed!")).foreach {
      case (i, j) => assert(i + 10 == j, s"Non parallele sequence traversal should keep order, but ${i}+10 != ${j}")
    }
  }

}
