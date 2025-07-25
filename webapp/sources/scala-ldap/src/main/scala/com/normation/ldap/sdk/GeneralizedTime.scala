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

import com.normation.utils.DateFormaterService
import com.unboundid.util.StaticUtils
import java.text.ParseException
import java.time.Instant
import org.joda.time.DateTime

/**
 * Wrapping class around DateTime that
 * print/parse GeneralizedTime format
 * as described in
 * http://www.ietf.org/rfc/rfc4517.txt
 * (it's an ISO 8601 but with a missing 'T'
 * http://en.wikipedia.org/wiki/ISO_8601)
 *
 */
final case class GeneralizedTime(val instant: Instant) extends AnyVal {

  /**
   * Print the string into a well formed generalize time format.
   */
  override def toString(): String = StaticUtils.encodeGeneralizedTime(instant.toEpochMilli)
}

object GeneralizedTime {

  def apply(dt: DateTime): GeneralizedTime = {
    new GeneralizedTime(DateFormaterService.toInstant(dt))
  }

  /**
   * Try to parse the given string into a GeneralizedTime.
   *
   * @return
   */
  @throws(classOf[ParseException])
  def apply(s: String): GeneralizedTime = {
    new GeneralizedTime(StaticUtils.decodeGeneralizedTime(s).toInstant)
  }

  /**
   * Parse the given string into a generalized time.
   * Return None if the string is not a well-formed
   * generalized time.
   * @param s
   *    The string to parse as a generalize time
   *
   */
  def parse(s: String): Option[GeneralizedTime] = {
    try {
      Some(apply(s))
    } catch {
      case e: IllegalArgumentException => None
    }
  }
}
