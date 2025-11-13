package com.normation.rudder.reports

import com.normation.errors.IOResult
import com.normation.inventory.domain.NodeId
import com.normation.rudder.domain.reports.NodeConfigId
import com.normation.rudder.reports.execution.*
import org.joda.time.DateTime
import zio.Ref
import zio.Scope
import zio.ZIO
import zio.test.*
import zio.test.Assertion.*

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
object ReportsExecutionRepositoryCacheImplTest extends ZIOSpecDefault {

  override def spec: Spec[TestEnvironment & Scope, Any] = suite("ReportsExecutionRepositoryCacheImpl")(
    suite("Getting unprocessed doesn't update the cache")(
      test("it should make actual call for each getUnprocessedRuns call whatever the cache status") {
        for {
          ref      <- Ref.make(0)
          underTest = new CachedReportsExecutionRepository(createUncachedReportMock(ref))
          _        <- underTest.getUnprocessedRuns()
          _        <- underTest.getUnprocessedRuns()
          counter  <- ref.get
        } yield assert(counter)(equalTo(2))
      }
    ),
    suite("Getting nodes and uncompliance doesn't update the cache")(
      test("it should make actual call for each getNodesAndUncomputedCompliance call whatever the cache status") {
        for {
          ref      <- Ref.make(0)
          underTest = new CachedReportsExecutionRepository(createUncachedReportMock(ref))
          _        <- underTest.getNodesAndUncomputedCompliance() // 1 call
          _        <- underTest.getNodesAndUncomputedCompliance() // 1 call
          _         = underTest.clearCache()
          _        <- underTest.getNodesAndUncomputedCompliance() // 1 call
          counter  <- ref.get
        } yield assert(counter)(equalTo(3))
      }
    ),
    suite("Getting nodes last run")(
      test("it should make actual call when empty the cache before the call") {
        for {
          ref      <- Ref.make(0)
          underTest = new CachedReportsExecutionRepository(createUncachedReportMock(ref))
          _        <- underTest.getNodesAndUncomputedCompliance() // 1 call, put dummyNode1 in cache
          _         = underTest.clearCache()
          _        <-
            underTest.getNodesLastRun(nodeIds = Set(dummyNodeId1, dummyNodeId2, dummyNodeId3)) // 3 calls because cache is empty
          counter  <- ref.get
        } yield assert(counter)(equalTo(4))
      },
      test(
        """it should make 4 actual call when :
           |    - node1 is cached when calling getNodesAndUncomputedCompliance (nbcalls = 1, cache=(node1))
           |    - clear the cache                                              (nbcalls = 0, cache=())
           |    - calling getNodesLastRun on node1, node2, node3               (nbcalls = 3, cache=())
           |    -> number total of calls = 1 + 0 + 3 = 4 (for node1, node1, node2, node3)
           | """.stripMargin) {
        for {
          ref      <- Ref.make(0)
          underTest = new CachedReportsExecutionRepository(createUncachedReportMock(ref))
          _        <- underTest.getNodesAndUncomputedCompliance() // 1 call, put dummyNode1 in cache
          _         = underTest.clearCache()
          _        <-
            underTest.getNodesLastRun(nodeIds = Set(dummyNodeId1, dummyNodeId2, dummyNodeId3)) // 3 calls because cache is empty
          counter  <- ref.get
        } yield assert(counter)(equalTo(4))
      },
      test(
        """it should make 3 calls instead of 4 when :
          |     - calling getNodesAndUncomputedCompliance, node1 is cached                       (nbcalls = 1, cache=(node1))
          |     - calling getNodesLastRun on node1, node2 and node3 (node1 is cached in memory)  (nbcalls = 2, cache=(node1))
          |     -> number total of calls = 1 + 2 = 3 (for node1, node2, node3)
          | """.stripMargin) {
        for {
          ref      <- Ref.make(0)
          underTest = new CachedReportsExecutionRepository(createUncachedReportMock(ref))
          _        <- underTest.getNodesAndUncomputedCompliance() // 1 call, put dummyNode1 in cache
          _        <- underTest.getNodesLastRun(nodeIds = Set(dummyNodeId1, dummyNodeId2, dummyNodeId3))
          counter  <- ref.get
        } yield assert(counter)(equalTo(3)) // total number of calls is 3, it would be 4 without cache
      },
      test(
        """it should make 3 calls instead of 6 when :
          |     - calling getNodesLastRun on node4, node5, node6  (nbcalls = 3, cache=(node1, node2, node3))
          |     - calling getNodesLastRun on node4, node5, node6  (nbcalls = 0, cache=(node1, node2, node3))
          |     -> number total of calls = 3 + 0 (for node1, node2, node3)
          | """.stripMargin) {
        for {
          ref      <- Ref.make(0)
          underTest = new CachedReportsExecutionRepository(createUncachedReportMock(ref))
          _        <- underTest.getNodesLastRun(nodeIds = Set(dummyNodeId4, dummyNodeId5, dummyNodeId6))
          _        <- underTest.getNodesLastRun(nodeIds = Set(dummyNodeId4, dummyNodeId5, dummyNodeId6))
          counter  <- ref.get
        } yield assert(counter)(equalTo(3)) // total number of calls is 3, it would be 6 without cache
      },
      test(
        """it should make 6 calls instead of 6 when :
          |     - calling getNodesLastRun on node1, node2, node3  (nbcalls = 3, cache=(node1, node2, node3))
          |     - clear the cache                                 (nbcalls = 0, cache=())
          |     - calling getNodesLastRun on node1, node2, node3  (nbcalls = 3, cache=(node1, node2, node3))
          |     -> number total of calls = 3 + 3 (for node1, node2, node3 then again node1, node2, node3)""".stripMargin) {
        for {
          ref      <- Ref.make(0)
          underTest = new CachedReportsExecutionRepository(createUncachedReportMock(ref))
          _        <- underTest.getNodesLastRun(nodeIds = Set(dummyNodeId1, dummyNodeId2, dummyNodeId3))
          _        = underTest.clearCache()
          _        <- underTest.getNodesLastRun(nodeIds = Set(dummyNodeId1, dummyNodeId2, dummyNodeId3))
          counter  <- ref.get
        } yield assert(counter)(equalTo(6)) // total number of calls is 6, cache cleared
      }
    )
  )

  private def createUncachedReportMock(counterRef: Ref[Int]): RoReportsExecutionRepository = new RoReportsExecutionRepository {
    override def getNodesLastRun(nodeIds: Set[NodeId]): IOResult[Map[NodeId, Option[AgentRunWithNodeConfig]]] = {
      for {
        _     <- counterRef.update(_ + nodeIds.size)
        dummy <- ZIO.succeed(nodeIds.zipWithIndex.map{ case (k,_) => (k,Some(dummyAgentRunWithNodeConfig)) }.toMap)
      } yield dummy
    }

    override def getNodesAndUncomputedCompliance(): ZIO[Any, Nothing, Map[NodeId, Some[AgentRunWithNodeConfig]]] = for {
      _     <- counterRef.update(_ + 1)
      dummy <- ZIO.succeed(dummyNode1)
    } yield dummy

    override def getUnprocessedRuns(): IOResult[Seq[AgentRunWithoutCompliance]] = {
      for {
        _     <- counterRef.update(_ + 1)
        dummy <- ZIO.succeed(dummyUnprocessedRuns)
      } yield dummy
    }
  }

  private val dummyAgentRunId      = AgentRunId(NodeId("some-dummy-node-id"), DateTime.now())
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

  private val dummyNodeId1 = NodeId("nodeId1")
  private val dummyNodeId2 = NodeId("nodeId2")
  private val dummyNodeId3 = NodeId("nodeId3")
  private val dummyNodeId4 = NodeId("nodeId4")
  private val dummyNodeId5 = NodeId("nodeId5")
  private val dummyNodeId6 = NodeId("nodeId6")

  private val dummyAgentRunWithNodeConfig = AgentRunWithNodeConfig(
    agentRunId = dummyAgentRunId,
    nodeConfigVersion = None,
    insertionId = 0L
  )
  private val dummyNode1 = Map((dummyNodeId1, Some(dummyAgentRunWithNodeConfig)))
}
