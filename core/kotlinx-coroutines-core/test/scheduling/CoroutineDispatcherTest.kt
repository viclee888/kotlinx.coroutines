/*
 * Copyright 2016-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license.
 */

package kotlinx.coroutines.scheduling

import kotlinx.coroutines.*
import org.junit.*
import org.junit.Test
import java.util.concurrent.atomic.*
import kotlin.test.*

class CoroutineDispatcherTest : SchedulerTestBase() {

    @After
    fun tearDown() {
        schedulerTimeSource = NanoTimeSource
    }

    @Test
    fun testSingleThread() = runBlocking {
        expect(1)
        withContext(coroutineDispatcher) {
            require(Thread.currentThread() is CoroutineScheduler.Worker)
            expect(2)
            val job = async {
                expect(3)
                delay(10)
                expect(4)
            }

            job.await()
            expect(5)
        }

        finish(6)
        checkPoolThreadsCreated(1)
    }

    @Test
    fun testFairScheduling() = runBlocking {
        corePoolSize = 1
        expect(1)

        val outerJob = launch(coroutineDispatcher) {
            val d1 = launch(coroutineDispatcher) { expect(3) }
            val d2 = launch(coroutineDispatcher) { expect(4) }
            val d3 = launch(coroutineDispatcher) { expect(2) }
            listOf(d1, d2, d3).joinAll()
        }

        outerJob.join()
        finish(5)
    }

    @Test
    fun testStealing() = runBlocking {
        corePoolSize = 2
        val flag = AtomicBoolean(false)
        val job = async(context = coroutineDispatcher) {
            expect(1)
            val innerJob = async {
                expect(2)
                flag.set(true)
            }

            while (!flag.get()) {
                Thread.yield() // Block current thread, submitted inner job will be stolen
            }

            innerJob.await()
            expect(3)
        }

        job.await()
        finish(4)
        checkPoolThreadsCreated(2)
    }

    @Test
    fun testNoStealing() = runBlocking {
        corePoolSize = CORES_COUNT
        schedulerTimeSource = TestTimeSource(0L)
        withContext(coroutineDispatcher) {
            val thread = Thread.currentThread()
            val job = async(coroutineDispatcher) {
                assertEquals(thread, Thread.currentThread())
                val innerJob = async(coroutineDispatcher) {
                    assertEquals(thread, Thread.currentThread())
                }
                innerJob.await()
            }

            job.await()
            assertEquals(thread, Thread.currentThread())
        }

        checkPoolThreadsCreated(1..2)
    }

    @Test
    fun testDelay() = runBlocking {
        corePoolSize = 2
        withContext(coroutineDispatcher) {
            expect(1)
            delay(10)
            expect(2)
        }

        finish(3)
        checkPoolThreadsCreated(2)
    }

    @Test
    fun testWithTimeout() = runBlocking {
        corePoolSize = CORES_COUNT
        withContext(coroutineDispatcher) {
            expect(1)
            val result = withTimeoutOrNull(1000) {
                expect(2)
                yield() // yield only now
                "OK"
            }
            assertEquals("OK", result)

            val nullResult = withTimeoutOrNull(1000) {
                expect(3)
                while (true) {
                    yield()
                }
            }

            assertNull(nullResult)
            finish(4)
        }

        checkPoolThreadsCreated(1..CORES_COUNT)
    }

    @Test
    fun testMaxSize() = runBlocking {
        corePoolSize = 1
        maxPoolSize = 4
        (1..10).map { launch(blockingDispatcher.value) { Thread.sleep(100) } }.joinAll()
        checkPoolThreadsCreated(4)
    }

    @Test(timeout = 1_000)
    fun testYield() = runBlocking {
        corePoolSize = 1
        maxPoolSize = 1
        val outerJob = launch(coroutineDispatcher) {
            expect(1)
            val innerJob = launch(coroutineDispatcher) {
                // Do nothing
                expect(3)
            }

            expect(2)
            while (innerJob.isActive) {
                yield()
            }

            expect(4)
            innerJob.join()
        }

        outerJob.join()
        finish(5)
    }

    @Test
    fun testThreadName() = runBlocking {
        val initialCount = Thread.getAllStackTraces().keys.asSequence()
            .count { it is CoroutineScheduler.Worker && it.name.contains("SomeTestName") }
        assertEquals(0, initialCount)
        val dispatcher = ExperimentalCoroutineDispatcher(1, 1, IDLE_WORKER_KEEP_ALIVE_NS, "SomeTestName")
        dispatcher.use {
            launch(dispatcher) {
            }.join()

            val count = Thread.getAllStackTraces().keys.asSequence()
                .count { it is CoroutineScheduler.Worker && it.name.contains("SomeTestName") }
            assertEquals(1, count)
        }

    }
}
