package org.json4s.other

import org.json4s.*
import org.json4s.native.Document
import org.json4s.native.Document.*
import org.json4s.native.JsonMethods.*
import org.json4s.prefs.EmptyValueStrategy

object JsonUtils {

  extension (json: JValue) {
    def compactRender: String = compact(ParserUtils.render(json))
    def prettyRender:  String = pretty(ParserUtils.render(json))
  }

  private object ParserUtils {

    private[json4s] def quote[T <: java.lang.Appendable](s: String, appender: T, alwaysEscapeUnicode: Boolean): T = { // hot path
      var i = 0
      val l = s.length
      while (i < l) {
        (s(i)) match {
          case '"'  => appender.append("\\\"")
          case '\\' => appender.append("\\\\")
          case '\b' => appender.append("\\b")
          case '\f' => appender.append("\\f")
          case '\n' => appender.append("\\n")
          case '\r' => appender.append("\\r")
          case '\t' => appender.append("\\t")
          case c    =>
            val shouldEscape = if (alwaysEscapeUnicode) {
              c >= 0x80
            } else {
              (c >= '\u0000' && c <= '\u001f') || (c >= '\u0080' && c < '\u00a0') || (c >= '\u2000' && c < '\u2010')
            }
            if (shouldEscape)
              appender.append("\\u%04X".format(c: Int))
            else appender.append(c)
        }
        i += 1
      }
      appender
    }

    def render(
        value:               JValue,
        alwaysEscapeUnicode: Boolean = false,
        emptyValueStrategy:  EmptyValueStrategy = EmptyValueStrategy.default
    ): Document = {
      emptyValueStrategy.replaceEmpty(value) match {
        case null          => text("null")
        case JBool(true)   => text("true")
        case JBool(false)  => text("false")
        case JDouble(n)    => text(StreamingJsonWriter.handleInfinity(n))
        case JDecimal(n)   => text(n.toString)
        case JLong(n)      => text(n.toString)
        case JInt(n)       => text(n.toString)
        case JNull         => text("null")
        case JNothing      => sys.error("can't render 'nothing'")
        case JString(null) => text("null")
        case JString(s)    => text("\"" + quote(s, alwaysEscapeUnicode) + "\"")
        case JArray(arr)   =>
          text("[") :: series(trimArr(arr).map(render(_, alwaysEscapeUnicode, emptyValueStrategy))) :: text("]")
        case JSet(set)     =>
          text("[") :: series(trimArr(set).map(render(_, alwaysEscapeUnicode, emptyValueStrategy))) :: text("]")
        case JObject(obj)  =>
          if (obj.isEmpty) {
            text("{") :: text("}")
          } else {
            val nested = break :: fields(trimObj(obj).map {
              case (n, v) =>
                text("\"" + quote(n, alwaysEscapeUnicode) + "\":") :: render(
                  v,
                  alwaysEscapeUnicode,
                  emptyValueStrategy
                )
            })
            text("{") :: nest(2, nested) :: break :: text("}")
          }
      }
    }

    private def trimArr(xs: Iterable[JValue]) = xs.withFilter(_ != JNothing)

    private def trimObj(xs: List[JField]) = xs.filter(_._2 != JNothing)

    private def series(docs: Iterable[Document]) = punctuate(text(","), docs)

    private def fields(docs: List[Document]) = punctuate(text(",") :: break, docs)

    private def punctuate(p: Document, docs: Iterable[Document]): Document = {
      if (docs.isEmpty) empty
      else docs.reduceLeft((d1, d2) => d1 :: p :: d2)
    }

    def quote(s: String, alwaysEscapeUnicode: Boolean): String = {
      quote(
        s = s,
        appender = new java.lang.StringBuilder,
        alwaysEscapeUnicode = alwaysEscapeUnicode
      ).toString
    }

  }
}
