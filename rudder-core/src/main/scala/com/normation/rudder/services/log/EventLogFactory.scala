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
}

class EventLogFactoryImpl(
    crToXml: ConfigurationRuleSerialisation
  , piToXml: PolicyInstanceSerialisation
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
    AddConfigurationRule(id, principal, details, creationDate, severity)
  }

  
  def getDeleteConfigurationRuleFromDiff(    
      id          : Option[Int] = None
    , principal   : EventActor
    , deleteDiff  : DeleteConfigurationRuleDiff
    , creationDate: DateTime = DateTime.now()
    , severity    : Int = 100
  ) : DeleteConfigurationRule= {
    val details = EventLog.withContent(crToXml.serialise(deleteDiff.cr) % ("changeType" -> "delete"))
    DeleteConfigurationRule(id, principal, details, creationDate, severity)
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
    ModifyConfigurationRule(id, principal, details, creationDate, severity)
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
    AddPolicyInstance(id, principal, details, creationDate, severity)
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
    DeletePolicyInstance(id, principal, details, creationDate, severity)    
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
    ModifyPolicyInstance(id, principal, details, creationDate, severity)  
  }
}