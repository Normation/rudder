package com.normation.rudder.services.log
import com.normation.rudder.domain.policies.ModifyConfigurationRuleDiff
import com.normation.rudder.domain.log.ModifyConfigurationRule
import com.normation.rudder.domain.log.AddConfigurationRule
import com.normation.rudder.domain.policies.AddConfigurationRuleDiff
import com.normation.eventlog.EventActor
import com.normation.rudder.domain.policies.DeleteConfigurationRuleDiff
import com.normation.rudder.domain.log.DeleteConfigurationRule
import com.normation.rudder.services.marshalling.ConfigurationRuleSerialisation
import org.joda.time.DateTime
import com.normation.eventlog.EventLog
import com.normation.rudder.domain.Constants
import com.normation.rudder.domain.policies.SimpleDiff
import net.liftweb.util.Helpers._
import com.normation.rudder.domain.policies.PolicyInstanceTarget
import scala.xml.Text
import com.normation.rudder.domain.policies.PolicyInstanceId
import com.normation.rudder.domain.policies.AddPolicyInstanceDiff
import com.normation.cfclerk.domain.SectionSpec
import com.normation.rudder.domain.policies.ModifyPolicyInstanceDiff
import com.normation.rudder.domain.policies.DeletePolicyInstanceDiff
import com.normation.rudder.services.marshalling.PolicyInstanceSerialisation
import com.normation.cfclerk.domain.PolicyVersion
import com.normation.rudder.domain.log.ModifyPolicyInstance
import com.normation.rudder.domain.log.AddPolicyInstance
import com.normation.rudder.domain.log.DeletePolicyInstance
import com.normation.rudder.domain.policies.SectionVal
import com.normation.rudder.domain.nodes.AddNodeGroupDiff
import com.normation.rudder.domain.log.DeleteNodeGroup
import com.normation.rudder.domain.log.AddNodeGroup
import com.normation.rudder.domain.nodes.ModifyNodeGroupDiff
import com.normation.rudder.domain.log.ModifyNodeGroup
import com.normation.rudder.domain.nodes.DeleteNodeGroupDiff
import com.normation.rudder.services.marshalling.NodeGroupSerialisation
import com.normation.rudder.domain.queries.Query
import com.normation.inventory.domain.NodeId
import com.normation.eventlog.EventLogDetails

trait EventLogFactory {
  
  def getAddConfigurationRuleFromDiff(
      id          : Option[Int] = None
    , principal   : EventActor
    , addDiff     : AddConfigurationRuleDiff
    , creationDate: DateTime = DateTime.now()
    , severity    : Int = 100
  ) : AddConfigurationRule

  
  def getDeleteConfigurationRuleFromDiff(
      id          : Option[Int] = None
    , principal   : EventActor
    , deleteDiff  : DeleteConfigurationRuleDiff
    , creationDate: DateTime = DateTime.now()
    , severity    : Int = 100
  ) : DeleteConfigurationRule
  
  def getModifyConfigurationRuleFromDiff(
      id          : Option[Int] = None
    , principal   : EventActor
    , modifyDiff  : ModifyConfigurationRuleDiff
    , creationDate: DateTime = DateTime.now()
    , severity    : Int = 100
  ) : ModifyConfigurationRule
  
  def getAddPolicyInstanceFromDiff(
      id                 : Option[Int] = None
    , principal          : EventActor
    , addDiff            : AddPolicyInstanceDiff
    , varsRootSectionSpec: SectionSpec
    , creationDate       : DateTime = DateTime.now()
    , severity           : Int = 100
  ) : AddPolicyInstance
  
  def getDeletePolicyInstanceFromDiff(
      id                 : Option[Int] = None
    , principal          : EventActor
    , deleteDiff         : DeletePolicyInstanceDiff
    , varsRootSectionSpec: SectionSpec
    , creationDate       : DateTime = DateTime.now()
    , severity           : Int = 100
  ) : DeletePolicyInstance
  
  def getModifyPolicyInstanceFromDiff(
      id          : Option[Int] = None
    , principal   : EventActor
    , modifyDiff  : ModifyPolicyInstanceDiff
    , creationDate: DateTime = DateTime.now()
    , severity    : Int = 100
  ) : ModifyPolicyInstance
  
  def getAddNodeGroupFromDiff(
      id          : Option[Int] = None
    , principal   : EventActor
    , addDiff     : AddNodeGroupDiff
    , creationDate: DateTime = DateTime.now()
    , severity    : Int = 100
  ) : AddNodeGroup

  def getDeleteNodeGroupFromDiff(
      id          : Option[Int] = None
    , principal   : EventActor
    , deleteDiff  : DeleteNodeGroupDiff
    , creationDate: DateTime = DateTime.now()
    , severity    : Int = 100
  ) : DeleteNodeGroup

  def getModifyNodeGroupFromDiff(
      id          : Option[Int] = None
    , principal   : EventActor
    , modifyDiff  : ModifyNodeGroupDiff
    , creationDate: DateTime = DateTime.now()
    , severity    : Int = 100
  ) : ModifyNodeGroup
  
}

class EventLogFactoryImpl(
    crToXml   : ConfigurationRuleSerialisation
  , piToXml   : PolicyInstanceSerialisation
  , groutToXml: NodeGroupSerialisation
) extends EventLogFactory {
  
  ///// 
  ///// Configuration Rules /////
  /////
  
  def getAddConfigurationRuleFromDiff(
      id          : Option[Int] = None
    , principal   : EventActor
    , addDiff     : AddConfigurationRuleDiff
    , creationDate: DateTime = DateTime.now()
    , severity    : Int = 100
  ) : AddConfigurationRule= {
    val details = EventLog.withContent(crToXml.serialise(addDiff.cr) % ("changeType" -> "add"))
    AddConfigurationRule(EventLogDetails(
        id = id
      , principal = principal
      , details = details
      , creationDate = creationDate
      , severity = severity))
  }

  
  def getDeleteConfigurationRuleFromDiff(    
      id          : Option[Int] = None
    , principal   : EventActor
    , deleteDiff  : DeleteConfigurationRuleDiff
    , creationDate: DateTime = DateTime.now()
    , severity    : Int = 100
  ) : DeleteConfigurationRule= {
    val details = EventLog.withContent(crToXml.serialise(deleteDiff.cr) % ("changeType" -> "delete"))
    DeleteConfigurationRule(EventLogDetails(
        id = id
      , principal = principal
      , details = details
      , creationDate = creationDate
      , severity = severity))
  }
  
  def getModifyConfigurationRuleFromDiff(    
      id          : Option[Int] = None
    , principal   : EventActor
    , modifyDiff  : ModifyConfigurationRuleDiff
    , creationDate: DateTime = DateTime.now()
    , severity    : Int = 100
  ) : ModifyConfigurationRule = {
    val details = EventLog.withContent{
      scala.xml.Utility.trim(
        <configurationRule changeType="modify" fileFormat={Constants.XML_FILE_FORMAT_1_0}>
          <id>{modifyDiff.id.value}</id>
          <displayName>{modifyDiff.name}</displayName>{
            modifyDiff.modName.map(x => SimpleDiff.stringToXml(<name/>, x) ) ++
            modifyDiff.modSerial.map(x => SimpleDiff.intToXml(<serial/>, x ) ) ++
            modifyDiff.modTarget.map(x => SimpleDiff.toXml[Option[PolicyInstanceTarget]](<target/>, x){ t =>
              t match {
                case None => <none/>
                case Some(y) => Text(y.target)
              }
            } ) ++
            modifyDiff.modPolicyInstanceIds.map(x => SimpleDiff.toXml[Set[PolicyInstanceId]](<policyInstanceIds/>, x){ ids =>
                ids.toSeq.map { id => <id>{id.value}</id> }
              } ) ++
            modifyDiff.modShortDescription.map(x => SimpleDiff.stringToXml(<shortDescription/>, x ) ) ++
            modifyDiff.modLongDescription.map(x => SimpleDiff.stringToXml(<longDescription/>, x ) ) ++
            modifyDiff.modIsActivatedStatus.map(x => SimpleDiff.booleanToXml(<isActivated/>, x ) ) ++
            modifyDiff.modIsSystem.map(x => SimpleDiff.booleanToXml(<isSystem/>, x ) )
          }
        </configurationRule>
      )
    }
    ModifyConfigurationRule(EventLogDetails(
        id = id
      , principal = principal
      , details = details
      , creationDate = creationDate
      , severity = severity))
  }
  
  ///// 
  ///// Policy Instance /////
  /////
  
  def getAddPolicyInstanceFromDiff(
      id                 : Option[Int] = None
    , principal          : EventActor
    , addDiff            : AddPolicyInstanceDiff
    , varsRootSectionSpec: SectionSpec
    , creationDate       : DateTime = DateTime.now()
    , severity           : Int = 100
  ) = {
    val details = EventLog.withContent(piToXml.serialise(addDiff.policyTemplateName, varsRootSectionSpec, addDiff.pi) % ("changeType" -> "add"))
    AddPolicyInstance(EventLogDetails(
        id = id
      , principal = principal
      , details = details
      , creationDate = creationDate
      , severity = severity))
  }
  
  def getDeletePolicyInstanceFromDiff(
      id                 : Option[Int] = None
    , principal          : EventActor
    , deleteDiff         : DeletePolicyInstanceDiff
    , varsRootSectionSpec: SectionSpec
    , creationDate       : DateTime = DateTime.now()
    , severity           : Int = 100
  ) = {
    val details = EventLog.withContent(piToXml.serialise(deleteDiff.policyTemplateName, varsRootSectionSpec, deleteDiff.pi) % ("changeType" -> "delete"))
    DeletePolicyInstance(EventLogDetails(
        id = id
      , principal = principal
      , details = details
      , creationDate = creationDate
      , severity = severity))    
  }
  
  def getModifyPolicyInstanceFromDiff(
      id          : Option[Int] = None
    , principal   : EventActor
    , modifyDiff  : ModifyPolicyInstanceDiff
    , creationDate: DateTime = DateTime.now()
    , severity    : Int = 100
  ) = {
    val details = EventLog.withContent{
      scala.xml.Utility.trim(
        <policyInstance changeType="modify" fileFormat={Constants.XML_FILE_FORMAT_1_0}>
          <id>{modifyDiff.id.value}</id>
          <policyTemplateName>{modifyDiff.policyTemplateName.value}</policyTemplateName>
          <displayName>{modifyDiff.name}</displayName>{
            modifyDiff.modName.map(x => SimpleDiff.stringToXml(<name/>, x) ) ++
            modifyDiff.modPolicyTemplateVersion.map(x => SimpleDiff.toXml[PolicyVersion](<policyTemplateVersion/>, x){ v =>
              Text(v.toString)
            } ) ++
            modifyDiff.modParameters.map(x => SimpleDiff.toXml[SectionVal](<parameters/>, x){ sv =>
              SectionVal.toXml(sv)
            } ) ++
            modifyDiff.modShortDescription.map(x => SimpleDiff.stringToXml(<shortDescription/>, x ) ) ++
            modifyDiff.modLongDescription.map(x => SimpleDiff.stringToXml(<longDescription/>, x ) ) ++
            modifyDiff.modPriority.map(x => SimpleDiff.intToXml(<priority/>, x ) ) ++
            modifyDiff.modIsActivated.map(x => SimpleDiff.booleanToXml(<isActivated/>, x ) ) ++
            modifyDiff.modIsSystem.map(x => SimpleDiff.booleanToXml(<isSystem/>, x ) )
          }
        </policyInstance>
      )
    }
    ModifyPolicyInstance(EventLogDetails(
        id = id
      , principal = principal
      , details = details
      , creationDate = creationDate
      , severity = severity))  
  }


  def getAddNodeGroupFromDiff(
      id          : Option[Int] = None
    , principal   : EventActor
    , addDiff     : AddNodeGroupDiff
    , creationDate: DateTime = DateTime.now()
    , severity    : Int = 100
  ) : AddNodeGroup = {
    val details = EventLog.withContent(groutToXml.serialise(addDiff.group) % ("changeType" -> "add"))
    AddNodeGroup(EventLogDetails(
        id = id
      , principal = principal
      , details = details
      , creationDate = creationDate
      , severity = severity))
  }

  def getDeleteNodeGroupFromDiff(
      id          : Option[Int] = None
    , principal   : EventActor
    , deleteDiff  : DeleteNodeGroupDiff
    , creationDate: DateTime = DateTime.now()
    , severity    : Int = 100
  ) : DeleteNodeGroup = {
    val details = EventLog.withContent(groutToXml.serialise(deleteDiff.group) % ("changeType" -> "delete"))
    DeleteNodeGroup(EventLogDetails(
        id = id
      , principal = principal
      , details = details
      , creationDate = creationDate
      , severity = severity))
  }

  def getModifyNodeGroupFromDiff(
      id          : Option[Int] = None
    , principal   : EventActor
    , modifyDiff  : ModifyNodeGroupDiff
    , creationDate: DateTime = DateTime.now()
    , severity    : Int = 100
  ) : ModifyNodeGroup = {
    val details = EventLog.withContent{
      scala.xml.Utility.trim(<nodeGroup changeType="modify" fileFormat={Constants.XML_FILE_FORMAT_1_0}>
        <id>{modifyDiff.id.value}</id>
        <displayName>{modifyDiff.name}</displayName>{
          modifyDiff.modName.map(x => SimpleDiff.stringToXml(<name/>, x) ) ++
          modifyDiff.modDescription.map(x => SimpleDiff.stringToXml(<description/>, x ) ) ++
          modifyDiff.modQuery.map(x => SimpleDiff.toXml[Option[Query]](<query/>, x){ t =>
            t match {
              case None => <none/>
              case Some(y) => Text(y.toJSONString)
            }
          } ) ++
          modifyDiff.modIsDynamic.map(x => SimpleDiff.booleanToXml(<isDynamic/>, x ) ) ++
          modifyDiff.modServerList.map(x => SimpleDiff.toXml[Set[NodeId]](<nodeIds/>, x){ ids =>
              ids.toSeq.map { id => <id>{id.value}</id> }
            } ) ++
          modifyDiff.modIsActivated.map(x => SimpleDiff.booleanToXml(<isActivated/>, x ) ) ++
          modifyDiff.modIsSystem.map(x => SimpleDiff.booleanToXml(<isSystem/>, x ) )
        }
      </nodeGroup>)
    }
    ModifyNodeGroup(EventLogDetails(
        id = id
      , principal = principal
      , details = details
      , creationDate = creationDate
      , severity = severity))
  }
}


