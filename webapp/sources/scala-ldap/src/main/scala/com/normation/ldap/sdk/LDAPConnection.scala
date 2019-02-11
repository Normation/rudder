/*
*************************************************************************************
* Copyright 2011 Normation SAS
*************************************************************************************
*
* Licensed under the Apache License, Version 2.0 (the "License");
* you may not use this file except in compliance with the License.
* You may obtain a copy of the License at
*
* http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.
*
*************************************************************************************
*/

package com.normation.ldap.sdk

import com.unboundid.ldap.sdk.{AddRequest, DN, DeleteRequest, Filter, LDAPException, LDAPSearchException, Modification, ModifyDNRequest, ModifyRequest, RDN, ReadOnlyLDAPRequest, ResultCode, SearchRequest}
import ResultCode._
import cats.data.NonEmptyList
import cats.data.ValidatedNel
import com.unboundid.ldif.LDIFChangeRecord
import com.normation.ldap.ldif.{LDIFFileLogger, LDIFNoopChangeRecord}
import cats.implicits._

import scala.collection.JavaConverters._
import org.slf4j.LoggerFactory
import com.normation.ldap.sdk.IOLdap._

sealed trait LDAPConnectionError {
  def msg  : String
  def messageChain: String
}

object LDAPConnectionError {
  // errors due to some LDAPException
  final case class BackendException(msg: String, cause: Throwable)  extends LDAPConnectionError {
    def messageChain = msg
  }
  // errors where there is a result, but result is not SUCCESS
  final case class FailureResult(msg: String, result: LDAPResult)   extends LDAPConnectionError {
    def messageChain = msg
  }
  // errors linked to some logic of our lib
  final case class Rudder(msg: String)                              extends LDAPConnectionError{
    def messageChain = msg
  }
  // trace
  final case class Chained(msg: String, cause: LDAPConnectionError) extends LDAPConnectionError {
    def messageChain = msg +" <- " + cause.messageChain
  }
  // accumulated errors from multiple independent action
  final case class Accumulated(errors: NonEmptyList[LDAPConnectionError]) extends LDAPConnectionError {
    def msg = s"Several errors encountered: ${errors.toList.map(_.messageChain).mkString("; ")}"
    def messageChain = msg
  }
}

object IOLdap {
  import net.liftweb.common._
  type IOLdap[T] = Either[LDAPConnectionError, T]

  // transform an Option[T] into an error
  implicit class StrictOption[T](opt: IOLdap[Option[T]]) {
    def notOptional(msg: String) = opt.flatMap {
      case None    => Left(LDAPConnectionError.Rudder(msg))
      case Some(x) => Right(x)
    }
  }

  implicit class ToSuccess[T](t: T) {
    def successIOLdap() = Right(t)
  }
  implicit class ToFailure[T <: LDAPConnectionError](e: T) {
    def failureIOLdap() = Left(e)
  }
  // same than above for a Rudder error from a string
  implicit class ToFailureMsg(e: String) {
    def failureIOLdap() = Left(LDAPConnectionError.Rudder(e))
  }
  // for easier transition from Box
  implicit class ChainError[T](res: IOLdap[T]) {
    def ?~!(msg: String): IOLdap[T] = res.leftMap(e =>
      LDAPConnectionError.Chained(msg, e)
    )
  }

  // for compat
  implicit class ToBox[T](res: IOLdap[T]) {
    import net.liftweb.common._
    def toBox: Box[T] = res.fold(e => Failure(e.messageChain), s => Full(s))
  }

  implicit class ValidatedToLdapError[T](res: ValidatedNel[LDAPConnectionError, T]) {
    def toIOLdap: IOLdap[T] = res.fold(errors => LDAPConnectionError.Accumulated(errors).failureIOLdap() , x => x.successIOLdap())
  }

  implicit class ToIOLdapError(res: EmptyBox) {
    def toIOLdapError: LDAPConnectionError = res match {
      case Empty                        => LDAPConnectionError.Rudder("Unknow error happened")
      case Failure(msg, _, Full(cause)) => LDAPConnectionError.Chained(msg, cause.toIOLdapError)
      case Failure(msg, Full(ex), _)    => ex match {
                                             case ldapEx:LDAPException => LDAPConnectionError.BackendException(msg, ldapEx)
                                             case _                    => LDAPConnectionError.BackendException(msg, new LDAPException(ResultCode.OTHER, ex))
                                           }
      case Failure(msg, _, _)           => LDAPConnectionError.Rudder(msg)
    }
  }
  implicit class ToIOLdap[T](res: Box[T]) {
    def toIOLdap: IOLdap[T] = res match {
      case Full(x)     => x.successIOLdap()
      case eb:EmptyBox => eb.toIOLdapError.failureIOLdap()
    }
  }

}

trait ReadOnlyEntryLDAPConnection {

  /**
   * Most generic search request, which allows to use controls
   * and other advanced operation.
   * @param sr
   *   SearchRequest object which define the search operation
   *   to send to LDAP directory
   * @return
   *   The sequence of entries matching SearchRequest.
   */
  def search(sr:SearchRequest): IOLdap[Seq[LDAPEntry]]

  /**
   * Retrieve entry with given 'dn', optionally restricting
   * entry's attribute set to attribute with name in the
   * 'attributes' parameter.
   * @param dn
   *   DN of the entry to retrieve
   * @param attributes
   *   Only retrieve attributes on that list in the entry.
   *   Let empty to retrieve all attributes.
   * @return
   *   Full(entry) if the entry exists,
   *   Empty if no such entry exists
   *   Failure(message) if something bad happened
   */
  def get(dn:DN, attributes:String*) : IOLdap[Option[LDAPEntry]]

  /**
   * A search with commonly used parameters
   * @param baseDn
   *   The base DN from which the search has to be started
   * @param scope
   *   Scope of the search (base, one level, subtree)
   * @param filter
   *   Filter to use to decide if an entry should be returned
   * @param attributes
   *   If non-empty, for each returned entry, only retrieve
   *   attributes in the list.
   *   Otherwise, retrieve all attributes of all entries.
   * @return
   *   The sequence of entries matching search request parameters.
   */
  def search(baseDn:DN, scope:SearchScope, filter:Filter, attributes:String*) : IOLdap[Seq[LDAPEntry]] = {
    search(new SearchRequest(baseDn.toString, scope, filter, attributes:_*))
  }

  /**
   * Search for one entry which is:
   * - a direct children of a base DN
   * - match the filter
   *
   * Only one entry is returned at max. If the
   * filter match several entries under base DN,
   * one will be pick at random.
   *
   * @param baseDn
   *   Root of the search: we are looking for one of
   *   its children
   * @param filter
   *   Filter to use to choose from children of base DN entry
   * @param attributes
   *   If non empty, only retrieve attribute from that list.
   *   Else, retrieve all attributes.
   * @return
   *   Full(entry) if an entry matching filter is found in
   *     base DN entry's children
   *   Failure(message) if something goes wrong
   *   Empty otherwise
   */
  def get(baseDn:DN, filter:Filter, attributes:String*) : IOLdap[Option[LDAPEntry]] = {
    searchOne(baseDn, filter, attributes:_*).map {
      case buf if(buf.isEmpty) => None
      case buf                 => Some(buf(0))
    }
  }

  /**
   * Test existence of the given entry.
   * Of course, as LDAP is not a transactionnal datasource,
   * result is only valid for the time when directory
   * gave the answer.
   *
   * @param dn
   *   DN of the entry to test for existence
   * @return
   *   True if the entry exists, false otherwise.
   */
  def exists(dn:DN) : Boolean = get(dn, "1.1") match {
    case Right(Some(_)) => true
    case _              => false
  }

  /**
   * Search method restricted to scope = One level
   * @see search
   * @param baseDn
   *   The base DN from which the search has to be started
   * @param filter
   *   Filter to use to decide if an entry should be returned
   * @param attributes
   *   If non-empty, for each returned entry, only retrieve
   *   attributes in the list.
   *   Otherwise, retrieve all attributes of all entries.
   * @return
   *   The sequence of entries matching search request parameters.
   */
  def searchOne(baseDn:DN,filter:Filter, attributes:String*) : IOLdap[Seq[LDAPEntry]] = search(baseDn,One,filter,attributes:_*)

  /**
   * Search method restricted to scope = SubTree
   * @see search
   * @param baseDn
   *   The base DN from which the search has to be started
   * @param filter
   *   Filter to use to decide if an entry should be returned
   * @param attributes
   *   If non-empty, for each returned entry, only retrieve
   *   attributes in the list.
   *   Otherwise, retrieve all attributes of all entries.
   * @return
   *   The sequence of entries matching search request parameters.
   */
  def searchSub(baseDn:DN,filter:Filter, attributes:String*) : IOLdap[Seq[LDAPEntry]] = search(baseDn,Sub,filter,attributes:_*)
}

trait WriteOnlyEntryLDAPConnection {

  /**
   * Execute a plain modification.
   * Return the actual modification executed if success,
   * the error in other case.
   */
  def modify(dn:DN, modifications:Modification*) : IOLdap[LDIFChangeRecord]

  /**
   * Move entry with given dn to new parent.
   * @param dn
   *   Entry's DN to move
   * @param newParentDn
   *   New parent's DN
   * @parem newRDN
   *   Optionnaly change the RDN of the entry.
   * @return
   *   Full[Seq(ldifChangeRecord)] if the operation is successful
   *   Empty or Failure if an error occurred.
   */
  def move(dn:DN,newParentDn:DN, newRDN:Option[RDN] = None) : IOLdap[LDIFChangeRecord]

  /**
   * Save an LDAP entry.
   * The semantic of a save is complex:
   * - by default, it only update attributes in LDAP entry.
   *   That means that if entry in the directory has (a,b,c) attribute, and entry has only (a,b),
   *   then c won't be remove nor updated in LDAP
   * - attribute with no values are removed
   *   That means that if entry has attribute 'a' with no value, attribute 'a' will be removed in LDAP directory
   * - if "removeMissing" is set to true, then missing attribute in entry are marked to be removd (most of the time,
   *   it's not what you want).
   *   WARNING: the RDN attribute is always ignored. You can only change it with the <code>move</move> method
   * - if "removeMissing" is set to true, you can still keep some attribute enumerated here. If removeMissing is false,
   *   that parameter is ignored.
   */
  def save(entry : LDAPEntry, removeMissingAttributes:Boolean=false, forceKeepMissingAttributes:Seq[String] = Seq()) : IOLdap[LDIFChangeRecord]

  /**
   * Delete entry at the given DN
   * If recurse is set to true (default), delete all entry's children before
   * deleting entry.
   * If recurse is set to false, the entry must have zero child to be
   * allowed to be deleted.
   *
   * If no entry has the given DN, nothing is done.
   */
  def delete(dn:DN, recurse:Boolean = true) : IOLdap[Seq[LDIFChangeRecord]]

}

trait ReadOnlyTreeLDAPConnection {
  /**
   * Retrieve the full sub-tree of entries where the root
   * entry is the one with given 'dn'
   * All entries of the subtree are retrieved, and for
   * each of them, all attributes are retrieved.
   * BE CAREFULL: the result may be HUGE.
   * @param dn
   *   DN of the root entry for the sub-tree to retrieve
   * @return
   *   Full(LDAPTree) if the root entry exists and the command
   *     succeeded
   *   Empty if no entry has the given DN
   *   Failure(message) if something goes wrong.
   */
  def getTree(dn:DN) : IOLdap[Option[LDAPTree]]
}

trait WriteOnlyTreeLDAPConnection {
  /**
   * Save the full LDAPTree given in argument.
   *
   * TODO: specify behaviour.
   *
   * @param tree
   * @param deleteRemoved
   * @return
   */
  def saveTree(tree:LDAPTree, deleteRemoved:Boolean=false) : IOLdap[Seq[LDIFChangeRecord]]
}

/**
 * Trait that specify that the LDAPConnection is
 * backed by an UnboundID LDAPConnection object.
 * This object may be used to access to methods
 * not supported by LDAPConnection Scala API
 */
trait UnboundidBackendLDAPConnection {
  /**
   * Access to the backed UnboundID LDAPConnection object,
   * if one need to do operation not covered by Scala API.
   *
   * @return
   *   LDAPConnection object used in back-end.
   */
  def backed : UnboundidLDAPConnection

  /**
   * Close that LDAPConnection
   */
  def close() : Unit = backed.close()

}

object RoLDAPConnection {
  import ResultCode._
  /**
   * Default error on which we don't want to throw an exception
   * but only log a message for Search operation
   */
  def onlyReportOnSearch(errorCode:ResultCode) : Boolean = {
    errorCode match {
      case TIME_LIMIT_EXCEEDED |
        SIZE_LIMIT_EXCEEDED => true
      case _ => false
    }
  }
}


sealed class RoLDAPConnection(
  override val backed : UnboundidLDAPConnection,
  val ldifFileLogger : LDIFFileLogger,
  val onlyReportOnSearch: ResultCode => Boolean = RoLDAPConnection.onlyReportOnSearch
) extends
  UnboundidBackendLDAPConnection with
  ReadOnlyEntryLDAPConnection with
  ReadOnlyTreeLDAPConnection {

  val logger = LoggerFactory.getLogger(classOf[RoLDAPConnection])

  /*
   * //////////////////////////////////////////////////////////////////
   * // Read
   * //////////////////////////////////////////////////////////////////
   */

  override def search(sr:SearchRequest) : IOLdap[Seq[LDAPEntry]] = {
    try {
      backed.search(sr).getSearchEntries.asScala.map(e => LDAPEntry(e.getDN,e.getAttributes.asScala)).successIOLdap()
    } catch {
      case e:LDAPSearchException if(onlyReportOnSearch(e.getResultCode)) =>
        logger.error("Ignored execption (configured to be ignored)", e)
        e.getSearchEntries.asScala.map(e => LDAPEntry(e.getDN, e.getAttributes.asScala)).successIOLdap()
      case ex: LDAPException =>
        LDAPConnectionError.BackendException(s"Error during search: ${ex.getDiagnosticMessage}", ex).failureIOLdap()
    }
  }

  override def get(dn:DN, attributes:String*) : IOLdap[Option[LDAPEntry]] = {
    try {
      val e = if(attributes.size == 0) backed.getEntry(dn.toString)
              else backed.getEntry(dn.toString, attributes:_*)
      e match {
        case null => None.successIOLdap()
        case r => Some(LDAPEntry(r.getDN, r.getAttributes.asScala)).successIOLdap()
      }
    } catch {
      case ex: LDAPException =>
        LDAPConnectionError.BackendException(s"Error when getting enty '${dn.toNormalizedString}': ${ex.getDiagnosticMessage}", ex).failureIOLdap()
    }
  }

  /*
   * //////////////////////////////////////////////////////////////////
   * // Read Tree
   * //////////////////////////////////////////////////////////////////
   */

  override def getTree(dn:DN) : IOLdap[Option[LDAPTree]] = {
    try {
      val all = backed.search(dn.toString,Sub,BuildFilter.ALL)
      if(all.getEntryCount() > 0) {
        //build the tree
        LDAPTree(all.getSearchEntries.asScala.map(x => LDAPEntry(x))).map(Some(_))
      } else None.successIOLdap()
    } catch {
      //a no such object error simply means that the required LDAP tree is not in the directory
      case e:LDAPSearchException if(NO_SUCH_OBJECT == e.getResultCode) => None.successIOLdap()
      case e:LDAPException => LDAPConnectionError.BackendException(s"Can not get tree '${dn}': ${e.getDiagnosticMessage}", e).failureIOLdap()
    }
  }
}

object RwLDAPConnection {
  import ResultCode._

  /**
   * Default error on which we don't want to throw an exception
   * but only log a message for Add operation
   */
  def onlyReportOnAdd(errorCode:ResultCode) : Boolean = {
    errorCode match {
      case NO_SUCH_ATTRIBUTE |
           UNDEFINED_ATTRIBUTE_TYPE |
           ATTRIBUTE_OR_VALUE_EXISTS |
           INVALID_ATTRIBUTE_SYNTAX |
           NO_SUCH_OBJECT |
           INVALID_DN_SYNTAX |
           ENTRY_ALREADY_EXISTS |
           ENCODING_ERROR => true
      case _ => false
    }
  }

  /**
   * Default error on which we don't want to throw an exception
   * but only log a message for Delete operation
   */
  def onlyReportOnDelete(errorCode:ResultCode) : Boolean = onlyReportOnAdd(errorCode)

  /**
   * Default error on which we don't want to throw an exception
   * but only log a message for Modify operation
   */
  def onlyReportOnModify(errorCode:ResultCode) : Boolean = {
    errorCode match {
      case NO_SUCH_ATTRIBUTE |
           UNDEFINED_ATTRIBUTE_TYPE |
           ATTRIBUTE_OR_VALUE_EXISTS |
           INVALID_ATTRIBUTE_SYNTAX |
           INVALID_DN_SYNTAX |
           ENTRY_ALREADY_EXISTS |
           ENCODING_ERROR => true
      case _ => false
    }
  }

  /**
   * Default error on which we don't want to throw an exception
   * but only log a message for ModifyDN operation
   */
  def onlyReportOnModifyDN(errorCode:ResultCode) : Boolean = onlyReportOnModify(errorCode)
}

/**
 *
 * LDAPConnection is the media to talk with the
 * LDAP directory.
 *
 * It's not here that connection properties and creation are
 * deals with. For that, look to <code>LDAPConnectionProvider</code>
 *
 * Main interaction are:
 * - get : optionally retrieve an entry
 * - search : retrieve entries based on a search request
 * - save : modify entry attributes (not the dn/rdn one)
 * - delete : delete a tree (or only one entry)
 * - getTree : retrieve a subtree
 * - move : change the dn of an entry
 *
 * @parameter backed
 *   UnboundID LDAPConnection to use to actually execute commands
 *
 * @parameter onlyReportOn*
 *   Methods that decide if such an error ResultCode should
 *   throw an exception (and probably kill the connection) or
 *   if the error only has to be logged.
 *   Typically, you want to throw an exception on error like
 *   "the directory is not available", and only get an error
 *   message (and report it to the user) on "the attribute value
 *   you tried to save is not valid for that entry".
 */
class RwLDAPConnection(
  override val backed : UnboundidLDAPConnection,
  override val ldifFileLogger : LDIFFileLogger,
  onlyReportOnAdd: ResultCode => Boolean = RwLDAPConnection.onlyReportOnAdd,
  onlyReportOnModify: ResultCode => Boolean = RwLDAPConnection.onlyReportOnModify,
  onlyReportOnModifyDN: ResultCode => Boolean = RwLDAPConnection.onlyReportOnModifyDN,
  onlyReportOnDelete: ResultCode => Boolean = RwLDAPConnection.onlyReportOnDelete,
  override val onlyReportOnSearch: ResultCode => Boolean = RoLDAPConnection.onlyReportOnSearch
) extends
  RoLDAPConnection(backed, ldifFileLogger, onlyReportOnSearch) with
  WriteOnlyEntryLDAPConnection with
  WriteOnlyTreeLDAPConnection
{

  override val logger = LoggerFactory.getLogger(classOf[RwLDAPConnection])

  /**
   * Ask the directory if it knows how to
   * delete full sub-tree in one command.
   */
  private lazy val canDeleteTree : Boolean = {
    try {
      backed.getRootDSE.supportsControl(com.unboundid.ldap.sdk.controls.SubtreeDeleteRequestControl.SUBTREE_DELETE_REQUEST_OID)
    } catch {
      case e: LDAPException =>
        logger.debug("Can not know if the LDAP server support recursive subtree delete request control, supposing not. Exception was: " + e.getMessage())
        false
    }
  }



  /*
   * //////////////////////////////////////////////////////////////////
   * // Write
   * //////////////////////////////////////////////////////////////////
   */

  /**
   * Generic method that apply a sequence of modification to a directory.
   * It can trace the full list of queries and handle result code.
   * @parameter MOD
   *   The modification request type
   * @parameter MOD => LDIFChangeRecord
   *   The method to call to transform a modification request of type MOD into
   *   an LDIFChangeRecord
   * @parameter MOD => LDAPResult
   *   The method to call on the backend UnboundidLDAPConnection to actually
   *   execute the modification request.
   * @parameter Seq[MOD]
   *   the list of modification to apply.
   */
  private def applyMods[MOD <: ReadOnlyLDAPRequest](modName: String, toLDIFChangeRecord:MOD => LDIFChangeRecord, backendAction: MOD => LDAPResult, onlyReportThat: ResultCode => Boolean)(reqs: List[MOD]) : IOLdap[Seq[LDIFChangeRecord]] = {
    if(reqs.size < 1) Seq().successIOLdap()
    else {
      ldifFileLogger.records(reqs map ( toLDIFChangeRecord (_) ))
      reqs.traverse { req =>
        applyMod(modName, toLDIFChangeRecord, backendAction, onlyReportThat)(req)
      }
    }
  }

  /**
   * Try to execute the given modification. In case of SUCCESS, return the corresponding change record.
   * In case of error, check if the error should be ignored. In such case, we assume that no modification were
   * actually done in the server: return a success with the corresponding "no change record" content.
   */
  private def applyMod[MOD <: ReadOnlyLDAPRequest](modName: String, toLDIFChangeRecord:MOD => LDIFChangeRecord, backendAction: MOD => LDAPResult, onlyReportThat: ResultCode => Boolean)(req:MOD) : IOLdap[LDIFChangeRecord] = {
    val record = toLDIFChangeRecord(req)
    ldifFileLogger.records(Seq(record))
    try {
      val res = backendAction(req)
      if(res.getResultCode == SUCCESS) {
        record.successIOLdap()
      } else if(onlyReportThat(res.getResultCode)) {
        LDIFNoopChangeRecord(record.getParsedDN).successIOLdap()
      } else {
        LDAPConnectionError.FailureResult(s"Error when doing action '${modName}' with and LDIF change request: ${res.getDiagnosticMessage}", res).failureIOLdap()
      }
    } catch {
      case ex:LDAPException =>
        if(onlyReportThat(ex.getResultCode)) {
          logIgnoredException(record.getDN, modName, ex)
          LDIFNoopChangeRecord(record.getParsedDN).successIOLdap()
        } else {
          LDAPConnectionError.BackendException(s"Error when doing action '${modName}' with and LDIF change request: ${ex.getDiagnosticMessage}", ex).failureIOLdap()
        }
    }
  }


  private[this] def logIgnoredException(dn: => String, action: String, e:Throwable) : Unit = {
      val message = s"Exception ignored (by configuration) when trying to $action entry '$dn'.  Reported exception was: ${e.getMessage}"
      logger.error(message,e)
  }

  /**
   * Specialized version of applyMods for DeleteRequest modification type
   */
  private val applyDeletes = applyMods[DeleteRequest](
      "delete"
    , {req:DeleteRequest => req.toLDIFChangeRecord}
    , {req:DeleteRequest => backed.delete(req)}
    , res => NO_SUCH_OBJECT == res || onlyReportOnDelete(res) // no such object only says it's already deleted
  ) _

  /**
   * Specialized version of applyMods for AddRequest modification type
   */
  private val applyAdds = applyMods[AddRequest](
      "adds"
    , {req:AddRequest => req.toLDIFChangeRecord}
    , {req:AddRequest => backed.add(req)}
    , onlyReportOnAdd
  ) _

  private val applyAdd = applyMod[AddRequest](
      "add"
    , {req:AddRequest => req.toLDIFChangeRecord}
    , {req:AddRequest => backed.add(req)}
    , onlyReportOnAdd
  ) _

  /**
   * Specialized version of applyMods for ModifyRequest modification type
   */
  private val applyModify = applyMod[ModifyRequest](
      "modify"
    , {req:ModifyRequest => req.toLDIFChangeRecord}
    , {req:ModifyRequest => backed.modify(req)}
    , onlyReportOnModify
  ) _

  /**
   * Execute a plain modification.
   * Return the actual modification executed if success,
   * the error in other case.
   */
  override def modify(dn:DN, modifications:Modification*) : IOLdap[LDIFChangeRecord] = {
    applyModify(new ModifyRequest(dn.toString,modifications:_*))
  }

  override def move(dn:DN, newParentDn:DN, newRDN:Option[RDN] = None) : IOLdap[LDIFChangeRecord] = {
    if(
        dn.getParent == newParentDn && (
            newRDN match {
              case None => true
              case Some(rdn) => dn.getRDN == rdn
            }
        )
    ) {
      LDIFNoopChangeRecord(dn).successIOLdap()
    } else {
      applyMod[ModifyDNRequest](
          "modify DN"
        , {req:ModifyDNRequest => req.toLDIFChangeRecord}
        , {req:ModifyDNRequest => backed.modifyDN(req)}
        , onlyReportOnModify
      ) (new ModifyDNRequest(dn.toString, newRDN.getOrElse(dn.getRDN).toString, newRDN.isDefined, newParentDn.toString))
    }
  }

  override def save(entry : LDAPEntry, removeMissingAttributes:Boolean=false, forceKeepMissingAttributes:Seq[String] = Seq()) : IOLdap[LDIFChangeRecord] = {
    synchronized {
      get(entry.dn) flatMap {  //TODO if remocoveMissing is false, only get attribute in entry (we don't care of others)
        case None =>
          applyAdd(new AddRequest(entry.backed))
        case Some(existing) =>
          val mods = LDAPEntry.merge(existing,entry, false, removeMissingAttributes, forceKeepMissingAttributes)
          if(!mods.isEmpty) {
            applyModify(new ModifyRequest(entry.dn.toString, mods.asJava))
          } else LDIFNoopChangeRecord(entry.dn).successIOLdap()
      }
    }
  }

  override def delete(dn:DN, recurse:Boolean = true) : IOLdap[Seq[LDIFChangeRecord]] = {
    if(recurse) {
      if(canDeleteTree) {
        import com.unboundid.ldap.sdk.{DeleteRequest,Control}
        import com.unboundid.ldap.sdk.controls.SubtreeDeleteRequestControl
        applyDeletes(List(new DeleteRequest(dn, Array(new SubtreeDeleteRequestControl()):Array[Control])))
      } else {
        searchSub(dn,BuildFilter.ALL,"dn").flatMap { seq =>
          val dns = seq.map(_.dn).toList.sortWith( (a,b) => a.compareTo(b) > 0)
          applyDeletes(dns.map { dn => new DeleteRequest(dn.toString) })
        }
      }
    } else {
      applyDeletes(List(new DeleteRequest(dn.toString)))
    }
  }

  /*
   * //////////////////////////////////////////////////////////////////
   * // Write Tree
   * //////////////////////////////////////////////////////////////////
   */

  protected def addTree(tree:LDAPTree) : IOLdap[Seq[LDIFChangeRecord]] = {
    applyAdds(tree.toSeq.toList.map {e => new AddRequest(e.backed) })
  }

  override def saveTree(tree:LDAPTree, deleteRemoved:Boolean=false) : IOLdap[Seq[LDIFChangeRecord]] = {
    //compose the result of unit modification
    def doSave(tree:Tree[TreeModification]) : IOLdap[Seq[LDIFChangeRecord]] = {
      //utility method to process the good method given the type of modification
      def applyTreeModification(mod:TreeModification) : IOLdap[Seq[LDIFChangeRecord]] = {
        mod match {
          case NoMod => Seq().successIOLdap() //OK
          case Add(tree) => addTree(tree)
          case Delete(tree) =>
            if(deleteRemoved) delete(tree.root.toString, true) //TODO : do we want to actually only try to delete these entry and not cut the full subtree ? likely to be error prone
            else Seq().successIOLdap()
          case Replace((dn,mods)) => mods.toList.traverse { mod => modify(dn,mod) }
        }
      }
      ((Seq().successIOLdap():IOLdap[Seq[LDIFChangeRecord]])/:tree.toSeq) { (records, mod) =>
        records match {
          case Right(seq) => applyTreeModification(mod).map { newRecords =>
            (seq ++ newRecords)
          }
          case x => x
        }
      }
    }
    ldifFileLogger.tree(tree)
    //process mofications
    getTree(tree.root.dn.toString).flatMap {
      case None => addTree(tree)
      case Some(t) => LDAPTree.diff(t, tree, deleteRemoved) match {
        case None => Seq().successIOLdap()
        case Some(treeMod) => treeMod.root match {
          case x:TreeModification => doSave(treeMod.asInstanceOf[Tree[TreeModification]])
          case x => LDAPConnectionError.Rudder("Was hopping for a Tree[TreeModification] and get a Tree with root element: " + x).failureIOLdap()
        }
      }
    }
  }

}
