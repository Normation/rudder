package zio.json

import _root_.enumeratum.*

package object enumeratum {

  def buildDecoder[A <: EnumEntry](cn: Enum[A], parse: String => Either[NoSuchMember[A], A]): JsonDecoder[A] = {
    JsonDecoder.string.mapOrFail { s =>
      parse(s).left.map { e =>
        s"Error decoding enum '${cn.getClass.getSimpleName
            .replaceAll("\\$", "")}': ${e.getMessage} ; values: ${cn.values.mkString("'", "','", "'")}"
      }
    }
  }

  /*
   * Exact codec for EnumEntry
   * It is isomorphic to enum `entryName`, case sensitive
   */
  trait EnumCodecCaseSensitive[A <: EnumEntry] {
    this: Enum[A] =>

    implicit val decoderEnumEntry: JsonDecoder[A] = buildDecoder(this, this.withNameEither)
    implicit val encoderEnumEntry: JsonEncoder[A] = JsonEncoder.string.contramap(_.entryName)
  }

  /*
   * Codec for EnumEntry that:
   *   - encode using exactly `entryName`
   *   - decode case insensitive
   */
  trait EnumCodecCaseInsensitive[A <: EnumEntry] {
    this: Enum[A] =>

    implicit val decoderEnumEntry: JsonDecoder[A] = buildDecoder(this, this.withNameInsensitiveEither)
    implicit val encoderEnumEntry: JsonEncoder[A] = JsonEncoder.string.contramap(_.entryName)
  }

  // by default, encode lc, decode ci
  type EnumCodec[A <: EnumEntry] = EnumCodecCaseInsensitive[A]

}
