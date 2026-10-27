import com.project.Automated_Platform.entity.Execution;
import com.project.Automated_Platform.entity.status.ExecutionStatus;
import com.project.Automated_Platform.repository.ExecutionRepository;
import com.project.Automated_Platform.service.ExecutionRecoveryService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class ExecutionRecoveryServiceTest {
    @Mock
    private ExecutionRepository executionRepository;

    @InjectMocks
    private ExecutionRecoveryService recoveryService;

    @Test
    void shouldMarkExpiredExecutionAsFailed() {
        Execution execution = mock(Execution.class);

        when(execution.getId()).thenReturn(1L);
        when(execution.getWorkerId()).thenReturn("worker-1");

        when(executionRepository.findByStatus(ExecutionStatus.RUNNING))
                .thenReturn(List.of(execution));

        when(executionRepository.recoverExpiredExecution(
                eq(1L),
                eq(ExecutionStatus.RUNNING),
                eq(ExecutionStatus.FAILED),
                any(),
                eq("worker lease expired"),
                eq("worker-1")
        )).thenReturn(1);

        recoveryService.recoverInterruptedExecutions();

        verify(executionRepository).recoverExpiredExecution(
                eq(1L),
                eq(ExecutionStatus.RUNNING),
                eq(ExecutionStatus.FAILED),
                any(),
                eq("worker lease expired"),
                eq("worker-1")
        );
    }

    @Test
    void shouldLeaveExecutionUnchangedWhenLeaseHasNotExpired() {
        Execution execution = mock(Execution.class);

        when(execution.getId()).thenReturn(2L);
        when(execution.getWorkerId()).thenReturn("worker-2");

        when(executionRepository.findByStatus(ExecutionStatus.RUNNING))
                .thenReturn(List.of(execution));

        when(executionRepository.recoverExpiredExecution(
                eq(2L),
                eq(ExecutionStatus.RUNNING),
                eq(ExecutionStatus.FAILED),
                any(),
                eq("worker lease expired"),
                eq("worker-2")
        )).thenReturn(0);

        recoveryService.recoverInterruptedExecutions();

        verify(executionRepository).recoverExpiredExecution(
                eq(2L),
                eq(ExecutionStatus.RUNNING),
                eq(ExecutionStatus.FAILED),
                any(),
                eq("worker lease expired"),
                eq("worker-2")
        );
    }
}
