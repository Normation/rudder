package com.normation.rudder.web.rest.directive

import net.liftweb.http.rest.RestHelper
import net.liftweb.common._
import net.liftweb.http.LiftResponse
import net.liftweb.http.Req
import com.normation.rudder.domain.policies.Directive
import com.normation.cfclerk.domain.TechniqueVersion
import com.normation.cfclerk.domain.Technique
import com.normation.rudder.domain.policies.ActiveTechnique

class LatestDirectiveAPI (
    latestApi : DirectiveAPI
) extends RestHelper with Loggable {
    serve( "api" / "lastest" / "directives" prefix latestApi.requestDispatch)

}

trait DirectiveAPI {
  val requestDispatch : PartialFunction[Req, () => Box[LiftResponse]]

}

case class RestDirective(
      name             : Option[String] = None
    , shortDescription : Option[String] = None
    , longDescription  : Option[String] = None
    , enabled          : Option[Boolean] = None
    , parameters       : Option[Map[String, Seq[String]]] = None
    , priority         : Option[Int] = None
    , techniqueVersion : Option[TechniqueVersion] = None
  ) {

    val onlyName = name.isDefined           &&
                   shortDescription.isEmpty &&
                   longDescription.isEmpty  &&
                   parameters.isEmpty       &&
                   priority.isEmpty         &&
                   enabled.isEmpty

    def updateDirective(directive:Directive) = {
      val updateName = name.getOrElse(directive.name)
      val updateShort = shortDescription.getOrElse(directive.shortDescription)
      val updateLong = longDescription.getOrElse(directive.longDescription)
      val updateEnabled = enabled.getOrElse(directive.isEnabled)
      val updateTechniqueVersion = techniqueVersion.getOrElse(directive.techniqueVersion)
      val updateParameters = parameters.getOrElse(directive.parameters)
      val updatePriority = priority.getOrElse(directive.priority)
      directive.copy(
          name             = updateName
        , shortDescription = updateShort
        , longDescription  = updateLong
        , isEnabled        = updateEnabled
        , parameters       = updateParameters
        , techniqueVersion = updateTechniqueVersion
        , priority         = updatePriority
      )

    }
}