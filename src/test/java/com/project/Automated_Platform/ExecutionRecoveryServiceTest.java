package com.project.Automated_Platform;


import com.project.Automated_Platform.entity.Execution;
import com.project.Automated_Platform.entity.ExecutionStatus;
import com.project.Automated_Platform.repository.ExecutionRepository;
import com.project.Automated_Platform.service.ExecutionRecoveryService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ExecutionRecoveryServiceTest {

    @Test
    void runningExecutionsMustBeMarkedFailedOnRecovery() {
        ExecutionRepository repository = mock(ExecutionRepository.class);
        ExecutionRecoveryService recoveryService =
                new ExecutionRecoveryService(repository);

        Execution execution = new Execution();
        execution.setId(100L);
        execution.setStatus(ExecutionStatus.RUNNING);

        when(repository.findByStatus(ExecutionStatus.RUNNING))
                .thenReturn(List.of(execution));

        recoveryService.recoverInterruptedExecutions();

        assertEquals(ExecutionStatus.FAILED, execution.getStatus());
        assertNotNull(execution.getFinishedAt());
        assertTrue(execution.getResult().contains("Execution interrupted"));

        verify(repository).save(execution);
    }

    @Test
    void recoveryMustDoNothingWhenNoExecutionsAreRunning() {
        ExecutionRepository repository = mock(ExecutionRepository.class);
        ExecutionRecoveryService recoveryService =
                new ExecutionRecoveryService(repository);

        when(repository.findByStatus(ExecutionStatus.RUNNING))
                .thenReturn(List.of());

        recoveryService.recoverInterruptedExecutions();

        verify(repository, never()).save(any(Execution.class));
    }
}

