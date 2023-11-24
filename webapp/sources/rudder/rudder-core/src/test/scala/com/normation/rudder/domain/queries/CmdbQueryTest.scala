package com.normation.rudder.domain.queries

import org.junit.runner.RunWith
import org.specs2.mutable._
import org.specs2.runner.JUnitRunner

@RunWith(classOf[JUnitRunner])
class CmdbQueryTest extends Specification {

  "Converting date format" should {
    "successfully convert yyyy/MM/dd to dd/MM/yyyy" in {
      DateComparator.dateConverter("yyyy/MM/dd", "dd/MM/yyyy", "2023/12/31") match {
        case Left(_)     => failure("This date should have been accepted")
        case Right(date) =>
          if (date == "31/12/2023") success
          else failure(s"This date should have been converted to '2023/12/31' but get '${date}'")
      }

      "successfully convert dd/MM/yyyy to yyyy/MM/dd" in {
        DateComparator.dateConverter("dd/MM/yyy", "yyyy/MM/dd", "31/12/2023") match {
          case Left(_)     => failure("This date should have been accepted")
          case Right(date) =>
            if (date == "2023/12/31") success
            else failure(s"This date should have been converted to '2023/12/31' but get '${date}'")
        }
      }

      "failed to convert to invalid format" in {
        DateComparator.dateConverter("uuu/ggg/deee", "dd/MM/yyyy", "31/12/2023") match {
          case Left(_)  => success
          case Right(_) => failure(s"This format shouldn't be accepted")
        }
      }

    }
  }
  "Datecomparator " should {
    "refuse date formatted as dd/mm/yyyy" in {
      DateComparator.validate("23/07/2012", "eq") match {
        case Left(_)  => success
        case Right(_) => failure("This french date should have been accepted")
      }
    }
    "refuse date formatted as mm/dd/yyyy" in {
      DateComparator.validate("12/31/2023", "eq") match {
        case Left(_)  => success
        case Right(_) => failure("This french date should have been accepted")
      }
    }
    "accept date formatted as yyyy/mm/dd" in {
      DateComparator.validate("2023/12/31", "eq") match {
        case Left(err) => failure(s"Invalid date format ${err.fullMsg}")
        case Right(_)  => success
      }
    }
    "refuse an invalid date with wrong day" in {
      DateComparator.validate("2023/12/34", "eq") match {
        case Left(_)  => success
        case Right(d) => failure("This date shouldn't have been accepted, 34th day of december doesn't exist")
      }
    }
    "refuse an invalid date with wrong month" in {
      DateComparator.validate("2023/21/31", "eq") match {
        case Left(_)  => success
        case Right(_) => failure("This date shouldn't have been accepted, 21th month doesn't exist")
      }
    }
    "successfully convert to LDAP a valid date" in {
      DateComparator.toLDAP("2023/12/31") match {
        case Left(err) => failure(s"Invalid parsing: ${err.fullMsg}")
        case Right(_)  => success
      }
    }
  }
}
