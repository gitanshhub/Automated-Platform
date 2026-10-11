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

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ExecutionEngineConcurrentFailureTest {

    @Test
    void executionMustFailWhenOneSiblingNodeFails() {
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

        Node root = new Node();
        root.setId(1L);
        root.setName("root");
        root.setType(NodeType.MANUAL_TRIGGER);
        root.setWorkflow(workflow);

        Node failing = new Node();
        failing.setId(2L);
        failing.setName("failing");
        failing.setType(NodeType.TRANSFORM);
        failing.setWorkflow(workflow);

        Node successful = new Node();
        successful.setId(3L);
        successful.setName("successful");
        successful.setType(NodeType.TRANSFORM);
        successful.setWorkflow(workflow);

        NodeConnection first = new NodeConnection();
        first.setId(11L);
        first.setSourceNode(root);
        first.setTargetNode(failing);

        NodeConnection second = new NodeConnection();
        second.setId(12L);
        second.setSourceNode(root);
        second.setTargetNode(successful);

        when(executionRepository.findById(100L))
                .thenReturn(Optional.of(execution));
        when(executionRepository.save(any(Execution.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(nodeRepository.findAll())
                .thenReturn(List.of(root, failing, successful));
        when(connectionRepository.findBySourceNodeId(1L))
                .thenReturn(List.of(first, second));
        when(connectionRepository.findBySourceNodeId(2L))
                .thenReturn(List.of());
        when(connectionRepository.findBySourceNodeId(3L))
                .thenReturn(List.of());
        when(connectionRepository.findByTargetNodeId(1L))
                .thenReturn(List.of());
        when(connectionRepository.findByTargetNodeId(2L))
                .thenReturn(List.of(first));
        when(connectionRepository.findByTargetNodeId(3L))
                .thenReturn(List.of(second));
        when(nodeExecutionRepository.save(any(NodeExecution.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        when(nodeExecutor.execute(eq(root), any(NodeExecutionContext.class)))
                .thenReturn(new NodeExecutionResult("root completed", 0, null));
        when(nodeExecutor.execute(eq(failing), any(NodeExecutionContext.class)))
                .thenThrow(new RuntimeException("Simulated sibling failure"));
        when(nodeExecutor.execute(eq(successful), any(NodeExecutionContext.class)))
                .thenReturn(new NodeExecutionResult("sibling completed", 0, null));

        engine.execute(100L);

        assertEquals(ExecutionStatus.FAILED, execution.getStatus());
        assertNotNull(execution.getFinishedAt());
        assertTrue(execution.getResult().contains("ERROR:"));
        verify(nodeExecutor, atLeastOnce())
                .execute(eq(failing), any(NodeExecutionContext.class));
        verify(nodeExecutor, atLeastOnce())
                .execute(eq(successful), any(NodeExecutionContext.class));
    }
}
