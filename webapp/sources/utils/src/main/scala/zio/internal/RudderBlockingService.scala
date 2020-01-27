package zio.internal

import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ForkJoinPool
import java.util.concurrent.ForkJoinWorkerThread
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

import zio.UIO
import zio.ZIO
import zio.blocking.Blocking
import zio.blocking.Blocking.Service

/*
 * Code copied from zio.blocking.internal
 *
 * On writting test, just the two modif below earns 50% of thread churn (thread kill before being reused)
 * and 10% performance (likely because creating a thread is costly).
 * It may not transpose to real use case gain.
 * Interestingly, using scala work stealing thread pool doesn't yield anything (but can lead to deadlock,
 * so we should not used it).
 */
trait RudderBlockingService extends Blocking {
  val blocking: Service[Any] = new Service[Any] {
    val blockingExecutor: UIO[Executor] = ZIO.succeed(RudderExecutor.executor)
  }
}

object RudderExecutor {

  final def fromForkJoinPool(yieldOpCount0: ExecutionMetrics => Int)(
    fjp: ForkJoinPool
  ): Executor =
    new Executor {
      private[this] def metrics0 = new ExecutionMetrics {
        def concurrency: Int = fjp.getParallelism
        def capacity: Int = Int.MaxValue // don't know how to get it from pool
        def size: Int = fjp.getQueuedSubmissionCount
        def workersCount: Int = fjp.getPoolSize()
        def enqueuedCount: Long = -1 // can't know ?
        def dequeuedCount: Long = -1 // can't know ?
      }

      def metrics = Some(metrics0)
      def yieldOpCount = yieldOpCount0(metrics0)
      def submit(runnable: Runnable): Boolean =
        try {
          fjp.execute(runnable)
          true
        } catch {
          case _: RejectedExecutionException => false
        }

      def here = false
    }

  /**
   * A named fork-join pool
   */

  lazy val factory: ForkJoinPool.ForkJoinWorkerThreadFactory = new ForkJoinPool.ForkJoinWorkerThreadFactory() {
    override def newThread(pool: ForkJoinPool): ForkJoinWorkerThread = {
      val worker = ForkJoinPool.defaultForkJoinWorkerThreadFactory.newThread(pool)
      worker.setName("zio-rudder-mix-" + worker.getPoolIndex)
      worker
    }
  }

  /*
   * We must set a max capacity
   */
  lazy val forkJoinPool = {
    // in FJP from jdk8, we can't control liveliness. On one or two processors, we need some room
    val parallelism = Integer.max(4, Runtime.getRuntime.availableProcessors)
    new ForkJoinPool(
        parallelism
      , factory
      , null // default UncaughtExceptionHandler
      , false // asyncMode: set to true for event style workload, better locality
// these are jdk11 specific
//      , 2*parallelism  // corePoolSize, ie thread to keep in the pool. We a lot of blocking tasks
//      , Int.MaxValue // maximumPoolSize: no up limit to number of thread
//      , if(parallelism == 1)  1 else 2 //  minimumRunnable: 1 to ensure liveliness
//      , null // use default for Predicate<? super ForkJoinPool> saturate
//      , 60 //keepAliveTime,
//      , TimeUnit.SECONDS // unit for keepAliveTime
    )
  }
  // the yield count must be relatively small with a common thread pool, else we almost have no
  // parallelism on CPU constraint task: they never yield by themselve, and they don't give a chance to the
  // scheduler to see that parallelism was wanted.

  //
  // WARNING: on all our tests, FJP from JDK8 leads to deadlock with ZIO.
  // Perhaps JDK 11 pool, where livelyness can be specified, would yield another result.
  //
  def fjpExecutor  = fromForkJoinPool(_ => 5000)(forkJoinPool)

  // a unbounded pool
  def blockingExecutor = Executor.fromThreadPoolExecutor(_ => 5000) { // same than for FJP yield
    val corePoolSize  = Math.max(5, Runtime.getRuntime.availableProcessors()/2)
    val maxPoolSize   = 500 // if we exhauste 500 thread, Rudder is most likely dead, no need to take the server with it
    val keepAliveTime = 30*1000L // default is 1000
    val timeUnit      = TimeUnit.MILLISECONDS
    val workQueue     = new SynchronousQueue[Runnable]()
    val threadFactory = new NamedThreadFactory("zio-rudder-mix", true)

    val threadPool = new ThreadPoolExecutor(
        corePoolSize
      , maxPoolSize
      , keepAliveTime
      , timeUnit
      , workQueue
      , threadFactory
    )

    threadPool
  }

  lazy val executor = blockingExecutor

}
