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

/**
 * A service that produce Unique Universal Identifier.
 * The result string HAVE to be not null and non empty,
 * and should conform to the http://tools.ietf.org/html/rfc4122
 * ex: c279f5a4-0738-4b74-a598-c1028108fc83
 */
trait StringUuidGenerator {

  /**
   * Get a fresh new UUID
   * @return the non null, non empty UUID as a string
   */
  def newUuid : String

}


/**
 * Default implementation use Java's UUID
 */
class StringUuidGeneratorImpl extends StringUuidGenerator {
  override def newUuid = java.util.UUID.randomUUID.toString
}