/*
 *************************************************************************************
 * Copyright 2017 Normation SAS
 *************************************************************************************
 *
 * This file is part of Rudder.
 *
 * Rudder is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * In accordance with the terms of section 7 (7. Additional Terms.) of
 * the GNU General Public License version 3, the copyright holders add
 * the following Additional permissions:
 * Notwithstanding to the terms of section 5 (5. Conveying Modified Source
 * Versions) and 6 (6. Conveying Non-Source Forms.) of the GNU General
 * Public License version 3, when you create a Related Module, this
 * Related Module is not considered as a part of the work and may be
 * distributed under the license agreement of your choice.
 * A "Related Module" means a set of sources files including their
 * documentation that, without modification of the Source Code, enables
 * supplementary functions or services in addition to those offered by
 * the Software.
 *
 * Rudder is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Rudder.  If not, see <http://www.gnu.org/licenses/>.

 *
 *************************************************************************************
 */

package com.normation.rudder.ncf

import better.files.File
import com.normation.errors.*
import com.normation.inventory.domain.Version
import com.normation.rudder.batch.UpdateCompilationStatus
import com.normation.rudder.hooks.CmdResult
import com.normation.zio.UnsafeRun
import net.liftweb.actor.MockLiftActor
import org.junit.runner.RunWith
import org.specs2.mutable.Specification
import org.specs2.runner.JUnitRunner
import org.specs2.specification.BeforeAfterAll
import zio.*
import zio.syntax.*

@RunWith(classOf[JUnitRunner])
class TestTechniqueCompilationCache extends Specification with BeforeAfterAll {

  sequential

  /*
   * Reading things
   */
  private val compilationDir = File.newTemporaryDirectory("rudder-test-technique-compilation-service")

  private val technique1 = EditorTechnique(
    BundleName("tech1"),
    new Version("1.0"),
    "technique 1",
    "",
    Nil,
    "",
    "",
    Seq.empty,
    Seq.empty,
    Map.empty,
    None
  )

  private val technique2 = technique1.copy(id = BundleName("tech2"), name = "technique 2")
  private val technique3 = technique1.copy(id = BundleName("tech3"), name = "technique 3")

  private val techniques = List(technique1, technique2, technique3)

  private val editorTechniqueReader: EditorTechniqueReader = new EditorTechniqueReader {
    override def readTechniquesMetadataFile
        : IOResult[(List[EditorTechnique], Map[BundleName, GenericMethod], List[RudderError])] = {
      (techniques, Map.empty[BundleName, GenericMethod], List.empty[RudderError]).succeed
    }
    override def getMethodsMetadata:        IOResult[Map[BundleName, GenericMethod]] = ???
    override def updateMethodsMetadataFile: IOResult[CmdResult]                      = ???

  }
  private val techniqueCompiler:     TechniqueCompiler     = new TechniqueCompiler {

    override def compileTechnique(technique: EditorTechnique): IOResult[TechniqueCompilationOutput] = {
      fakeCompilationOutput
        .apply(technique)
        .succeed
    }

    override def getCompilationOutputFile(technique: EditorTechnique): File = {
      compilationDir / s"${technique.id.value}"
    }

    override def getCompilationConfigFile(technique: EditorTechnique): File = ???

  }

  private val readTechniqueActiveStatus: ReadEditorTechniqueActiveStatus = new ReadEditorTechniqueActiveStatus {
    override def getActiveStatus(id: BundleName): IOResult[Option[TechniqueActiveStatus]]          = Some(
      TechniqueActiveStatus.Enabled
    ).succeed
    override def getActiveStatuses():             IOResult[Map[BundleName, TechniqueActiveStatus]] =
      techniques.map(_.id -> TechniqueActiveStatus.Enabled).toMap.succeed
  }

  // the SUT
  private val compilationStatusService: ReadEditorTechniqueCompilationResult = new TechniqueCompilationStatusService(
    editorTechniqueReader,
    techniqueCompiler
  )

  private val fakeCompilationOutput: PartialFunction[EditorTechnique, TechniqueCompilationOutput] = {
    case technique if technique.id == technique1.id =>
      TechniqueCompilationOutput(
        TechniqueCompilerApp.Rudderc,
        101,
        Chunk.empty,
        "err1",
        "stdout",
        "tech1 error"
      )
    case technique if technique.id == technique2.id =>
      TechniqueCompilationOutput(
        TechniqueCompilerApp.Rudderc,
        0,
        Chunk.empty,
        "success",
        "stdout",
        ""
      )
    case technique if technique.id == technique3.id =>
      TechniqueCompilationOutput(
        TechniqueCompilerApp.Rudderc,
        2,
        Chunk.empty,
        "err3",
        "stdout",
        "tech3 error"
      )
  }

  val msgLock            = Semaphore.make(1).runNow
  // sync with actor
  private val mockActor  = new MockLiftActor {
    override def !(param: Any): Unit = msgLock
      .withPermit(
        super.!(param).succeed
      )
      .runNow
  }
  private val writeCache =
    TechniqueCompilationErrorsActorSync.make(mockActor, compilationStatusService, readTechniqueActiveStatus).runNow

  // create output test files for each technique
  override def beforeAll(): Unit = {
    import TechniqueCompilationIO.*
    import zio.json.yaml.*
    ZIO
      .foreach(techniques)(t => fakeCompilationOutput.apply(t).toYaml().toIO.map(compilationDir.createChild(t.id.value).write(_)))
      .runNow
  }

  val expectedListOfOutputs:     List[EditorTechniqueCompilationResult] = List(
    EditorTechniqueCompilationResult(technique1.id, technique1.version, technique1.name, CompilationResult.Error("tech1 error")),
    EditorTechniqueCompilationResult(technique2.id, technique2.version, technique2.name, CompilationResult.Success),
    EditorTechniqueCompilationResult(technique3.id, technique3.version, technique3.name, CompilationResult.Error("tech3 error"))
  )
  val expectedCompilationStatus: CompilationStatusErrors                = {
    CompilationStatusErrors(
      NonEmptyChunk(
        EditorTechniqueError(
          technique1.id,
          technique1.version,
          TechniqueActiveStatus.Enabled,
          technique1.name,
          "tech1 error"
        ),
        EditorTechniqueError(
          technique3.id,
          technique3.version,
          TechniqueActiveStatus.Enabled,
          technique3.name,
          "tech3 error"
        )
      )
    )
  }

  override def afterAll(): Unit = {
    compilationDir.delete()
  }

  "ReadEditorTechniqueCompilationResult" should {
    "read with cumulated errors" in {
      compilationStatusService.get().runNow must beEqualTo(expectedListOfOutputs)
    }
  }

  "Error repository" should {
    "Correctly build the status" in {
      writeCache.updateStatus(expectedListOfOutputs.map(_ -> TechniqueActiveStatus.Enabled)).runNow must beEqualTo(
        expectedCompilationStatus
      )
    }

    val newError       = {
      EditorTechniqueCompilationResult(
        BundleName("new tech"),
        technique1.version,
        "new tech",
        CompilationResult.Error("new tech error")
      )
    }
    val newErrorStatus = CompilationStatusErrors(
      NonEmptyChunk(
        EditorTechniqueError(
          newError.id,
          newError.version,
          TechniqueActiveStatus.Enabled,
          newError.name,
          "new tech error"
        )
      )
    )
    "sync with UI" in {
      (writeCache.syncOne(newError) *> // println(mockActor.messages.head).succeed *>
      msgLock.withPermit(
        (mockActor hasReceivedMessage_? UpdateCompilationStatus(expectedCompilationStatus ++ newErrorStatus)).succeed
      )).runNow.aka("actor received message") must beTrue
      mockActor.messageCount must beEqualTo(1)
    }

    "update an existing technique in error" in {
      val updatedResult = {
        EditorTechniqueCompilationResult(
          technique3.id,
          technique3.version,
          technique3.name,
          CompilationResult.Error("new tech3 error")
        )
      }
      writeCache.updateOneStatus(updatedResult, TechniqueActiveStatus.Enabled).runNow must beEqualTo(
        CompilationStatusErrors(
          NonEmptyChunk(
            EditorTechniqueError(
              technique1.id,
              technique1.version,
              TechniqueActiveStatus.Enabled,
              technique1.name,
              "tech1 error"
            ),
            EditorTechniqueError(
              technique3.id,
              technique3.version,
              TechniqueActiveStatus.Enabled,
              technique3.name,
              "new tech3 error"
            )
          )
        ) ++ newErrorStatus
      )
    }

    "update with an technique in success" in {
      val success =
        EditorTechniqueCompilationResult(technique1.id, technique1.version, technique1.name, CompilationResult.Success)
      writeCache.updateOneStatus(success, TechniqueActiveStatus.Enabled).runNow must beEqualTo(
        CompilationStatusErrors(
          NonEmptyChunk(
            EditorTechniqueError(
              technique3.id,
              technique3.version,
              TechniqueActiveStatus.Enabled,
              technique3.name,
              "new tech3 error"
            )
          )
        ) ++ newErrorStatus
      )
    }

    "delete no longer existing techniques, keep only new one" in {
      writeCache.updateStatus(List(newError -> TechniqueActiveStatus.Enabled)).runNow must beEqualTo(
        newErrorStatus
      )
    }

    "update a technique with disabled status" in {
      writeCache.updateOneStatus(newError, TechniqueActiveStatus.Disabled).runNow must beEqualTo(
        CompilationStatusErrors(
          newErrorStatus.techniquesInError.map(_.copy(status = TechniqueActiveStatus.Disabled))
        )
      )
    }

    "sync technique active status" in {
      // bring the status back to Enabled
      (writeCache.syncTechniqueActiveStatus(newError.id) *>
      msgLock.withPermit(
        (mockActor hasReceivedMessage_? UpdateCompilationStatus(newErrorStatus)).succeed
      )).runNow.aka("actor received message") must beTrue
      mockActor.messageCount must beEqualTo(2)
    }

    "unsync one" in {
      (writeCache.unsyncOne(newError.id -> newError.version) *>
      msgLock.withPermit(
        (mockActor hasReceivedMessage_? UpdateCompilationStatus(CompilationStatusAllSuccess)).succeed
      )).runNow.aka("actor received message") must beTrue
      mockActor.messageCount must beEqualTo(3)
    }

  }
}
