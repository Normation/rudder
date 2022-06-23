package com.normation.rudder.rest

import org.junit.runner.RunWith
import org.specs2.mutable.Specification
import org.specs2.runner.JUnitRunner

class PropertiesApiTest extends Specification {
  sequential
  isolated

  val restTestSetUp = RestTestSetUp.ne
  val restTest = new RestTest(restTestSetUp.)

}
