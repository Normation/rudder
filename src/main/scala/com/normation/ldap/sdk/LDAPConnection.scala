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

import com.unboundid.ldap.sdk.{
  Entry,DN,RDN,Attribute,Filter,
  ResultCode,LDAPResult => UnboundidLDAPResult,
  LDAPSearchException,LDAPException,ReadOnlyLDAPRequest,
  Modification,SearchRequest,DeleteRequest,ModifyRequest,AddRequest,ModifyDNRequest
}
import ResultCode._
import com.unboundid.ldif.LDIFChangeRecord
import com.normation.ldap.ldif.{LDIFFileLogger,LDIFNoopChangeRecord}
import net.liftweb.common.{Box,Full,Empty,Failure,ParamFailure}
import scala.collection.JavaConversions._
import com.normation.utils.Control.sequence


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
  def search(sr:SearchRequest) : Seq[LDAPEntry]

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
  def get(dn:DN, attributes:String*) : Box[LDAPEntry]

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
  def search(baseDn:DN, scope:SearchScope, filter:Filter, attributes:String*) : Seq[LDAPEntry] = {
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
  def get(baseDn:DN, filter:Filter, attributes:String*) : Box[LDAPEntry] = {
    searchOne(baseDn, filter, attributes:_*) match {
      case buf if(buf.isEmpty) => Empty
      case buf => Full(buf(0))
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
  def exists(dn:DN) : Boolean = get(dn, "1.1").isDefined

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
  def searchOne(baseDn:DN,filter:Filter, attributes:String*) : Seq[LDAPEntry] = search(baseDn,One,filter,attributes:_*)

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
  def searchSub(baseDn:DN,filter:Filter, attributes:String*) : Seq[LDAPEntry] = search(baseDn,Sub,filter,attributes:_*)
}

trait WriteOnlyEntryLDAPConnection {

  /**
   * Execute a plain modification.
   * Return the actual modification executed if success,
   * the error in other case.
   */
  def modify(dn:DN, modifications:Modification*) : Box[LDIFChangeRecord]

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
  def move(dn:DN,newParentDn:DN, newRDN:Option[RDN] = None) : Box[LDIFChangeRecord]

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
  def save(entry : LDAPEntry, removeMissingAttributes:Boolean=false, forceKeepMissingAttributes:Seq[String] = Seq()) : Box[LDIFChangeRecord]

  /**
   * Delete entry at the given DN
   * If recurse is set to true (default), delete all entry's children before
   * deleting entry.
   * If recurse is set to false, the entry must have zero child to be
   * allowed to be deleted.
   *
   * If no entry has the given DN, nothing is done.
   */
  def delete(dn:DN, recurse:Boolean = true) : Box[Seq[LDIFChangeRecord]]

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
  def getTree(dn:DN) : Box[LDAPTree]
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
  def saveTree(tree:LDAPTree, deleteRemoved:Boolean=false) : Box[Seq[LDIFChangeRecord]]
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

  import org.slf4j.{Logger, LoggerFactory}
  val logger = LoggerFactory.getLogger(classOf[RoLDAPConnection])

  /*
   * //////////////////////////////////////////////////////////////////
   * // Read
   * //////////////////////////////////////////////////////////////////
   */

  override def search(sr:SearchRequest) : Seq[LDAPEntry] = {
    try {
      backed.search(sr).getSearchEntries.map(e => LDAPEntry(e.getDN,e.getAttributes))
    } catch {
      case e:LDAPSearchException if(onlyReportOnSearch(e.getResultCode)) =>
        logger.error("Ignored execption (configured to be ignored)",e)
        e.getSearchEntries().map(e => LDAPEntry(e.getDN,e.getAttributes))
    }
  }

  override def get(dn:DN, attributes:String*) : Box[LDAPEntry] = {
    {
      if(attributes.size == 0) backed.getEntry(dn.toString)
      else backed.getEntry(dn.toString, attributes:_*)
    } match {
      case null => Empty
      case r => Full(LDAPEntry(r.getDN, r.getAttributes))
    }
  }

  /*
   * //////////////////////////////////////////////////////////////////
   * // Read Tree
   * //////////////////////////////////////////////////////////////////
   */

  override def getTree(dn:DN) : Box[LDAPTree] = {
    try {
      val all = backed.search(dn.toString,Sub,BuildFilter.ALL)
      if(all.getEntryCount() > 0) {
        //build the tree
        val tree = LDAPTree(all.getSearchEntries.map(x => LDAPEntry(x)))
        tree
      } else Empty
    } catch {
      //a no such object error simply means that the required LDAP tree is not in the directory
      case e:LDAPSearchException if(NO_SUCH_OBJECT == e.getResultCode) => Empty
      case e:LDAPException => Failure(s"Can not get tree '${dn}': ${e.getMessage}", Full(e), Empty)
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

  import org.slf4j.{Logger, LoggerFactory}
  override val logger = LoggerFactory.getLogger(classOf[RwLDAPConnection])

  /**
   * Ask the directory if it knows how to
   * delete full sub-tree in one command.
   */
  private lazy val canDeleteTree : Boolean =
    backed.getRootDSE.supportsControl(com.unboundid.ldap.sdk.controls.SubtreeDeleteRequestControl.SUBTREE_DELETE_REQUEST_OID)


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
  private def applyMods[MOD <: ReadOnlyLDAPRequest](toLDIFChangeRecord:MOD => LDIFChangeRecord, backendAction: MOD => LDAPResult)(reqs:Seq[MOD]) : Box[Seq[LDIFChangeRecord]] = {
    if(reqs.size < 1) Full(Seq())
    else {
      ldifFileLogger.records(reqs map ( toLDIFChangeRecord (_) ))

      sequence(reqs) { req =>
        applyMod(toLDIFChangeRecord,backendAction)(req)
      }
    }
  }

  private def applyMod[MOD <: ReadOnlyLDAPRequest](toLDIFChangeRecord:MOD => LDIFChangeRecord, backendAction: MOD => LDAPResult)(req:MOD) : Box[LDIFChangeRecord] = {
    val record = toLDIFChangeRecord(req)
    ldifFileLogger.records(Seq(record))
    backendAction(req) match {
      case LDAPResult(SUCCESS,_) => Full(record)
      case LDAPResult(_,m) => Failure(m)
    }
  }


  private[this] def logIgnoredException(dn: => String, action: String, e:Throwable) : Unit = {
      val message = s"Exception ignored (by configuration) when trying to $action entry '$dn'.  Reported exception was: ${e.getMessage}"
      logger.error(message,e)
  }

  private[this] def logException(onlyReportThat: ResultCode => Boolean, dn: => String, action: String) = {
    import scala.util.control.Exception._

    (new Catch( { case e:LDAPException if(onlyReportThat(e.getResultCode)) => true } )).withApply { e =>
      logIgnoredException(dn, action, e)
      new LDAPResult(-1,SUCCESS)
    }
  }

  private val deleteAction = { req:DeleteRequest =>
    logException(onlyReportOnDelete, req.getDN, "delete") { backed.delete(req) }
  }


  /**
   * Specialized version of applyMods for DeleteRequest modification type
   */
  private val applyDeletes = applyMods[DeleteRequest](
      {req:DeleteRequest => req.toLDIFChangeRecord},
      deleteAction
  ) _

  private val addAction = {req:AddRequest =>
     logException(onlyReportOnAdd, req.getDN, "add") { backed.add(req) }
  }

  /**
   * Specialized version of applyMods for AddRequest modification type
   */
  private val applyAdds = applyMods[AddRequest](
      {req:AddRequest => req.toLDIFChangeRecord},
      addAction
  ) _

  private val applyAdd = applyMod[AddRequest](
      {req:AddRequest => req.toLDIFChangeRecord},
      addAction
  ) _

  val modifyAction = {req:ModifyRequest =>
     logException(onlyReportOnModify, req.getDN, "modify") { backed.modify(req) }
  }

  /**
   * Specialized version of applyMods for ModifyRequest modification type
   */
  private val applyModifies = applyMods[ModifyRequest](
      {req:ModifyRequest => req.toLDIFChangeRecord},
      modifyAction
  ) _

  private val applyModify = applyMod[ModifyRequest](
      {req:ModifyRequest => req.toLDIFChangeRecord},
      modifyAction
  ) _

  /**
   * Execute a plain modification.
   * Return the actual modification executed if success,
   * the error in other case.
   */
  override def modify(dn:DN, modifications:Modification*) : Box[LDIFChangeRecord] =
    try {
      applyModify(new ModifyRequest(dn.toString,modifications:_*))
    } catch {
      case e:LDAPException => Failure(s"Can not apply modifiction on '$dn': ${e.getMessage}", Full(e), Empty)
    }

  override def move(dn:DN, newParentDn:DN, newRDN:Option[RDN] = None) : Box[LDIFChangeRecord] = {
    if(
        dn.getParent == newParentDn && (
            newRDN match {
              case None => true
              case Some(rdn) => dn.getRDN == rdn
            }
        )
    ) {
      Full(LDIFNoopChangeRecord(dn))
    } else {
      try {
        applyMod[ModifyDNRequest]({req:ModifyDNRequest => req.toLDIFChangeRecord},
          {req:ModifyDNRequest =>
             logException(onlyReportOnModify, req.getDN, "modify DN") { backed.modifyDN(req) }
          }
        ) (new ModifyDNRequest(dn.toString, newRDN.getOrElse(dn.getRDN).toString, newRDN.isDefined, newParentDn.toString))
      } catch {
        case e:LDAPException => Failure(s"Can not move '${dn}' to new parent '${newParentDn}': ${e.getMessage}", Full(e), Empty)
      }
    }
  }

  override def save(entry : LDAPEntry, removeMissingAttributes:Boolean=false, forceKeepMissingAttributes:Seq[String] = Seq()) : Box[LDIFChangeRecord] = {
    synchronized {
      get(entry.dn) match { //TODO if remocoveMissing is false, only get attribute in entry (we don't care of others)
        case f@Failure(_,_,_) => f
        case Empty =>
          try {
            applyAdd(new AddRequest(entry.backed))
          } catch {
            case e:LDAPException if(onlyReportOnAdd(e.getResultCode)) =>
              logIgnoredException(entry.dn.toString, "add", e)
              Full(LDIFNoopChangeRecord(entry.dn)) //nothing was modified on the repos when such an error occurred
            case e:LDAPException => Failure(s"Can not save (add) '${entry.dn}': ${e.getMessage}", Full(e), Empty)
          }
        case Full(existing) =>
          val mods = LDAPEntry.merge(existing,entry, false, removeMissingAttributes, forceKeepMissingAttributes)
          if(!mods.isEmpty) {
            try {
              applyModify(new ModifyRequest(entry.dn.toString,mods))
            } catch {
              case e:LDAPException if(onlyReportOnModify(e.getResultCode)) =>
                logIgnoredException(entry.dn.toString, "modify", e)
                Full(LDIFNoopChangeRecord(entry.dn)) //nothing was modified on the repos when such an error occurred
              case e:LDAPException => Failure(s"Can not save (modify) '${entry.dn}': ${e.getMessage}", Full(e), Empty)
            }
          } else Full(LDIFNoopChangeRecord(entry.dn))
      }
    }
  }

  override def delete(dn:DN, recurse:Boolean = true) : Box[Seq[LDIFChangeRecord]] = {
    try {
      if(recurse) {
        if(canDeleteTree) {
          import com.unboundid.ldap.sdk.{DeleteRequest,Control}
          import com.unboundid.ldap.sdk.controls.SubtreeDeleteRequestControl
          applyDeletes(Seq(new DeleteRequest(dn, Array(new SubtreeDeleteRequestControl()):Array[Control])))
        } else {
          val dns = searchSub(dn,BuildFilter.ALL,"dn").map( _.dn).toSeq.sortWith( (a,b) => a.compareTo(b) > 0)
          applyDeletes(dns.map { dn => new DeleteRequest(dn.toString) })
        }
      } else {
        applyDeletes(Seq(new DeleteRequest(dn.toString)))
      }
    } catch {
      case e:LDAPException if(NO_SUCH_OBJECT == e.getResultCode) => Full(Seq()) //OK, already deleted
      case e:LDAPException => Failure(s"Can not delete '${dn}': ${e.getMessage}", Full(e), Empty)
    }
  }

  /*
   * //////////////////////////////////////////////////////////////////
   * // Write Tree
   * //////////////////////////////////////////////////////////////////
   */

  protected def addTree(tree:LDAPTree) : Box[Seq[LDIFChangeRecord]] =
    try {
      applyAdds(tree.toSeq.map {e => new AddRequest(e.backed) })
    } catch {
      case e:LDAPException => Failure(s"Can not add tree: ${tree.root().dn}. Reported exception was ${e.getMessage}", Full(e), Empty)
    }

  override def saveTree(tree:LDAPTree, deleteRemoved:Boolean=false) : Box[Seq[LDIFChangeRecord]] = {
    //compose the result of unit modification
    def doSave(tree:Tree[TreeModification]) : Box[Seq[LDIFChangeRecord]] = {
      //utility method to process the good method given the type of modification
      def applyTreeModification(mod:TreeModification) : Box[Seq[LDIFChangeRecord]] = {
        mod match {
          case NoMod => Full(Seq()) //OK
          case Add(tree) => addTree(tree)
          case Delete(tree) =>
            if(deleteRemoved) delete(tree.root.toString, true) //TODO : do we want to actually only try to delete these entry and not cut the full subtree ? likely to be error prone
            else Full(Seq())
          case Replace((dn,mods)) => sequence(mods) { mod => modify(dn,mod) }
        }
      }
      ((Full(Seq()):Box[Seq[LDIFChangeRecord]])/:tree.toSeq) { (records,mod) =>
        records match {
          case Full(seq) => applyTreeModification(mod) match {
            case f@Failure(_,_,_) => f
            case Empty => Full(seq)
            case Full(newRecords) => Full(seq ++ newRecords)
          }
          case x => x
        }
      }
    }
    ldifFileLogger.tree(tree)
    //process mofications
    getTree(tree.root.dn.toString) match {
      case f@Failure(_,_,_) => f
      case Empty => addTree(tree)
      case Full(t) => LDAPTree.diff(t, tree, deleteRemoved) match {
        case None => Full(Seq())
        case Some(treeMod) => treeMod.root match {
          case x:TreeModification => doSave(treeMod.asInstanceOf[Tree[TreeModification]])
          case x => Failure("Was hopping for a Tree[TreeModification] and get a Tree with root element: " + x)
        }
      }
    }
  }

}
