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


import java.security.Principal

/**
 * This class define the main method to interact with
 * authorization questions.
 * Methods allow to answer to questions like:
 * "Does PRINCIPAL have RIGHTS on TARGETS"
 *
 */
trait AuthorizationService {

  /**
   * Check if the given principal has all the rights in rights on the given target
   * @param principal
   *   the principal for whom the authorization has to be perform
   * @param rights
   *   the set of AuthorizationType to check
   * @param target
   *   the target on which we want to check rights for principal
   * @return false if any AuthorizationType in rights is missing for principal on target.
   */
  def isAllowed(principal:Principal, right: AuthorizationType, target:String) : Boolean

  /**
   * Check on what target from the list principal has rights.
   *
   * @param principal
   *   the principal for whom the authorization has to be perform
   * @param rights
   *   the set of AuthorizationType to check
   * @param target
   *   the list of targets on which we want to check rights for principal
   * @return the list of targets from targets parameter on which principal has rights, or
   *         empty collection if principal has rights on zero target.
   */
  def isAllowed(principal:Principal, rights: AuthorizationType, targets:String*) : Traversable[String]


  /**
   * Check if the given principal has all the rights in rights on the given target
   * @param principal
   *   the principal for whom the authorization has to be perform
   * @param rights
   *   the set of AuthorizationType to check
   * @param target
   *   the target on which we want to check rights for principal
   * @return false if any AuthorizationType in rights is missing for principal on target.
   */
  def isAllowed(principal:Principal, rights: Rights, target:String) : Boolean

  /**
   * Check on what target from the list principal has rights.
   *
   * @param principal
   *   the principal for whom the authorization has to be perform
   * @param rights
   *   the set of AuthorizationType to check
   * @param target
   *   the list of targets on which we want to check rights for principal
   * @return the list of targets from targets parameter on which principal has rights, or
   *         empty collection if principal has rights on zero target.
   */
  def isAllowed(principal:Principal, rights: Rights, targets:String*) : Traversable[String]
}