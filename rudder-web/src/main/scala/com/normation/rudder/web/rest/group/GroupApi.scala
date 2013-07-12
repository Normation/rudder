package com.normation.rudder.web.rest.group

import net.liftweb.http.rest.RestHelper
import net.liftweb.common._
import net.liftweb.http.LiftResponse
import net.liftweb.http.Req
import com.normation.rudder.domain.policies.DirectiveId
import com.normation.rudder.domain.policies.GroupTarget
import com.normation.rudder.domain.nodes.NodeGroup
import com.normation.rudder.domain.queries.Query

class LatestGroupAPI (
    latestApi : GroupAPI
) extends RestHelper with Loggable {
    serve( "api" / "lastest" / "groups" prefix latestApi.requestDispatch)

}

trait GroupAPI {
  val requestDispatch : PartialFunction[Req, () => Box[LiftResponse]]

}

case class RestGroup(
      name        : Option[String] = None
    , description : Option[String] = None
    , query       : Option[Query] = None
    , isDynamic   : Option[Boolean] = None
    , enabled     : Option[Boolean]     = None
  ) {

    val onlyName = name.isDefined      &&
                   description.isEmpty &&
                   query.isEmpty       &&
                   isDynamic.isEmpty   &&
                   enabled.isEmpty

    def updateGroup(group:NodeGroup) = {
      val updateName  = name.getOrElse(group.name)
      val updateDesc  = description.getOrElse(group.description)
      val updateisDynamic = isDynamic.getOrElse(group.isDynamic)
      val updateEnabled = enabled.getOrElse(group.isEnabled)
      group.copy(
          name        = updateName
        , description = updateDesc
        , query       = query
        , isDynamic   = updateisDynamic
        , isEnabled   = updateEnabled
      )

    }
}