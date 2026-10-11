package com.project.Automated_Platform.service.execution;

import com.project.Automated_Platform.service.ExecutionEngine;
import com.project.Automated_Platform.service.ExecutionQueue;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Component
public class ExecutionWorker {

    private static final Logger log = LoggerFactory.getLogger(ExecutionWorker.class);

    @Autowired
    private ExecutionQueue executionQueue;

    @Autowired
    private ExecutionEngine executionEngine;

    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    @PostConstruct
    public void startWorker() {
        worker.submit(this::processExecution);
    }

    private void processExecution() {

        while(!Thread.currentThread().isInterrupted()) {
            try {
                Long executionId = executionQueue.take();

                log.info("processing execution {}", executionId);

                executionEngine.execute(executionId);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                log.error("Worker encountered an error", e);
            }
        }
    }

    @PreDestroy
    public void shutdown() {
        worker.shutdownNow();
    }


}
