package com.normation.rudder.domain.queries



import org.junit.runner.RunWith
import org.specs2.mutable._
import org.specs2.matcher._
import org.specs2.runner.JUnitRunner
import net.liftweb.common._
import com.unboundid.ldap.sdk._
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
    "Convert a search request to a valid json request on one attribute " in {
      JsonComparator("attribute").buildFilter("key",Equals,"value") must beEqualTo(SUB("attribute",null,("\"key\":\"value\""::Nil).toArray,null)) 
    }
    
    "Convert a search request to a valid json request on 2 attributes" in {
      JsonComparator("attribute","=").buildFilter("key.key2",Equals,"value=value2") must beEqualTo(SUB("attribute",null,("\"key\":\"value\""::"\"key2\":\"value2\""::Nil).toArray,null)) 
    }
    
    "Convert a search request to a valid json request on one numeric attribute" in {
      JsonComparator("attribute","",true).buildFilter("key",Equals,"1") must beEqualTo(SUB("attribute",null,("\"key\":1"::Nil).toArray,null)) 
    }
  }
}