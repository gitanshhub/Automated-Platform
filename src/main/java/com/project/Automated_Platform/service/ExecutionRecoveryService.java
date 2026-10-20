package com.project.Automated_Platform.service;


import com.project.Automated_Platform.entity.Execution;
import com.project.Automated_Platform.entity.ExecutionStatus;
import com.project.Automated_Platform.repository.ExecutionRepository;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class ExecutionRecoveryService {

    @Autowired
    private ExecutionRepository executionRepository;

    private static final Logger log = LoggerFactory.getLogger(ExecutionRecoveryService.class);


    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void recoverInterruptedExecutions() {
        LocalDateTime now = LocalDateTime.now();

        List<Execution> interruptedExecution = executionRepository.findByStatus(ExecutionStatus.RUNNING);

        for(Execution execution : interruptedExecution) {

            int recovered = executionRepository.recoverExpiredExecution(
                    execution.getId(),
                    ExecutionStatus.RUNNING,
                    ExecutionStatus.FAILED,
                    now,
                    "worker lease expired",
                    execution.getWorkerId()
            );

            if(recovered == 1) {
                log.warn("Recovered abandoned execution {} because its lease expired", execution.getId());
            }
        }

        log.info(
                "Checked {} running execution for expired lease", interruptedExecution.size()
        );
    }


}
