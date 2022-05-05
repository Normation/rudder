//package com.normation.rudder
//
//import com.normation.rudder.apidata.JsonResponseObjects.JRRuleTarget
//import com.normation.rudder.domain.policies.DirectiveId
//import com.normation.rudder.domain.policies.RuleId
//import com.normation.rudder.domain.policies.Tags
//import com.normation.rudder.rest.RestTestSetUp
//import com.normation.rudder.rest.lift.RuleApiService14
//import net.liftweb.common.Loggable
//import org.junit.runner.RunWith
//import org.specs2.mutable.Specification
//import org.specs2.runner.JUnitRunner
//import org.specs2.specification.AfterAll
//import specs2.arguments.isolated
//import specs2.arguments.sequential
//
//@RunWith(classOf[JUnitRunner])
//class RulesServicev14Test extends Specification with Loggable {
//  sequential
//  isolated
//
//  val restTestSetUp   = RestTestSetUp.newEnv
//
//  val rulesApiService14 = restTestSetUp.ruleApiService14
//
////  final case class JQRule(
////    id               : Option[RuleId]              = None
////    , displayName      : Option[String]              = None
////    , category         : Option[String]              = None
////    , shortDescription : Option[String]              = None
////    , longDescription  : Option[String]              = None
////    , directives       : Option[Set[DirectiveId]]    = None
////    , targets          : Option[Set[JRRuleTarget]]   = None
////    , enabled          : Option[Boolean]             = None
////    , tags             : Option[Tags]                = None
////    //for clone
////    , source           : Option[RuleId]              = None
////  ) {
//  "Secret service CRUD" should {
//    "create a rule" in {
//      rulesApiService14.createRule()
//    }
//  }
//
//
//
//}
