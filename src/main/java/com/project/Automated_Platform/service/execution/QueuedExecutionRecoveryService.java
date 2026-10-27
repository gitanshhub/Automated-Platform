package com.project.Automated_Platform.service.execution;

import com.project.Automated_Platform.entity.Execution;
import com.project.Automated_Platform.entity.status.ExecutionStatus;
import com.project.Automated_Platform.repository.ExecutionRepository;
import com.project.Automated_Platform.service.ExecutionQueue;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import org.slf4j.Logger;

import java.util.List;
import java.util.concurrent.TimeUnit;

@Service
public class QueuedExecutionRecoveryService {

    private static final Logger log = LoggerFactory.getLogger(QueuedExecutionRecoveryService.class);

    @Autowired
    private ExecutionRepository executionRepository;

    @Autowired
    private ExecutionQueue executionQueue;

    @Scheduled(fixedDelay = 30, timeUnit = TimeUnit.SECONDS)
    public void recoveryQueuedExecutions() {
        List<Execution> queuedExecution = executionRepository.findByStatus(ExecutionStatus.QUEUED);

        for(Execution execution : queuedExecution) {
            try {
                executionQueue.submit(execution.getId());

                log.info("Recovered queued execution {}", execution.getId());

            } catch (IllegalStateException e) {
                log.error(
                        "Could not recover queued execution {} because the queue was full",
                        execution.getId()
                );

                break;
            }

            log.info("Queued execution recovery finished. Found {} execution", queuedExecution.size());
        }
    }

}
