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
import com.normation.stringtemplate.language.NormationAmpersandTemplateLexer
import com.normation.zio.currentTimeNanos
import enumeratum.Enum
import enumeratum.EnumEntry
import org.antlr.stringtemplate.StringTemplate
import zio.*
import zio.syntax.*

/*
 * The engine used to fill policy templates (".st" files) during policy generation:
 * - StringTemplate: the historical engine, based on the (mutable, synchronized)
 *   StringTemplate 3 library with the ampersand lexer;
 * - Fastparse: the new in-house engine (see AmpersandTemplate), an immutable AST
 *   parsed with fastparse and rendered as a pure function.
 * Both accept the same template syntax; the choice is done once at service init
 * with the `rudder.policy.template.engine` property in rudder-web.properties.
 */
sealed abstract class PolicyTemplateEngine(override val entryName: String) extends EnumEntry

object PolicyTemplateEngine extends Enum[PolicyTemplateEngine] {
  case object StringTemplate extends PolicyTemplateEngine("string-template")
  case object Fastparse      extends PolicyTemplateEngine("rudder-fastparse")

  val values: IndexedSeq[PolicyTemplateEngine] = findValues

  def default: PolicyTemplateEngine = StringTemplate

  def parse(s: String): PureResult[PolicyTemplateEngine] = {
    withNameInsensitiveOption(s.trim).toRight(
      Inconsistency(
        s"Value '${s}' is not a valid policy template engine, expected values are: ${values.map(_.entryName).mkString("'", "', '", "'")}"
      )
    )
  }
}

/*
 * A template parsed by one of the engines, ready to be filled.
 * BEWARE: the StringTemplate case holds a MUTABLE, NON THREAD SAFE template: a
 * given instance must not be filled concurrently (PolicyWriterService guarantees
 * that by grouping templates by content and filling each group sequentially).
 * The Fastparse case is immutable and free of such constraint.
 */
sealed trait PreparedTemplate
object PreparedTemplate {
  final case class StringTemplateInstance(template: StringTemplate) extends PreparedTemplate
  final case class FastparseTemplate(template: ParsedTemplate)      extends PreparedTemplate
}

/*
 * The service used by policy generation to parse and fill policy templates,
 * one implementation per PolicyTemplateEngine.
 */
trait PolicyTemplateService {
  def engine: PolicyTemplateEngine

  /* parse template content; `name` is only used in error messages */
  def parse(name: String, content: String): IOResult[PreparedTemplate]

  /*
   * Fill the template with the given variables and return (filled content, file name),
   * where the file name is `templateName` with the multi-policy tag substituted when
   * `replaceId` is defined.
   */
  def fill(
      templateName: String,
      template:     PreparedTemplate,
      variables:    Seq[STVariable],
      timer:        FillTemplateTimer,
      replaceId:    Option[(String, String)]
  ): IOResult[(String, String)]

  def clearCache(): IOResult[Unit]
}

class StringTemplatePolicyTemplateService(fillTemplates: FillTemplatesService) extends PolicyTemplateService {
  override val engine: PolicyTemplateEngine = PolicyTemplateEngine.StringTemplate

  override def parse(name: String, content: String): IOResult[PreparedTemplate] = {
    IOResult.attempt(s"Error when trying to parse template '${name}'") {
      PreparedTemplate.StringTemplateInstance(new StringTemplate(content, classOf[NormationAmpersandTemplateLexer]))
    }
  }

  override def fill(
      templateName: String,
      template:     PreparedTemplate,
      variables:    Seq[STVariable],
      timer:        FillTemplateTimer,
      replaceId:    Option[(String, String)]
  ): IOResult[(String, String)] = {
    template match {
      case PreparedTemplate.StringTemplateInstance(st) =>
        FillTemplateThreadUnsafe.fill(templateName, st, variables, timer, replaceId)
      case other                                       =>
        Unexpected(s"Template '${templateName}' was not parsed by the '${engine.entryName}' engine, this is a bug").fail
    }
  }

  override def clearCache(): IOResult[Unit] = fillTemplates.clearCache()
}

class FastparsePolicyTemplateService extends PolicyTemplateService {
  override val engine: PolicyTemplateEngine = PolicyTemplateEngine.Fastparse

  override def parse(name: String, content: String): IOResult[PreparedTemplate] = {
    AmpersandTemplate
      .parse(content)
      .map(PreparedTemplate.FastparseTemplate.apply)
      .toIO
      .chainError(
        s"Error when trying to parse template '${name}'"
      )
  }

  override def fill(
      templateName: String,
      template:     PreparedTemplate,
      variables:    Seq[STVariable],
      timer:        FillTemplateTimer,
      replaceId:    Option[(String, String)]
  ): IOResult[(String, String)] = {
    template match {
      case PreparedTemplate.FastparseTemplate(ast) =>
        (for {
          t0     <- currentTimeNanos
          result <- AmpersandTemplate.fill(ast, templateName, variables, replaceId).toIO
          t1     <- currentTimeNanos
          // rendering is one pure step: variable handling and stringification are not distinguishable
          _      <- timer.fill.update(_ + t1 - t0)
        } yield result).chainError(s"Error with template '${templateName}'").uninterruptible
      case other                                   =>
        Unexpected(s"Template '${templateName}' was not parsed by the '${engine.entryName}' engine, this is a bug").fail
    }
  }

  // the immutable ASTs are parsed per generation by the policy writer, there is no cache to clear
  override def clearCache(): IOResult[Unit] = ZIO.unit
}
