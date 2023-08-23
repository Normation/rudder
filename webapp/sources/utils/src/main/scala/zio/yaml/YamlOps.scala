/*
 Copied from zio.json.yaml to change JsonOps.toYaml internals, see inline comments.
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
        // this is the modification compared to io.json.yaml.JsonOps, see https://gist.github.com/fanf/445e9228cd62bb786960ce2242c1020c
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

    def toYamlAST(options: YamlOptions = YamlOptions.default): Node = {
      zio.json.yaml.JsonOps(json).toYamlAST(options)
    }
  }

  implicit final class EncoderYamlOps[A](private val a: A) extends AnyVal {
    def toYaml(options: YamlOptions = YamlOptions.default)(implicit A: JsonEncoder[A]): Either[String, String] =
      a.toJsonAST.flatMap(_.toYaml(options).left.map(_.getMessage))

    def toYamlAST(options: YamlOptions = YamlOptions.default)(implicit A: JsonEncoder[A]): Either[String, Node] =
      a.toJsonAST.map(_.toYamlAST(options))
  }
}
