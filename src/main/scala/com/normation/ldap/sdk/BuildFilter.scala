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

import com.unboundid.ldap.sdk.Filter
import Filter._

object BuildFilter {

  /**
   * Create a non filtering filter
   * equivalent to (objectClass=*)
   */
  val ALL : Filter = HAS("objectClass")


  /**
   * Creates a new AND search filter with the provided components.
   *
   * @param  andComponents  The set of filter components to include in the AND
   *                        filter.  It must not be {@code null}.
   *
   * @return  The created AND search filter.
   */
  def AND(andComponents:Filter*) = createANDFilter(andComponents:_*)

  /**
   * Creates a new OR search filter with the provided components.
   *
   * @param  orComponents  The set of filter components to include in the OR
   *                       filter.  It must not be {@code null}.
   *
   * @return  The created OR search filter.
   */
  def OR(orComponents:Filter*) = createORFilter(orComponents:_*)



  /**
   * Creates a new NOT search filter with the provided component.
   *
   * @param  notComponent  The filter component to include in this NOT filter.
   *                       It must not be {@code null}.
   *
   * @return  The created NOT search filter.
   */
  def NOT(notComponent:Filter) = createNOTFilter(notComponent)

  /**
   * Creates a new equality search filter with the provided information.
   *
   * @param  attributeName   The attribute name for this equality filter.  It
   *                         must not be {@code null}.
   * @param  assertionValue  The assertion value for this equality filter.  It
   *                         must not be {@code null}.
   *
   * @return  The created equality search filter.
   */
  def EQ(attributeName:String, assertionValue:String) = createEqualityFilter(attributeName,assertionValue)

  /**
   * Creates a new equality search filter with the provided information.
   *
   * @param  attributeName   The attribute name for this equality filter.  It
   *                         must not be {@code null}.
   * @param  assertionValue  The assertion value for this equality filter.  It
   *                         must not be {@code null}.
   *
   * @return  The created equality search filter.
   */
  def EQ(attributeName:String, assertionValue:Array[Byte]) = createEqualityFilter(attributeName,assertionValue)

  /**
   * A special case of EQ for object class
   */
  def IS(assertionValue:String) = createEqualityFilter("objectClass",assertionValue)

  /**
   * Creates a new substring search filter with the provided information.  At
   * least one of the subInitial, subAny, and subFinal components must not be
   * {@code null}.
   *
   * @param  attributeName  The attribute name for this substring filter.  It
   *                        must not be {@code null}.
   * @param  subInitial     The subInitial component for this substring filter.
   * @param  subAny         The set of subAny components for this substring
   *                        filter.
   * @param  subFinal       The subFinal component for this substring filter.
   *
   * @return  The created substring search filter.
   */
  def SUB(attributeName:String, subInitial:String, subAny:Array[String],subFinal:String) =
    createSubstringFilter(attributeName, subInitial, subAny, subFinal)


  /**
   * Creates a new substring search filter with the provided information.  At
   * least one of the subInitial, subAny, and subFinal components must not be
   * {@code null}.
   *
   * @param  attributeName  The attribute name for this substring filter.  It
   *                        must not be {@code null}.
   * @param  subInitial     The subInitial component for this substring filter.
   * @param  subAny         The set of subAny components for this substring
   *                        filter.
   * @param  subFinal       The subFinal component for this substring filter.
   *
   * @return  The created substring search filter.
   */
  def SUB(attributeName:String, subInitial:Array[Byte], subAny:Array[Array[Byte]],subFinal:Array[Byte]) =
    createSubstringFilter(attributeName, subInitial, subAny, subFinal)


  /**
   * Creates a new greater-or-equal search filter with the provided information.
   *
   * @param  attributeName   The attribute name for this greater-or-equal
   *                         filter.  It must not be {@code null}.
   * @param  assertionValue  The assertion value for this greater-or-equal
   *                         filter.  It must not be {@code null}.
   *
   * @return  The created greater-or-equal search filter.
   */
  def GTEQ(attributeName:String, assertionValue:String) = createGreaterOrEqualFilter(attributeName,assertionValue)

  /**
   * Creates a new greater-or-equal search filter with the provided information.
   *
   * @param  attributeName   The attribute name for this greater-or-equal
   *                         filter.  It must not be {@code null}.
   * @param  assertionValue  The assertion value for this greater-or-equal
   *                         filter.  It must not be {@code null}.
   *
   * @return  The created greater-or-equal search filter.
   */
  def GTEQ(attributeName:String, assertionValue:Array[Byte]) = createGreaterOrEqualFilter(attributeName,assertionValue)

  /**
   * Creates a new less-or-equal search filter with the provided information.
   *
   * @param  attributeName   The attribute name for this less-or-equal
   *                         filter.  It must not be {@code null}.
   * @param  assertionValue  The assertion value for this less-or-equal
   *                         filter.  It must not be {@code null}.
   *
   * @return  The created less-or-equal search filter.
   */
  def LTEQ(attributeName:String, assertionValue:String) = createLessOrEqualFilter(attributeName,assertionValue)

  /**
   * Creates a new less-or-equal search filter with the provided information.
   *
   * @param  attributeName   The attribute name for this less-or-equal
   *                         filter.  It must not be {@code null}.
   * @param  assertionValue  The assertion value for this less-or-equal
   *                         filter.  It must not be {@code null}.
   *
   * @return  The created less-or-equal search filter.
   */
  def LTEQ(attributeName:String, assertionValue:Array[Byte]) = createLessOrEqualFilter(attributeName,assertionValue)

  /**
   * Creates a new presence search filter with the provided information.
   *
   * @param  attributeName   The attribute name for this presence filter.  It
   *                         must not be {@code null}.
   *
   * @return  The created presence search filter.
   */
  def HAS(attributeName:String) = createPresenceFilter(attributeName)


  /**
   * Creates a new approximate match search filter with the provided
   * information.
   *
   * @param  attributeName   The attribute name for this approximate match
   *                         filter.  It must not be {@code null}.
   * @param  assertionValue  The assertion value for this approximate match
   *                         filter.  It must not be {@code null}.
   *
   * @return  The created approximate match search filter.
   */
  def MATCH(attributeName:String, assertionValue:String) = createApproximateMatchFilter(attributeName,assertionValue)

  /**
   * Creates a new approximate match search filter with the provided
   * information.
   *
   * @param  attributeName   The attribute name for this approximate match
   *                         filter.  It must not be {@code null}.
   * @param  assertionValue  The assertion value for this approximate match
   *                         filter.  It must not be {@code null}.
   *
   * @return  The created approximate match search filter.
   */
  def MATCH(attributeName:String, assertionValue:Array[Byte]) = createApproximateMatchFilter(attributeName,assertionValue)

  /**
   * Creates a new extensible match search filter with the provided
   * information.  At least one of the attribute name and matching rule ID must
   * be specified, and the assertion value must always be present.
   *
   * @param  attributeName   The attribute name for this extensible match
   *                         filter.
   * @param  matchingRuleID  The matching rule ID for this extensible match
   *                         filter.
   * @param  dnAttributes    Indicates whether the match should be performed
   *                         against attributes in the target entry's DN.
   * @param  assertionValue  The assertion value for this extensible match
   *                         filter.  It must not be {@code null}.
   *
   * @return  The created extensible match search filter.
   */
  def MATCHEX(attributeName:String, matchingRuleID:String, dnAttributes:Boolean, assertionValue:Array[Byte]) =
    createExtensibleMatchFilter(attributeName, matchingRuleID, dnAttributes, assertionValue)

  /**
   * Creates a new search filter from the provided string representation.
   *
   * @param  filterString  The string representation of the filter to create.
   *                       It must not be {@code null}.
   *
   * @return  The search filter decoded from the provided filter string.
   *
   * @throws  LDAPException  If the provided string cannot be decoded as a valid
   *                         LDAP search filter.
   */
  def apply(filterString:String) = create(filterString)

}
