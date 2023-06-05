/*
 *************************************************************************************
 * Copyright 2023 Normation SAS
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

package zio.yaml

import java.io.StringWriter
import java.nio.charset.StandardCharsets
import java.util.Base64
import org.yaml.snakeyaml.DumperOptions
import org.yaml.snakeyaml.DumperOptions.NonPrintableStyle
import org.yaml.snakeyaml.DumperOptions.ScalarStyle
import org.yaml.snakeyaml.emitter.Emitter
import org.yaml.snakeyaml.error.YAMLException
import org.yaml.snakeyaml.nodes.MappingNode
import org.yaml.snakeyaml.nodes.Node
import org.yaml.snakeyaml.nodes.NodeTuple
import org.yaml.snakeyaml.nodes.ScalarNode
import org.yaml.snakeyaml.nodes.SequenceNode
import org.yaml.snakeyaml.nodes.Tag
import org.yaml.snakeyaml.reader.StreamReader
import org.yaml.snakeyaml.resolver.Resolver
import org.yaml.snakeyaml.serializer.Serializer
import scala.util.matching.Regex
import zio.json.ast.Json
import zio.json.yaml.YamlOptions

object YamlOps {

  import zio.json._
  implicit final class EncoderYamlOps[A](private val a: A) extends AnyVal {
    def toYaml(options: YamlOptions = YamlOptions.default)(implicit A: JsonEncoder[A]): Either[String, String] =
      a.toJsonAST.flatMap(_.toYaml(options).left.map(_.getMessage))

    def toYamlAST(options: YamlOptions = YamlOptions.default)(implicit A: JsonEncoder[A]): Either[String, Node] =
      a.toJsonAST.map(_.toYamlAST(options))
  }

  import scala.jdk.CollectionConverters._

  final private def jsonToYaml(json: Json, options: YamlOptions): Node = {
    json match {
      case Json.Obj(fields)   =>
        val finalFields = {
          if (options.dropNulls) {
            fields.filter {
              case (_, value) =>
                value match {
                  case Json.Null => false
                  case _         => true
                }
            }
          } else {
            fields
          }
        }
        new MappingNode(
          Tag.MAP,
          finalFields.map {
            case (key, value) =>
              new NodeTuple(
                new ScalarNode(Tag.STR, key, null, null, options.keyStyle(key)),
                jsonToYaml(value, options)
              )
          }.toList.asJava,
          options.flowStyle(json)
        )
      case Json.Arr(elements) =>
        new SequenceNode(
          Tag.SEQ,
          elements.map(jsonToYaml(_, options)).toList.asJava,
          options.flowStyle(json)
        )
      case Json.Bool(value)   =>
        new ScalarNode(Tag.BOOL, value.toString, null, null, options.scalarStyle(json))
      case Json.Str(value)    =>
        if (options.nonPrintableStyle == NonPrintableStyle.BINARY && !StreamReader.isPrintable(value)) {
          new ScalarNode(
            Tag.BINARY,
            Base64.getEncoder.encodeToString(value.getBytes(StandardCharsets.UTF_8)),
            null,
            null,
            ScalarStyle.LITERAL
          )
        } else {

          val multiline: Regex = "[\n\u0085\u2028\u2029]".r
          val isMultiLine = multiline.findFirstIn(value).isDefined
          val style       = options.scalarStyle(json)
          val finalStyle  = if (style == ScalarStyle.PLAIN && isMultiLine) ScalarStyle.LITERAL else style
          new ScalarNode(Tag.STR, value, null, null, finalStyle)
        }
      case Json.Num(value)    =>
        val stripped = value.stripTrailingZeros()
        if (stripped.scale() <= 0) {
          new ScalarNode(Tag.INT, stripped.intValue().toString, null, null, options.scalarStyle(json))
        } else {
          new ScalarNode(Tag.FLOAT, stripped.toString, null, null, options.scalarStyle(json))
        }
      case Json.Null          =>
        new ScalarNode(Tag.NULL, "null", null, null, options.scalarStyle(json))
    }
  }

  implicit final class JsonOps(private val json: Json) extends AnyVal {
    def toYaml(options: YamlOptions = YamlOptions.default): Either[YAMLException, String] = {
      val yamlNode = toYamlAST(options)

      try {
        val dumperOptions = new DumperOptions()
        dumperOptions.setIndent(options.indentation)
        dumperOptions.setIndicatorIndent(options.sequenceIndentation)
        options.maxScalarWidth match {
          case Some(width) =>
            dumperOptions.setWidth(width)
            dumperOptions.setSplitLines(true)
          case None        =>
            dumperOptions.setSplitLines(false)
        }
        dumperOptions.setLineBreak(options.lineBreak)
        dumperOptions.setIndentWithIndicator(true)

        val resolver   = new Resolver
        val output     = new StringWriter()
        val serializer = new Serializer(new Emitter(output, dumperOptions), resolver, dumperOptions, yamlNode.getTag)
        serializer.open()
        try {
          serializer.serialize(yamlNode)
        } finally {
          serializer.close()
        }

        Right(output.toString)
      } catch {
        case error: YAMLException => Left(error)
      }
    }

    def toYamlAST(options: YamlOptions = YamlOptions.default): Node = jsonToYaml(json, options)
  }

}
