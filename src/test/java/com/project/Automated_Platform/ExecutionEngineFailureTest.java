
package com.project.Automated_Platform;

import com.project.Automated_Platform.entity.*;
import com.project.Automated_Platform.repository.*;
import com.project.Automated_Platform.service.ExecutionEngine;
import com.project.Automated_Platform.service.NodeExecutor;
import com.project.Automated_Platform.service.execution.NodeExecutionContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ExecutionEngineFailureTest {

    @Mock
    private WorkflowRepository workflowRepository;

    @Mock
    private NodeExecutor nodeExecutor;

    @Mock
    private NodeRepository nodeRepository;

    @Mock
    private NodeConnectionRepository nodeConnectionRepository;

    @Mock
    private ExecutionRepository executionRepository;

    @Mock
    private NodeExecutionRepository nodeExecutionRepository;

    @InjectMocks
    private ExecutionEngine executionEngine;

    @Test
    void workflowWithoutStartingNodeMustFailAndRecordFinishTime() {

        Workflow workflow = new Workflow();
        workflow.setId(1L);

        Execution execution = new Execution();
        execution.setId(100L);
        execution.setWorkflow(workflow);
        execution.setStatus(ExecutionStatus.QUEUED);

        Node nodeA = new Node();
        nodeA.setId(10L);
        nodeA.setWorkflow(workflow);

        Node nodeB = new Node();
        nodeB.setId(11L);
        nodeB.setWorkflow(workflow);

        NodeConnection connectionAB = new NodeConnection();
        connectionAB.setId(20L);
        connectionAB.setSourceNode(nodeA);
        connectionAB.setTargetNode(nodeB);

        NodeConnection connectionBA = new NodeConnection();
        connectionBA.setId(21L);
        connectionBA.setSourceNode(nodeB);
        connectionBA.setTargetNode(nodeA);

        when(executionRepository.findById(100L))
                .thenReturn(Optional.of(execution));

        when(nodeRepository.findAll())
                .thenReturn(List.of(nodeA, nodeB));

        when(nodeConnectionRepository.findBySourceNodeId(10L))
                .thenReturn(List.of(connectionAB));

        when(nodeConnectionRepository.findBySourceNodeId(11L))
                .thenReturn(List.of(connectionBA));

        when(nodeConnectionRepository.findByTargetNodeId(10L))
                .thenReturn(List.of(connectionBA));

        when(nodeConnectionRepository.findByTargetNodeId(11L))
                .thenReturn(List.of(connectionAB));

        when(executionRepository.save(any(Execution.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        executionEngine.execute(100L);

        assertEquals(ExecutionStatus.FAILED, execution.getStatus());
        assertEquals("No starting node found", execution.getResult());
        assertNotNull(
                execution.getFinishedAt(),
                "Failed execution must record its finish time"
        );

        verify(executionRepository, atLeastOnce())
                .save(execution);
        verifyNoInteractions(nodeExecutor);
    }

    @Test
    void failedNodeMustBeRetriedAndEventuallyFail() {
        Workflow workflow = new Workflow();
        workflow.setId(1L);

        Execution execution = new Execution();
        execution.setId(100L);
        execution.setWorkflow(workflow);
        execution.setStatus(ExecutionStatus.QUEUED);

        Node node = new Node();
        node.setId(10L);
        node.setName("failing-node");
        node.setType(NodeType.MANUAL_TRIGGER);
        node.setWorkflow(workflow);

        AtomicInteger attempts = new AtomicInteger();

        when(executionRepository.findById(100L))
                .thenReturn(Optional.of(execution));

        when(nodeRepository.findAll())
                .thenReturn(List.of(node));

        when(nodeConnectionRepository.findByTargetNodeId(10L))
                .thenReturn(List.of());

        when(nodeConnectionRepository.findBySourceNodeId(10L))
                .thenReturn(List.of());

        when(nodeExecutor.execute(
                any(Node.class),
                any(NodeExecutionContext.class)))
                .thenAnswer(invocation -> {
                    attempts.incrementAndGet();
                    throw new RuntimeException("Simulated node failure");
                });

        when(executionRepository.save(any(Execution.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        when(nodeExecutionRepository.save(any(NodeExecution.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        executionEngine.execute(100L);

        assertEquals(4, attempts.get(),
                "Node should be attempted once plus three retries");

        assertEquals(ExecutionStatus.FAILED, execution.getStatus());
        assertNotNull(execution.getFinishedAt());
        assertTrue(execution.getResult().contains("ERROR:"));

        verify(nodeExecutionRepository, times(2))
                .save(any(NodeExecution.class));

        verify(executionRepository, atLeastOnce())
                .save(execution);
    }



    @Test
    void executionMustPersistRunningStatusBeforeNodeCompletes() {
        Workflow workflow = new Workflow();
        workflow.setId(1L);

        Execution execution = new Execution();
        execution.setId(100L);
        execution.setWorkflow(workflow);
        execution.setStatus(ExecutionStatus.QUEUED);

        Node node = new Node();
        node.setId(10L);
        node.setName("test-node");
        node.setType(NodeType.MANUAL_TRIGGER);
        node.setWorkflow(workflow);

        when(executionRepository.findById(100L))
                .thenReturn(Optional.of(execution));

        when(nodeRepository.findAll())
                .thenReturn(List.of(node));

        when(nodeConnectionRepository.findByTargetNodeId(10L))
                .thenReturn(List.of());

        when(nodeConnectionRepository.findBySourceNodeId(10L))
                .thenReturn(List.of());

        when(executionRepository.save(any(Execution.class)))
                .thenAnswer(invocation -> {
                    Execution saved = invocation.getArgument(0);

                    if (saved.getStatus() == ExecutionStatus.RUNNING) {
                        assertNotNull(saved.getStartedAt());
                    }

                    return saved;
                });

        when(nodeExecutionRepository.save(any(NodeExecution.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        when(nodeExecutor.execute(any(Node.class), any()))
                .thenReturn(new com.project.Automated_Platform.service.execution
                        .NodeExecutionResult("completed", 0, null));

        executionEngine.execute(100L);

        verify(executionRepository, atLeastOnce()).save(execution);
        assertEquals(ExecutionStatus.SUCCESS, execution.getStatus());
        assertNotNull(execution.getStartedAt());
        assertNotNull(execution.getFinishedAt());
    }
}
