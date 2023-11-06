package com.normation.rudder.domain.queries

import org.junit.runner.RunWith
import org.specs2.mutable._
import org.specs2.runner.JUnitRunner

@RunWith(classOf[JUnitRunner])
class CmdbQueryTest extends Specification {

  "Datecomparator " should {
    "refuse date formatted as dd/mm/yyyy" in {
      DateComparator.validate("23/07/2012", "eq") match {
        case Left(_)  => success
        case Right(_) => failure("This french date shouldn't have been accepted")
      }
    }
    "refuse date formatted as mm/dd/yyyy" in {
      DateComparator.validate("12/31/2023", "eq") match {
        case Left(_)  => success
        case Right(_) => failure("This french date shouldn't have been accepted")
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
        case Right(_) => failure("This date shouldn't have been accepted, 34th day of december doesn't exist")
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
