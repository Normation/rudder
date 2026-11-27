package com.normation.rudder.campaigns

import com.normation.errors.IOResult
import com.normation.rudder.domain.nodes.NodeGroupId

trait CampaignTargetService {

  // TODO implementation dans system-update
  def removeGroupFromCampaigns(groupId: NodeGroupId): IOResult[List[Campaign]]

}
