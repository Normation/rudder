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
 * Base class for Authorization types. 
 * Common types are read, write, etc but the list is
 * open and implementations are free to add their semantic.
 *
 */
trait AuthorizationType {

  /**
   * A string identifier of that authorization type
   * which may be use to transform string to that 
   * type.
   */
  val id : String
  
}


/**
 * Represent the right to delete the target. 
 * Semantic is let to AuthorizationService implementation,
 * but common example are: 
 * delete a file in a file system, 
 * destroy an entry in a database, 
 * allow to start a "fire employee" work flow. 
 */
case object Delete extends AuthorizationType { val id = "DELETE" }

/**
 * Represent the right to read the target.
 * Semantic is let to AuthorizationService implementation,
 * but common example are: 
 * read the content of file on file system,
 * access internal properties of an object, 
 * display something in a user interface. 
 */
case object Read extends AuthorizationType { val id = "READ" }

/**
 * Represent the right to write the target.
 * Semantic is let to AuthorizationService implementation,
 * but common example are: 
 * modify some properties of an object, 
 * allows user input in a form. 
 */
case object Write extends AuthorizationType { val id = "WRITE" }

/**
 * Represent the right to search for or from
 * the target. 
 * Semantic is let to AuthorizationService implementation,
 * but common example are: 
 * return the target in a search result set, allow to use
 * the target has a base path for a search request. 
 */
case object Search extends AuthorizationType { val id = "SEARCH" }

/**
 * Represent the right to create the target, or
 * let children be created under the target. 
 * Semantic is let to AuthorizationService implementation,
 * but common example are: 
 * allow target type of object to be created, 
 * in a file system allows file to be created under target
 * directory
 */
case object Create extends AuthorizationType { val id = "CREATE" }
