package com.project.Automated_Platform.service;


import com.project.Automated_Platform.entity.Execution;
import com.project.Automated_Platform.entity.ExecutionStatus;
import com.project.Automated_Platform.repository.ExecutionRepository;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
@AllArgsConstructor
@NoArgsConstructor
public class ExecutionRecoveryService {

    @Autowired
    private ExecutionRepository executionRepository;


    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void recoverInterruptedExecutions() {
        List<Execution> interruptedExecution = executionRepository.findByStatus(ExecutionStatus.RUNNING);

        for(Execution execution : interruptedExecution) {
            execution.setStatus(ExecutionStatus.FAILED);
            execution.setFinishedAt(LocalDateTime.now());
            execution.setResult("Execution interrupted");

            executionRepository.save(execution);
        }
    }


}
