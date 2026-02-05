/*
 *************************************************************************************
 * Copyright 2025 Normation SAS
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
package com.normation.rudder.reports

import com.normation.errors.IOResult
import com.normation.inventory.domain.NodeId
import com.normation.rudder.domain.reports.NodeConfigId
import com.normation.rudder.reports.execution.*
import org.joda.time.DateTime
import org.junit.runner.RunWith
import zio.Ref
import zio.Scope
import zio.ZIO
import zio.test.*
import zio.test.Assertion.*
import zio.test.junit.ZTestJUnitRunner

/**
 * Tests on the behaviour of the reports execution repository with a cache.
 *
 * First, just a short presentation of the design of the class under test.
 * There are two concrete classes :
 * <code>RoReportsExecutionRepositoryImpl</code> extends the trait <code>RoReportsExecutionRepository</code>
 * <code>CachedReportsExecutionRepository</code> extends the trait <code>RoReportsExecutionRepository</code>
 * <code>CachedReportsExecutionRepository</code> is composed by a property of type <code>RoReportsExecutionRepository</code> which is actually a <code>RoReportsExecutionRepositoryImpl</code> (See in <code>RudderConfig</code> how it is constructed).
 *
 * This structure looks like a composite pattern.
 *
 * A thing to notice is that the class <code>CachedReportsExecutionRepository</code> is a layer on top of <code>RoReportsExecutionRepositoryImpl</code>, <code>CachedReportsExecutionRepository</code> responsibility is to manage the cache stuff. it never accesses to the database layer.
 * <code>CachedReportsExecutionRepository</code> is a cache by extending the trait <code>CachedRepository</code> and managing a private <code>Ref</code>
 * Notice that the effects are made through the underlying repository layer i.e. the property named <code>readBackend</code> of type <b>RoReportsExecutionRepositoryImpl</b>.
 *
 * Secondly, how to test in this design.
 * The map containing the cached data itself <code>cacheRef</code> is not accessible it's a private field of the class.
 * The work-around to make it testable is to use a state: <code>Zio</code> <code>Ref[Int]</code>, in the mock of the underlying repository in order to count the effectful calls, which gives the possibility to verify if some of them are cached or not.
 * Example: by calling twice the method <code>CachedReportsExecutionRepository.getLastNodes</code> with the same input, if the service is cached the count of calls in the underlying repository will be equal to 1.
 */
@RunWith(classOf[ZTestJUnitRunner])
class ReportsExecutionRepositoryCacheImplTest extends ZIOSpecDefault {

  override def spec: Spec[TestEnvironment & Scope, Any] = suite("ReportsExecutionRepositoryCacheImpl")(
    suite("Getting unprocessed doesn't write or read in the cache")(
      test("""it should make actual call for each getUnprocessedRuns call whatever the cache status :
             |     - call getUnprocessedRuns (mock returns node1)  (nbcalls = 1, cache=())
             |     - call getUnprocessedRuns (mock returns node1)  (nbcalls = 1, cache=())
             |     -> number total of calls = 1 + 1 = 2
             |""".stripMargin) {
        for {
          ref      <- Ref.make(0)
          underTest = new CachedReportsExecutionRepository(createUnderlyingRepositoryMock(ref))
          _        <- underTest.getUnprocessedRuns()
          _        <- underTest.getUnprocessedRuns()
          counter  <- ref.get
        } yield assert(counter)(equalTo(2))
      },
      test("""it should make all actual calls when :
             |     - call getUnprocessedRuns (mock returns node1) (nbcalls = 1, cache=())
             |     - call getUnprocessedRuns (mock returns node1) (nbcalls = 1, cache=())
             |     - call getNodesLastRun on node1                (nbcalls = 1, cache=(node1))
             |     - call getUnprocessedRuns (mock returns node1) (nbcalls = 1, cache=(node1))
             |     -> number total of calls = 1 + 1 + 1 + 1 = 4
             |""".stripMargin) {
        for {
          ref      <- Ref.make(0)
          underTest = new CachedReportsExecutionRepository(createUnderlyingRepositoryMock(ref))
          _        <- underTest.getUnprocessedRuns()
          _        <- underTest.getUnprocessedRuns()
          _        <- underTest.getNodesLastRun(Set(stubNodeId1))
          _        <- underTest.getUnprocessedRuns()
          counter  <- ref.get
        } yield assert(counter)(equalTo(4))
      }
    ),
    suite("Getting nodes and uncompliance doesn't read the cache")(
      test("""it should make actual call for each getNodesAndUncomputedCompliance call whatever the cache status
             |""".stripMargin) {
        for {
          ref      <- Ref.make(0)
          underTest = new CachedReportsExecutionRepository(createUnderlyingRepositoryMock(ref))
          _        <- underTest.getNodesAndUncomputedCompliance() // 1 call
          _        <- underTest.getNodesAndUncomputedCompliance() // 1 call
          _         = underTest.clearCache()
          _        <- underTest.getNodesAndUncomputedCompliance() // 1 call
          counter  <- ref.get
        } yield assert(counter)(equalTo(3))
      },
      test("""it should make actual call and write in the cache when getNodesAndUncomputedCompliance :
             |      - call getNodesAndUncomputedCompliance  (nbcalls = 1, cache=(node1))
             |      - call getNodesAndUncomputedCompliance  (nbcalls = 1, cache=(node1))
             |      - call getNodesLastRun                  (nbcalls = 0, cache=(node1))
             |      - call getNodesLastRun                  (nbcalls = 0, cache=(node1))
             |      -> number total of calls = 1 + 1 + 0 + 0 = 2
             |""".stripMargin) {
        for {
          ref      <- Ref.make(0)
          underTest = new CachedReportsExecutionRepository(createUnderlyingRepositoryMock(ref))
          _        <- underTest.getNodesAndUncomputedCompliance() // 1 call
          _        <- underTest.getNodesAndUncomputedCompliance() // 1 call
          _        <- underTest.getNodesLastRun(Set(stubNodeId1)) // 0 call (reads node1 from cached)
          _        <- underTest.getNodesLastRun(Set(stubNodeId1)) // 0 call (reads node1 from cached)
          counter  <- ref.get
        } yield assert(counter)(equalTo(2))
      },
      test("""it should return the result returned by the service plus the cached values :
             |      - call getNodesLastRun on node2                         (nbcalls = 1, cache=(node2))
             |      - call getNodesLastRun on node2 and node3               (nbcalls = 1, cache=(node2, node3))
             |      - call getNodesAndUncomputedCompliance : returns node1  (nbcalls = 1, cache=(node2, node3, node1))
             |      -> number total of calls = 3
             |      -> cache = (node2, node3, node1)
             |      -> result = node1
             |""".stripMargin) {
        for {
          ref      <- Ref.make(0)
          underTest = new CachedReportsExecutionRepository(createUnderlyingRepositoryMock(ref))
          _        <- underTest.getNodesLastRun(Set(stubNodeId2))              // 1 call (write node2 in cache)
          _        <- underTest.getNodesLastRun(Set(stubNodeId2, stubNodeId3)) // 1 call (reads node2 from cached, write node3 in cache)
          result   <- underTest.getNodesAndUncomputedCompliance()              // 1 call and return node1 and write node1 in cache
          counter  <- ref.get
        } yield {
          val expectedResult = Map((stubNodeId1, Some(dummyAgentRun)))
          assert((result, counter))(equalTo((expectedResult, 3)))
        }
      }
    ),
    suite("Getting nodes last run")(
      test("""it should make actual call when empty the cache before the call
             |""".stripMargin) {
        for {
          ref      <- Ref.make(0)
          underTest = new CachedReportsExecutionRepository(createUnderlyingRepositoryMock(ref))
          _        <- underTest.getNodesAndUncomputedCompliance() // 1 call, put node1 in cache
          _         = underTest.clearCache()
          _        <-
            underTest.getNodesLastRun(nodeIds = Set(stubNodeId1, stubNodeId2, stubNodeId3)) // 3 calls because cache is empty
          counter  <- ref.get
        } yield assert(counter)(equalTo(4))
      },
      test("""it should make 4 actual call when :
             |      - node1 is cached when calling getNodesAndUncomputedCompliance (nbcalls = 1, cache=(node1))
             |      - clear the cache                                              (nbcalls = 0, cache=())
             |      - calling getNodesLastRun on node1, node2, node3               (nbcalls = 3, cache=())
             |      -> number total of calls = 1 + 0 + 3 = 4 (for node1, node1, node2, node3)
             | """.stripMargin) {
        for {
          ref      <- Ref.make(0)
          underTest = new CachedReportsExecutionRepository(createUnderlyingRepositoryMock(ref))
          _        <- underTest.getNodesAndUncomputedCompliance() // 1 call, put dummyNode1 in cache
          _         = underTest.clearCache()
          _        <-
            underTest.getNodesLastRun(nodeIds = Set(stubNodeId1, stubNodeId2, stubNodeId3)) // 3 calls because cache is empty
          counter  <- ref.get
        } yield assert(counter)(equalTo(4))
      },
      test("""it should make 3 calls instead of 4 when :
             |      - calling getNodesAndUncomputedCompliance, node1 is cached                       (nbcalls = 1, cache=(node1))
             |      - calling getNodesLastRun on node1, node2 and node3 (node1 is cached in memory)  (nbcalls = 2, cache=(node1))
             |      -> number total of calls = 1 + 2 = 3 (for node1, node2, node3)
             | """.stripMargin) {
        for {
          ref      <- Ref.make(0)
          underTest = new CachedReportsExecutionRepository(createUnderlyingRepositoryMock(ref))
          _        <- underTest.getNodesAndUncomputedCompliance() // 1 call, put node1 in cache
          _        <- underTest.getNodesLastRun(nodeIds = Set(stubNodeId1, stubNodeId2, stubNodeId3))
          counter  <- ref.get
        } yield assert(counter)(equalTo(3)) // total number of calls is 3, it would be 4 without cache
      },
      test("""it should make 3 actual calls and read 3 results from cache instead of 6 actual calls when :
             |      - calling getNodesLastRun on node4, node5, node6  (nbcalls = 3, cache=(node1, node2, node3))
             |      - calling getNodesLastRun on node4, node5, node6  (nbcalls = 0, cache=(node1, node2, node3))
             |      -> number total of calls = 3 + 0 (for node1, node2, node3)
             | """.stripMargin) {
        for {
          ref      <- Ref.make(0)
          underTest = new CachedReportsExecutionRepository(createUnderlyingRepositoryMock(ref))
          _        <- underTest.getNodesLastRun(nodeIds = Set(stubNodeId4, stubNodeId5, stubNodeId6))
          _        <- underTest.getNodesLastRun(nodeIds = Set(stubNodeId4, stubNodeId5, stubNodeId6))
          counter  <- ref.get
        } yield assert(counter)(equalTo(3)) // total number of calls is 3, it would be 6 without cache
      },
      test("""it should make 6 calls instead of 6 when :
             |      - calling getNodesLastRun on node1, node2, node3  (nbcalls = 3, cache=(node1, node2, node3))
             |      - clear the cache                                 (nbcalls = 0, cache=())
             |      - calling getNodesLastRun on node1, node2, node3  (nbcalls = 3, cache=(node1, node2, node3))
             |      -> number total of calls = 3 + 3 (for node1, node2, node3 then again node1, node2, node3)
             | """.stripMargin) {
        for {
          ref      <- Ref.make(0)
          underTest = new CachedReportsExecutionRepository(createUnderlyingRepositoryMock(ref))
          _        <- underTest.getNodesLastRun(nodeIds = Set(stubNodeId1, stubNodeId2, stubNodeId3))
          _         = underTest.clearCache()
          _        <- underTest.getNodesLastRun(nodeIds = Set(stubNodeId1, stubNodeId2, stubNodeId3))
          counter  <- ref.get
        } yield assert(counter)(equalTo(6)) // total number of calls is 6, cache cleared
      }
    ),
    suite("ZIO modify behaviour")(test("""should accumulate runs in cache and return the runs
                                         |""".stripMargin) {
      val initialCache: Map[NodeId, Some[AgentRunWithNodeConfig]] = Map((stubNodeId3, Some(dummyAgentRun)))
      val stubMap = Map((stubNodeId1, Some(dummyAgentRun)), (stubNodeId2, Some(dummyAgentRun)))

      for {
        ref           <- Ref.Synchronized.make(initialCache)
        result        <- ref.modifyZIO(cache => ZIO.succeed(stubMap).map(runs => (runs, cache ++ runs))) // (result, modifiedCache)
        modifiedCache <- ref.get
      } yield {
        val expectedCache  = Map(
          (stubNodeId3, Some(dummyAgentRun)),
          (stubNodeId1, Some(dummyAgentRun)),
          (stubNodeId2, Some(dummyAgentRun))
        )
        val expectedResult = stubMap
        assert((result, modifiedCache))(equalTo((expectedResult, expectedCache)))
      }
    })
  )

  private def createUnderlyingRepositoryMock(counterRef: Ref[Int]) = new RoReportsExecutionRepository {

    override def getNodesLastRun(nodeIds: Set[NodeId]): IOResult[Map[NodeId, Option[AgentRunWithNodeConfig]]] = for {
      _     <- counterRef.update(_ + nodeIds.size)
      dummy <- ZIO.succeed(nodeIds.zipWithIndex.map { case (k, _) => (k, Some(dummyAgentRun)) }.toMap)
    } yield dummy

    override def getNodesAndUncomputedCompliance(): ZIO[Any, Nothing, Map[NodeId, Some[AgentRunWithNodeConfig]]] = for {
      _     <- counterRef.update(_ + 1)
      dummy <- ZIO.succeed(stubNode1)
    } yield dummy

    override def getUnprocessedRuns(): IOResult[Seq[AgentRunWithoutCompliance]] = for {
      _     <- counterRef.update(_ + 1)
      dummy <- ZIO.succeed(dummyUnprocessedRuns)
    } yield dummy
  }

  private val stubNodeId1 = NodeId("nodeId1")
  private val stubNodeId2 = NodeId("nodeId2")
  private val stubNodeId3 = NodeId("nodeId3")
  private val stubNodeId4 = NodeId("nodeId4")
  private val stubNodeId5 = NodeId("nodeId5")
  private val stubNodeId6 = NodeId("nodeId6")

  private val dummyAgentRunId      = AgentRunId(stubNodeId1, DateTime.now())
  private val dummyNodeConfigId    = Some(NodeConfigId("some-dummy-node-config-id"))
  private val dummyInsertionId     = 1L
  private val dummyInsertionDate   = DateTime.now()
  private val dummyUnprocessedRuns = Seq(
    AgentRunWithoutCompliance(
      agentRunId = dummyAgentRunId,
      nodeConfigVersion = dummyNodeConfigId,
      insertionId = dummyInsertionId,
      insertionDate = dummyInsertionDate
    )
  )

  private val dummyAgentRun = AgentRunWithNodeConfig(
    agentRunId = dummyAgentRunId,
    nodeConfigVersion = None,
    insertionId = 0L
  )

  private val stubNode1 = Map((stubNodeId1, Some(dummyAgentRun)))
}
