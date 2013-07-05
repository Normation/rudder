package com.normation.rudder.web.rest.rule

import net.liftweb.http.rest.RestHelper
import net.liftweb.common._
import net.liftweb.http.LiftResponse
import net.liftweb.http.Req
import com.normation.rudder.domain.policies.DirectiveId
import com.normation.rudder.domain.policies.RuleTarget
import com.normation.rudder.domain.policies.Rule

class LatestRuleAPI (
    latestApi : RuleAPI
) extends RestHelper with Loggable {
    serve( "api" / "lastest" / "rules" prefix latestApi.requestDispatch)

}

trait RuleAPI {
  val requestDispatch : PartialFunction[Req, () => Box[LiftResponse]]

}

case class RestRule(
      name             : Option[String] = None
    , shortDescription : Option[String] = None
    , longDescription  : Option[String] = None
    , directives       : Option[Set[DirectiveId]] = None
    , targets          : Option[Set[RuleTarget]] = None
    , enabled        : Option[Boolean]     = None
  ) {

    val onlyName = name.isDefined           &&
                   shortDescription.isEmpty &&
                   longDescription.isEmpty  &&
                   directives.isEmpty       &&
                   targets.isEmpty          &&
                   enabled.isEmpty

    def updateRule(rule:Rule) = {
      val updateName = name.getOrElse(rule.name)
      val updateShort = shortDescription.getOrElse(rule.shortDescription)
      val updateLong = longDescription.getOrElse(rule.longDescription)
      val updateDirectives = directives.getOrElse(rule.directiveIds)
      val updateTargets = targets.getOrElse(rule.targets)
      val updateEnabled = enabled.getOrElse(rule.isEnabledStatus)
      rule.copy(
          name             = updateName
        , shortDescription = updateShort
        , longDescription  = updateLong
        , directiveIds     = updateDirectives
        , targets          = updateTargets
        , isEnabledStatus  = updateEnabled
      )

    }
}