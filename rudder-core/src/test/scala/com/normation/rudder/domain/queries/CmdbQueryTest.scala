package com.normation.rudder.domain.queries

import org.junit.runner.RunWith
import org.specs2.mutable._
import org.specs2.runner.JUnitRunner
import net.liftweb.common._
import com.normation.ldap.sdk._
import BuildFilter._

@RunWith(classOf[JUnitRunner])
class CmdbQueryTest extends Specification {

  "French Datecomparator " should {
    "accept valid French date" in {
      DateComparator.validate("23/07/2012", "eq") match {
        case e:EmptyBox =>failure("Invalid parsing")
        case Full(_) => success
      }
    }

    "refuse an invalid French date" in {
      DateComparator.validate("07/23/2012", "eq") match {
        case e:EmptyBox => success
        case Full(_) => failure("This american date shouldn't have been accepted")
      }
    }

    "successfully onvert to LDAP a valid French date" in {
      DateComparator.toLDAP("23/07/2012") match {
        case e:EmptyBox =>failure("Invalid parsing")
        case Full(_) => success
      }
    }
  }
}

@RunWith(classOf[JUnitRunner])
class JsonQueryTest extends Specification {

  "JsonComparator " should {
    "Convert a search request to a valid json request on 2 attributes" in {
      NodePropertyComparator("attribute").buildFilter("name.value",Equals,"value=value2") must beEqualTo(
        OR( SUB("attribute","{\"name\":\"value\",\"value\":\"value2\"",null,null)
          , SUB("attribute","{\"name\":\"value\",\"value\":value2"    ,null,null)
        )
      )
    }
  }
}
