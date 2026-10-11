package com.project.Automated_Platform;

import com.project.Automated_Platform.service.execution.NodeExecutionContext;
import org.junit.jupiter.api.Test;

import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

class ConcurrentExecutionContextIsolationTest {

    @Test
    void concurrentExecutionsMustNotShareContextData()
            throws Exception {

        ExecutorService pool = Executors.newFixedThreadPool(2);

        try {
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch start = new CountDownLatch(1);

            Callable<String> executionA = () -> {
                NodeExecutionContext context =
                        new NodeExecutionContext();

                context.getData().put("message", "RUN-A");
                ready.countDown();

                assertTrue(start.await(5, TimeUnit.SECONDS));

                return (String) context.getData().get("message");
            };

            Callable<String> executionB = () -> {
                NodeExecutionContext context =
                        new NodeExecutionContext();

                context.getData().put("message", "RUN-B");
                ready.countDown();

                assertTrue(start.await(5, TimeUnit.SECONDS));

                return (String) context.getData().get("message");
            };

            Future<String> resultA = pool.submit(executionA);
            Future<String> resultB = pool.submit(executionB);

            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();

            assertEquals("RUN-A", resultA.get(5, TimeUnit.SECONDS));
            assertEquals("RUN-B", resultB.get(5, TimeUnit.SECONDS));

        } finally {
            pool.shutdownNow();
        }
    }
}
