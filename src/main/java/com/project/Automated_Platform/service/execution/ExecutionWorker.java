package com.project.Automated_Platform.service.execution;

import com.project.Automated_Platform.entity.ExecutionStatus;
import com.project.Automated_Platform.repository.ExecutionRepository;
import com.project.Automated_Platform.service.ExecutionEngine;
import com.project.Automated_Platform.service.ExecutionQueue;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
public class ExecutionWorker {

    private static final Logger log = LoggerFactory.getLogger(ExecutionWorker.class);

    @Autowired
    private ExecutionQueue executionQueue;

    @Autowired
    private ExecutionEngine executionEngine;

    @Autowired
    private ExecutionRepository executionRepository;

    private final String workerId = UUID.randomUUID().toString();

    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    private final ScheduledExecutorService heartBeatScheduler = Executors.newSingleThreadScheduledExecutor();

    @PostConstruct
    public void startWorker() {
        worker.submit(this::processExecution);

    }

    private void processExecution() {

        while (!Thread.currentThread().isInterrupted()) {
            try {
                System.out.println("Current Thread: " + Thread.currentThread() + "\n"+ "Active Thread Count: " + Thread.activeCount());

                Long executionId = executionQueue.take();

                LocalDateTime leaseExpiresAt = LocalDateTime.now().plusMinutes(2);

                int claimed = executionRepository.claimExecution(
                        executionId,
                        ExecutionStatus.QUEUED,
                        ExecutionStatus.RUNNING,
                        workerId,
                        leaseExpiresAt
                );

                System.out.println("Claimed: " + claimed);

                if (claimed == 0) {
                    log.info("skipping execution {} because it is no longer QUEUED", executionId);
                    continue;
                }

                log.info("worker {} Claimed execution {}", workerId, executionId);

                AtomicBoolean leaseValid = new AtomicBoolean(true);
                ScheduledFuture<?> heartbeat = heartBeatScheduler.scheduleAtFixedRate(
                        () -> {
                            try {
                                LocalDateTime now = LocalDateTime.now();

                                int renewed = executionRepository.renewLease(
                                        executionId,
                                        ExecutionStatus.RUNNING,
                                        workerId,
                                        now,
                                        now.plusMinutes(2)
                                );

                                if (renewed == 0) {
                                    leaseValid.set(false);
                                    log.error("Lost lease for execution {}", executionId);
                                }

                            } catch (Exception e) {
                                leaseValid.set(false);
                                log.error("Error renewing lease for execution {}", executionId, e);
                            }
                        }, 30, 30, TimeUnit.SECONDS
                );

                try {
                    executionEngine.execute(executionId, workerId, leaseValid);
                } finally {
                    heartbeat.cancel(false);
                }

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;

            } catch (Exception e) {
                log.error("Worker encountered an error", e);
            }
        }
    }


    @PreDestroy
    public void shutdown() {

        log.info("shutting down execution worker{}", workerId);

        worker.shutdown();
        heartBeatScheduler.shutdown();

        try {
            if(!worker.awaitTermination(30, TimeUnit.SECONDS)) {
                log.warn("Execution worker {} did not stop within 30 seconds", workerId);

                worker.shutdownNow();
            }

            if(!heartBeatScheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                heartBeatScheduler.shutdownNow();
            }

        } catch (InterruptedException e) {
            worker.shutdownNow();
            heartBeatScheduler.shutdownNow();

            Thread.currentThread().interrupt();
        }



    }


}
