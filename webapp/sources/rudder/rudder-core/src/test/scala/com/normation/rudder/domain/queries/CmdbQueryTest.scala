package com.normation.rudder.domain.queries

import org.junit.runner.RunWith
import org.specs2.mutable._
import org.specs2.runner.JUnitRunner
import net.liftweb.common._

@RunWith(classOf[JUnitRunner])
class CmdbQueryTest extends Specification {

  "French Datecomparator " should {
    "accept valid French date" in {
      DateComparator.validate("23/07/2012", "eq") match {
        case Left(err) => failure(s"Invalid parsing: ${err.fullMsg}")
        case Right(_)  => success
      }
    }

    "refuse an invalid French date" in {
      DateComparator.validate("07/23/2012", "eq") match {
        case Left(_)  => success
        case Right(_) => failure("This american date shouldn't have been accepted")
      }
    }

    "successfully onvert to LDAP a valid French date" in {
      DateComparator.toLDAP("23/07/2012") match {
        case Left(err) => failure(s"Invalid parsing: ${err.fullMsg}")
        case Right(_)  => success
      }
    }
  }
}

