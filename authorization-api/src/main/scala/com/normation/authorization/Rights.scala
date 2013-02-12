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

package com.normation.authorization

/**
 * That class represents a set of AuthorizationType that
 * HAS TO all be validated on the same time. It acts like
 * a new AuthorizationType which melt each AuthorizationType
 * that composed it.
 */
class Rights(_authorizationTypes:AuthorizationType*) {

  require(null != _authorizationTypes && _authorizationTypes.nonEmpty, "At least one AuthorizationType must be include in a Rights object")

  val authorizationTypes = _authorizationTypes.toSet

  override lazy val hashCode = 23 * authorizationTypes.hashCode

  override def equals(other:Any) = other match {
    case that:Rights => this.authorizationTypes == that.authorizationTypes
    case _ => false
  }

}