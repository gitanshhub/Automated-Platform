package com.project.Automated_Platform;



import com.project.Automated_Platform.entity.Execution;
import com.project.Automated_Platform.entity.ExecutionStatus;
import com.project.Automated_Platform.entity.Workflow;
import com.project.Automated_Platform.repository.ExecutionRepository;
import com.project.Automated_Platform.repository.WorkflowRepository;

import com.project.Automated_Platform.service.ExecutionRecoveryService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Transactional
class ExecutionRecoveryIntegrationTest {

    @Autowired
    private ExecutionRepository executionRepository;

    @Autowired
    private WorkflowRepository workflowRepository;

    @Autowired
    private ExecutionRecoveryService recoveryService;

    @Test
    void persistedRunningExecutionMustBeMarkedFailedAfterRecovery() {
        // Arrange: persist a workflow and an interrupted execution.
        Workflow workflow = new Workflow();
        workflow.setName("recovery-integration-test");
        workflow.setDescription("Workflow for recovery integration test");

        Workflow savedWorkflow = workflowRepository.saveAndFlush(workflow);

        Execution execution = new Execution();
        execution.setWorkflow(savedWorkflow);
        execution.setStatus(ExecutionStatus.RUNNING);

        Execution savedExecution = executionRepository.saveAndFlush(execution);
        Long executionId = savedExecution.getId();

        assertEquals(
                ExecutionStatus.RUNNING,
                executionRepository.findById(executionId)
                        .orElseThrow()
                        .getStatus()
        );

        // Act: run recovery.
        recoveryService.recoverInterruptedExecutions();

        // Assert: reload the record from the repository.
        Execution recoveredExecution = executionRepository.findById(executionId)
                .orElseThrow();

        assertEquals(ExecutionStatus.FAILED, recoveredExecution.getStatus());
        assertNotNull(recoveredExecution.getFinishedAt());
        assertTrue(
                recoveredExecution.getResult().contains("Execution interrupted"),
                "Recovery reason should be persisted"
        );
    }
}

