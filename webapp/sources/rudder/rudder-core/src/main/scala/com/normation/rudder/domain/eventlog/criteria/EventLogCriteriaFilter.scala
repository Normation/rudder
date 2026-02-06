package com.normation.rudder.domain.eventlog.criteria

import cats.data.NonEmptyList
import enumeratum.Enum
import enumeratum.EnumEntry.Lowercase
import enumeratum.EnumEntry.Uppercase
import java.time.Instant

case class EventLogCriteriaFilter(
    start:     Int,
    length:    Int,
    search:    Option[EventLogCriteriaFilter.Search],
    startDate: Option[Instant],
    endDate:   Option[Instant],
    principal: Option[EventLogCriteriaFilter.PrincipalFilter],
    order:     Option[EventLogCriteriaFilter.Order]
)

object EventLogCriteriaFilter {

  final case class Actor(include: Option[List[String]], exclude: Option[List[String]])
  final case class Search(value: String)
  final case class Order(column: Column, dir: Direction)
  final case class PrincipalFilter(include: Option[NonEmptyList[Principal]], exclude: Option[NonEmptyList[Principal]])
  final case class Principal(value: String)

  sealed abstract class Column(val id: Int) extends Lowercase
  object Column                             extends Enum[Column] {
    case object ID           extends Column(0)
    case object CreationDate extends Column(1)
    case object Principal    extends Column(2)
    case object EventType    extends Column(3)

    override def values: IndexedSeq[Column] = findValues

    def fromId(id: Int): Either[String, Column] = {
      values
        .find(_.id == id)
        .toRight(s"Not a valid column id : ${id}, columns are ${values.map(c => s"${c.id}=${c.entryName}").mkString(",")}")
    }
  }

  sealed trait Direction extends Uppercase

  object Direction extends Enum[Direction] {
    case object Desc extends Direction
    case object Asc  extends Direction

    override def values: IndexedSeq[Direction] = findValues

    def parse(s: String): Either[String, Direction] =
      withNameInsensitiveEither(s).left.map(e => s"not a valid sorting order: ${e.notFoundName}")
  }
}
