/*
 *************************************************************************************
 * Copyright 2026 Normation SAS
 *************************************************************************************
 *
 * This file is part of Rudder.
 *
 * Rudder is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * In accordance with the terms of section 7 (7. Additional Terms.) of
 * the GNU General Public License version 3, the copyright holders add
 * the following Additional permissions:
 * Notwithstanding to the terms of section 5 (5. Conveying Modified Source
 * Versions) and 6 (6. Conveying Non-Source Forms.) of the GNU General
 * Public License version 3, when you create a Related Module, this
 * Related Module is not considered as a part of the work and may be
 * distributed under the license agreement of your choice.
 * A "Related Module" means a set of sources files including their
 * documentation that, without modification of the Source Code, enables
 * supplementary functions or services in addition to those offered by
 * the Software.
 *
 * Rudder is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Rudder.  If not, see <http://www.gnu.org/licenses/>.

 *
 *************************************************************************************
 */

package com.normation.templates

import com.normation.errors.*
import com.normation.zio.currentTimeNanos
import scala.collection.immutable.ArraySeq
import zio.*

/**
 * A variable to replace in policy templates:
 * - name: the name referenced in the template;
 * - mayBeEmpty: an empty value list is allowed (else filling the template fails);
 * - values: one value is a scalar, several values are a list;
 * - isSystem: a system variable that may be empty and is empty (or a single "")
 *   is absent from the template scope.
 */
final case class STVariable(
    name:       String,
    mayBeEmpty: Boolean,
    values:     ArraySeq[Any],
    isSystem:   Boolean
)

/*
 * Accumulated time (in nanoseconds) spent parsing templates and filling them.
 */
final case class FillTemplateTimer(
    parse: Ref[Long],
    fill:  Ref[Long]
)

object FillTemplateTimer {
  def make(): ZIO[Any, Nothing, FillTemplateTimer] = for {
    a <- Ref.make(0L)
    b <- Ref.make(0L)
  } yield FillTemplateTimer(a, b)
}

/*
 * The service used by policy generation to parse and fill policy templates
 * (".st" files). A parsed template is immutable and can be filled concurrently.
 */
trait PolicyTemplateService {

  /* parse template content; `name` is only used in error messages */
  def parse(name: String, content: String): IOResult[ParsedTemplate]

  /*
   * Fill the template with the given variables and return (filled content, file name),
   * where the file name is `templateName` with the multi-policy tag substituted when
   * `replaceId` is defined.
   */
  def fill(
      templateName: String,
      template:     ParsedTemplate,
      variables:    Seq[STVariable],
      timer:        FillTemplateTimer,
      replaceId:    Option[(String, String)]
  ): IOResult[(String, String)]
}

class FastparsePolicyTemplateService extends PolicyTemplateService {

  override def parse(name: String, content: String): IOResult[ParsedTemplate] = {
    FastparseTemplateParser.parse(content).toIO.chainError(s"Error when trying to parse template '${name}'")
  }

  override def fill(
      templateName: String,
      template:     ParsedTemplate,
      variables:    Seq[STVariable],
      timer:        FillTemplateTimer,
      replaceId:    Option[(String, String)]
  ): IOResult[(String, String)] = {
    (for {
      t0     <- currentTimeNanos
      result <- FastparseTemplateParser.fill(template, templateName, variables, replaceId).toIO
      t1     <- currentTimeNanos
      _      <- timer.fill.update(_ + t1 - t0)
    } yield result).chainError(s"Error with template '${templateName}'")
  }
}
