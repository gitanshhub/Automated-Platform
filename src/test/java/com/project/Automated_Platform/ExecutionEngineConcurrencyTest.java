package com.project.Automated_Platform;


import com.project.Automated_Platform.entity.*;
import com.project.Automated_Platform.repository.*;
import com.project.Automated_Platform.service.ExecutionEngine;
import com.project.Automated_Platform.service.NodeExecutor;
import com.project.Automated_Platform.service.execution.NodeExecutionContext;
import com.project.Automated_Platform.service.execution.NodeExecutionResult;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ExecutionEngineConcurrencyTest {

    @Test
    void siblingNodesMustStartConcurrently() throws Exception {
        ExecutionRepository executionRepository = mock(ExecutionRepository.class);
        NodeRepository nodeRepository = mock(NodeRepository.class);
        NodeConnectionRepository connectionRepository =
                mock(NodeConnectionRepository.class);
        NodeExecutionRepository nodeExecutionRepository =
                mock(NodeExecutionRepository.class);
        NodeExecutor nodeExecutor = mock(NodeExecutor.class);
        WorkflowRepository workflowRepository = mock(WorkflowRepository.class);

        ExecutionEngine engine = new ExecutionEngine();

        ReflectionTestUtils.setField(engine, "executionRepository", executionRepository);
        ReflectionTestUtils.setField(engine, "nodeRepository", nodeRepository);
        ReflectionTestUtils.setField(engine, "nodeConnectionRepository", connectionRepository);
        ReflectionTestUtils.setField(engine, "nodeExecutionRepository", nodeExecutionRepository);
        ReflectionTestUtils.setField(engine, "nodeExecutor", nodeExecutor);
        ReflectionTestUtils.setField(engine, "workflowRepository", workflowRepository);

        Workflow workflow = new Workflow();
        workflow.setId(1L);

        Execution execution = new Execution();
        execution.setId(100L);
        execution.setWorkflow(workflow);
        execution.setStatus(ExecutionStatus.QUEUED);

        Node root = createNode(1L, "root", NodeType.MANUAL_TRIGGER, workflow);
        Node siblingA = createNode(2L, "sibling-A", NodeType.TRANSFORM, workflow);
        Node siblingB = createNode(3L, "sibling-B", NodeType.TRANSFORM, workflow);

        NodeConnection connectionA = new NodeConnection();
        connectionA.setId(11L);
        connectionA.setSourceNode(root);
        connectionA.setTargetNode(siblingA);

        NodeConnection connectionB = new NodeConnection();
        connectionB.setId(12L);
        connectionB.setSourceNode(root);
        connectionB.setTargetNode(siblingB);

        when(executionRepository.findById(100L))
                .thenReturn(Optional.of(execution));
        when(executionRepository.save(any(Execution.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(nodeExecutionRepository.save(any(NodeExecution.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        when(nodeRepository.findAll())
                .thenReturn(List.of(root, siblingA, siblingB));

        when(connectionRepository.findBySourceNodeId(1L))
                .thenReturn(List.of(connectionA, connectionB));
        when(connectionRepository.findBySourceNodeId(2L))
                .thenReturn(List.of());
        when(connectionRepository.findBySourceNodeId(3L))
                .thenReturn(List.of());

        when(connectionRepository.findByTargetNodeId(1L))
                .thenReturn(List.of());
        when(connectionRepository.findByTargetNodeId(2L))
                .thenReturn(List.of(connectionA));
        when(connectionRepository.findByTargetNodeId(3L))
                .thenReturn(List.of(connectionB));

        when(nodeExecutor.execute(eq(root), any(NodeExecutionContext.class)))
                .thenReturn(new NodeExecutionResult("root completed", 0, null));

        CountDownLatch siblingsStarted = new CountDownLatch(2);
        CountDownLatch allowSiblingsToFinish = new CountDownLatch(1);
        AtomicBoolean bothStartedBeforeRelease = new AtomicBoolean(false);

        when(nodeExecutor.execute(
                argThat(node -> node != null && node.getId().equals(2L)),
                any(NodeExecutionContext.class)))
                .thenAnswer(invocation -> {
                    siblingsStarted.countDown();

                    if (siblingsStarted.await(3, TimeUnit.SECONDS)) {
                        bothStartedBeforeRelease.set(true);
                    }

                    if (!allowSiblingsToFinish.await(5, TimeUnit.SECONDS)) {
                        throw new RuntimeException("Timed out waiting for sibling release");
                    }

                    return new NodeExecutionResult("A completed", 0, null);
                });

        when(nodeExecutor.execute(
                argThat(node -> node != null && node.getId().equals(3L)),
                any(NodeExecutionContext.class)))
                .thenAnswer(invocation -> {
                    siblingsStarted.countDown();

                    if (!allowSiblingsToFinish.await(5, TimeUnit.SECONDS)) {
                        throw new RuntimeException("Timed out waiting for sibling release");
                    }

                    return new NodeExecutionResult("B completed", 0, null);
                });

        Thread releaseThread = new Thread(() -> {
            try {
                if (siblingsStarted.await(3, TimeUnit.SECONDS)) {
                    allowSiblingsToFinish.countDown();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        releaseThread.start();

        try {
            engine.execute(100L);
        } finally {
            allowSiblingsToFinish.countDown();
            releaseThread.join(2000);
        }

        assertTrue(
                bothStartedBeforeRelease.get(),
                "Both sibling nodes must start before either is released to finish"
        );

        assertEquals(ExecutionStatus.SUCCESS, execution.getStatus());
        assertNotNull(execution.getFinishedAt());

        verify(nodeExecutor).execute(
                eq(siblingA), any(NodeExecutionContext.class));
        verify(nodeExecutor).execute(
                eq(siblingB), any(NodeExecutionContext.class));
    }

    private Node createNode(
            Long id, String name, NodeType type, Workflow workflow) {
        Node node = new Node();
        node.setId(id);
        node.setName(name);
        node.setType(type);
        node.setWorkflow(workflow);
        return node;
    }
}
