package com.project.Automated_Platform.service;

import com.project.Automated_Platform.entity.*;
import com.project.Automated_Platform.repository.*;
import com.project.Automated_Platform.service.execution.NodeExecutionContext;
import com.project.Automated_Platform.service.execution.NodeExecutionResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ExecutionEngineParallelTest {

    @Mock private NodeExecutor nodeExecutor;
    @Mock private ObjectMapper objectMapper;
    @Mock private NodeRepository nodeRepository;
    @Mock private ExecutionRepository executionRepository;
    @Mock private NodeConnectionRepository nodeConnectionRepository;
    @Mock private NodeExecutionRepository nodeExecutionRepository;

    @InjectMocks
    private ExecutionEngine executionEngine;

    private Workflow workflow;
    private Execution execution;
    private final AtomicBoolean leaseValid = new AtomicBoolean(true);
    private final String workerId = "test-worker";

    // Graph:
    //   MANUAL_TRIGGER (1)
    //        ├── DELAY_A (2)
    //        └── DELAY_B (3)
    //              └── both → MERGE (4)
    private Node trigger;
    private Node delayA;
    private Node delayB;
    private Node merge;

    private NodeConnection tToA;
    private NodeConnection tToB;
    private NodeConnection aToMerge;
    private NodeConnection bToMerge;

    @BeforeEach
    void setUp() {
        workflow = new Workflow();
        workflow.setId(10L);
        workflow.setName("parallel-test");

        execution = new Execution();
        execution.setId(100L);
        execution.setWorkflow(workflow);
        execution.setStatus(ExecutionStatus.RUNNING);
        execution.setTriggerType("MANUAL");
        execution.setWorkerId(workerId);

        trigger = node(1L, "Trigger", NodeType.MANUAL_TRIGGER);
        delayA  = node(2L, "DelayA",  NodeType.DELAY);
        delayB  = node(3L, "DelayB",  NodeType.DELAY);
        merge   = node(4L, "Merge",   NodeType.MERGE);

        tToA     = connection(11L, trigger, delayA,  null);
        tToB     = connection(12L, trigger, delayB,  null);
        aToMerge = connection(13L, delayA,  merge,   null);
        bToMerge = connection(14L, delayB,  merge,   null);

        stubGraph();
        stubNodeExecutionPersistence();

        when(executionRepository.findById(100L)).thenReturn(Optional.of(execution));
        when(executionRepository.completeExecution(
                anyLong(), any(), anyString(), any(), any(), anyString(), any(), any()))
                .thenReturn(1);
        when(objectMapper.valueToTree(any())).thenReturn(null);
    }

    // ------------------------------------------------------------------
    // 1. Parallel branches really overlap in time
    // ------------------------------------------------------------------
    @Test
    void parallelDelayNodes_shouldOverlapInTime() throws Exception {
        AtomicInteger currentlyRunning = new AtomicInteger(0);
        AtomicInteger maxConcurrent    = new AtomicInteger(0);
        CountDownLatch bothStarted     = new CountDownLatch(2);

        when(nodeExecutor.execute(any(Node.class), any(NodeExecutionContext.class), anyString()))
                .thenAnswer(inv -> {
                    Node n = inv.getArgument(0);
                    NodeExecutionContext ctx = inv.getArgument(1);

                    if (n.getType() == NodeType.MANUAL_TRIGGER) {
                        ctx.setNodeOutputItems(n.getId(), List.of(new LinkedHashMap<>()));
                        return new NodeExecutionResult("trigger ok", 1, null);
                    }

                    if (n.getType() == NodeType.DELAY) {
                        int running = currentlyRunning.incrementAndGet();
                        maxConcurrent.updateAndGet(prev -> Math.max(prev, running));
                        bothStarted.countDown();

                        Thread.sleep(250);   // give the other branch time to start

                        currentlyRunning.decrementAndGet();
                        ctx.setNodeOutputItems(n.getId(), List.of(Map.of("from", n.getName())));
                        return new NodeExecutionResult("delayed", 1, null);
                    }

                    if (n.getType() == NodeType.MERGE) {
                        ctx.setNodeOutputItems(n.getId(), List.of(Map.of("merged", true)));
                        return new NodeExecutionResult("merge ok", 1, null);
                    }

                    return new NodeExecutionResult("ok", 1, null);
                });

        long start = System.currentTimeMillis();
        executionEngine.execute(100L, workerId, leaseValid);
        long elapsed = System.currentTimeMillis() - start;

        // Both delays must have been alive at the same moment
        assertTrue(maxConcurrent.get() >= 2,
                "Expected ≥2 concurrent nodes, got " + maxConcurrent.get());

        // Wall time should be ~one delay, not two sequential
        assertTrue(elapsed < 700,
                "Took too long (" + elapsed + " ms) – probably sequential");

        verify(nodeExecutor, times(1)).execute(eq(trigger), any(), anyString());
        verify(nodeExecutor, times(1)).execute(eq(delayA),  any(), anyString());
        verify(nodeExecutor, times(1)).execute(eq(delayB),  any(), anyString());
        verify(nodeExecutor, times(1)).execute(eq(merge),   any(), anyString());
    }

    // ------------------------------------------------------------------
    // 2. Merge waits for BOTH parallel parents
    // ------------------------------------------------------------------
    @Test
    void mergeNode_shouldWaitForBothParallelParents() {
        List<String> executionOrder = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch delaysFinished = new CountDownLatch(2);

        when(nodeExecutor.execute(any(Node.class), any(NodeExecutionContext.class), anyString()))
                .thenAnswer(inv -> {
                    Node n = inv.getArgument(0);
                    NodeExecutionContext ctx = inv.getArgument(1);

                    executionOrder.add(n.getName());

                    if (n.getType() == NodeType.MANUAL_TRIGGER) {
                        ctx.setNodeOutputItems(n.getId(), List.of(new LinkedHashMap<>()));
                        return new NodeExecutionResult("trigger", 1, null);
                    }
                    if (n.getType() == NodeType.DELAY) {
                        try { Thread.sleep(80); } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                        delaysFinished.countDown();
                        ctx.setNodeOutputItems(n.getId(), List.of(Map.of("branch", n.getName())));
                        return new NodeExecutionResult("delay done", 1, null);
                    }
                    if (n.getType() == NodeType.MERGE) {
                        assertEquals(0, delaysFinished.getCount(),
                                "Merge started before both parallel parents finished");
                        ctx.setNodeOutputItems(n.getId(), List.of(Map.of("merged", true)));
                        return new NodeExecutionResult("merge done", 1, null);
                    }
                    return new NodeExecutionResult("ok", 1, null);
                });

        executionEngine.execute(100L, workerId, leaseValid);

        assertEquals("Trigger", executionOrder.get(0));

        int idxA = executionOrder.indexOf("DelayA");
        int idxB = executionOrder.indexOf("DelayB");
        int idxM = executionOrder.indexOf("Merge");

        assertTrue(idxA > 0, "DelayA never ran. Order was: " + executionOrder);
        assertTrue(idxB > 0, "DelayB never ran. Order was: " + executionOrder);
        assertTrue(idxM > idxA && idxM > idxB,
                "Merge must run after both delays. Order was: " + executionOrder);
    }

    // ------------------------------------------------------------------
    // 3. Nodes that become ready together are submitted in one wave
    // ------------------------------------------------------------------
    @Test
    void readyNodesInSameWave_areSubmittedConcurrently() {
        // simpler graph – no merge
        when(nodeRepository.findAll()).thenReturn(List.of(trigger, delayA, delayB));

        // override connection stubs for the simpler graph
        when(nodeConnectionRepository.findBySourceNodeId(anyLong())).thenAnswer(inv -> {
            long id = inv.getArgument(0);
            if (id == 1L) return List.of(tToA, tToB);
            return List.of();
        });
        when(nodeConnectionRepository.findByTargetNodeId(anyLong())).thenAnswer(inv -> {
            long id = inv.getArgument(0);
            if (id == 1L) return List.of();
            if (id == 2L) return List.of(tToA);
            if (id == 3L) return List.of(tToB);
            return List.of();
        });

        AtomicInteger concurrent = new AtomicInteger(0);
        AtomicInteger maxSeen    = new AtomicInteger(0);

        when(nodeExecutor.execute(any(Node.class), any(NodeExecutionContext.class), anyString()))
                .thenAnswer(inv -> {
                    Node n = inv.getArgument(0);
                    NodeExecutionContext ctx = inv.getArgument(1);

                    if (n.getType() == NodeType.DELAY) {
                        int c = concurrent.incrementAndGet();
                        maxSeen.updateAndGet(p -> Math.max(p, c));
                        try { Thread.sleep(150); } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                        concurrent.decrementAndGet();
                    }

                    ctx.setNodeOutputItems(n.getId(), List.of(new LinkedHashMap<>()));
                    return new NodeExecutionResult(n.getName() + " ok", 1, null);
                });

        executionEngine.execute(100L, workerId, leaseValid);

        assertTrue(maxSeen.get() >= 2,
                "DelayA and DelayB should run in parallel (max concurrent = " + maxSeen.get() + ")");
    }

    // ------------------------------------------------------------------
    // Helpers – robust stubs
    // ------------------------------------------------------------------

    private void stubGraph() {
        when(nodeRepository.findAll()).thenReturn(List.of(trigger, delayA, delayB, merge));

        // Outgoing connections – answer by node id so any Long/long works
        when(nodeConnectionRepository.findBySourceNodeId(anyLong())).thenAnswer(inv -> {
            long id = inv.getArgument(0);
            return switch ((int) id) {
                case 1 -> List.of(tToA, tToB);
                case 2 -> List.of(aToMerge);
                case 3 -> List.of(bToMerge);
                default -> List.of();
            };
        });

        // Incoming connections
        when(nodeConnectionRepository.findByTargetNodeId(anyLong())).thenAnswer(inv -> {
            long id = inv.getArgument(0);
            return switch ((int) id) {
                case 1 -> List.of();
                case 2 -> List.of(tToA);
                case 3 -> List.of(tToB);
                case 4 -> List.of(aToMerge, bToMerge);
                default -> List.of();
            };
        });
    }

    private void stubNodeExecutionPersistence() {
        AtomicInteger neId = new AtomicInteger(1000);

        when(nodeExecutionRepository.createNodeExecutionIfOwned(
                anyLong(), anyLong(), anyString(), any(), any(), any()))
                .thenAnswer(inv -> (long) neId.incrementAndGet());

        when(nodeExecutionRepository.findById(anyLong()))
                .thenAnswer(inv -> {
                    NodeExecution ne = new NodeExecution();
                    ne.setId(inv.getArgument(0));
                    ne.setAttempts(0);
                    return Optional.of(ne);
                });

        when(nodeExecutionRepository.updateResultIfOwned(
                anyLong(), anyLong(), anyString(), any(), any(),
                any(), anyInt(), anyString(), any(), any()))
                .thenReturn(1);
    }

    private Node node(Long id, String name, NodeType type) {
        Node n = new Node();
        n.setId(id);
        n.setName(name);
        n.setType(type);
        n.setWorkflow(workflow);
        return n;
    }

    private NodeConnection connection(Long id, Node source, Node target, String branch) {
        NodeConnection c = new NodeConnection();
        c.setId(id);
        c.setSourceNode(source);
        c.setTargetNode(target);
        c.setBranch(branch);
        return c;
    }
}