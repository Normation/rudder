/*
*************************************************************************************
* Copyright 2011 Normation SAS
*************************************************************************************
*
* This program is free software: you can redistribute it and/or modify
* it under the terms of the GNU Affero General Public License as
* published by the Free Software Foundation, either version 3 of the
* License, or (at your option) any later version.
*
* In accordance with the terms of section 7 (7. Additional Terms.) of
* the GNU Affero GPL v3, the copyright holders add the following
* Additional permissions:
* Notwithstanding to the terms of section 5 (5. Conveying Modified Source
* Versions) and 6 (6. Conveying Non-Source Forms.) of the GNU Affero GPL v3
* licence, when you create a Related Module, this Related Module is
* not considered as a part of the work and may be distributed under the
* license agreement of your choice.
* A "Related Module" means a set of sources files including their
* documentation that, without modification of the Source Code, enables
* supplementary functions or services in addition to those offered by
* the Software.
*
* This program is distributed in the hope that it will be useful,
* but WITHOUT ANY WARRANTY; without even the implied warranty of
* MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
* GNU Affero General Public License for more details.
*
* You should have received a copy of the GNU Affero General Public License
* along with this program. If not, see <http://www.gnu.org/licenses/agpl.html>.
*
*************************************************************************************
*/

package com.normation.rudder.repository.xml

import org.junit.runner.RunWith
import org.specs2.mutable._
import org.specs2.matcher._
import org.specs2.runner.JUnitRunner
import net.liftweb.common.Loggable
import org.specs2.specification.Fragments
import org.specs2.specification.Step
import net.liftweb.common._
import org.apache.commons.io.FileUtils
import java.io.File
import org.eclipse.jgit.storage.file.FileRepositoryBuilder
import org.eclipse.jgit.api.Git

@RunWith(classOf[JUnitRunner])
class TestGitFindUtils extends Specification with Loggable {
  
  ////////// set up / clean-up and utilities //////////
  
  lazy val gitRoot = new File("/tmp/test-jgit/filters", System.currentTimeMillis.toString)
  
  override def map(fs: => Fragments) = fs ^ Step(deleteDirectory)

  def deleteDirectory = {
    logger.debug("Deleting directory " + gitRoot.getAbsoluteFile)
    try {
      FileUtils.deleteDirectory(gitRoot)   
    } catch {
      case e:Exception => anError
    }
  }
  
  
  //create a file with context = its name, creating its parent dir if needed
  def mkfile(relativePath:String, name:String) = {
    val d = new File(gitRoot, relativePath)
    d.mkdirs
    FileUtils.writeStringToFile(new File(d,name), name)
  }
  

  ////////// init test directory structure //////////
  
  //init the file layout
  val all =
    ("a"      , "root.txt") ::
    ("a"      , "root.pdf") ::
    ("a/a"    , "f.txt")    ::
    ("b"      , "root.txt") ::
    ("b"      , "root.pdf") ::
    ("b/a"    , "f.txt")    ::
    ("b/a"    , "f.plop")    ::
    ("x/f.txt", "f.txt")    ::
    Nil
  
  
  //build
  all.foreach { case (path, file) =>
    mkfile(path, file)
  }
  
  val allPaths = all.map { case(d,f) => d+"/"+f }
  val allPdf = all.collect { case(d,f) if f.endsWith(".pdf") => d+"/"+f }
  val allTxt = all.collect { case(d,f) if f.endsWith(".txt") => d+"/"+f }
  val allDirA  = all.collect { case(d,f) if d.startsWith("a") => d+"/"+f }
  val allDirAA = all.collect { case(d,f) if d.startsWith("a/a") => d+"/"+f }
  val allDirB  = all.collect { case(d,f) if d.startsWith("b") => d+"/"+f }
  val allDirX  = List("x/f.txt/f.txt")
  
  val db = (new FileRepositoryBuilder).setWorkTree(gitRoot).build
  if(!db.getConfig.getFile.exists) {
    db.create()
  }
  val git = new Git(db)
  git.add.addFilepattern(".").call
  val id = GitFindUtils.findRevTreeFromRevString(db, git.commit.setMessage("initial commit").call.name).open_!

  def list(rootDirectories:List[String], endPaths: List[String]) = 
    GitFindUtils.listFiles(db, id, rootDirectories, endPaths)
    
  ////////// actual tests //////////

  "the walk" should {
    "return all results when no filter provided" in {
      list(Nil,Nil) must haveTheSameElementsAs(allPaths)
    }

    "return all results when all dir are provided as filter" in {
      list(List("a", "b", "x"),Nil) must haveTheSameElementsAs(allPaths)
    }

    "return all results when all extension are provided" in {
      list(Nil,List("pdf", "txt", "plop")) must haveTheSameElementsAs(allPaths)
    }

    "return only files under a when filter for 'a'" in {
      list(List("a"),Nil) must haveTheSameElementsAs(allDirA)
    }
    
    "return only files under a when filter for a/" in {
      list(List("a/"),Nil) must haveTheSameElementsAs(allDirA)
    }

    "return only files under a when filter for a/a" in {
      list(List("a/a"),Nil) must haveTheSameElementsAs(allDirAA)
    }

    "return only files under a when filter for 'b'" in {
      list(List("b"),Nil) must haveTheSameElementsAs(allDirB)
    }

    "return both files under 'a' and 'b'" in {
      list(List("a", "b"), Nil) must haveTheSameElementsAs(allDirA ++ allDirB)
    }

    "return all .txt" in {
      list(Nil, List(".txt")) must haveTheSameElementsAs(allTxt)
    }

    "return all .txt and .pdf" in {
      list(Nil, List(".txt", "pdf")) must haveTheSameElementsAs(allTxt ++ allPdf)
    }

    "return x/f.txt/f.txt" in {
      list(List("x"), List(".txt")) must haveTheSameElementsAs(allDirX)
    }

    "return nothing" in {
      list(List("x"), List("plop")) must beEmpty
    }
    
    "ignore empty path" in {
      list(List("x", ""), Nil) must haveTheSameElementsAs(allDirX)
    }
    
    "ignore empty extension" in {
      list(Nil, List("txt", "")) must haveTheSameElementsAs(allTxt)
    }
    
  }

}