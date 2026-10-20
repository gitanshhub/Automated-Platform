package com.project.Automated_Platform.service;

import com.project.Automated_Platform.entity.*;
import com.project.Automated_Platform.exception.ExecutionLeaseLostExecption;
import com.project.Automated_Platform.repository.*;
import com.project.Automated_Platform.service.execution.ConnectionExecutionStatus;
import com.project.Automated_Platform.service.execution.NodeExecutionContext;
import com.project.Automated_Platform.service.execution.NodeExecutionResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class ExecutionEngine {

    @Autowired
    private NodeExecutor nodeExecutor;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private NodeRepository nodeRepository;

    @Autowired
    private ExecutionRepository executionRepository;

    @Autowired
    private NodeConnectionRepository nodeConnectionRepository;

    private final ExecutorService executorService = Executors.newCachedThreadPool();

    private Logger log = LoggerFactory.getLogger(ExecutionEngine.class);

    @Autowired
    private NodeExecutionRepository nodeExecutionRepository;


    private static final int MAX_RETRIES = 4;

    public void execute(long executionId, String workerId, AtomicBoolean leaseValid) {

        Execution execution = executionRepository.findById(executionId)
                .orElseThrow(() -> new RuntimeException("Execution not found"));

        NodeExecutionContext context = new NodeExecutionContext();

        Workflow workflow = execution.getWorkflow();

        Map<Long, ConnectionExecutionStatus> connectionStatus = new HashMap<>();


        List<Node> nodes = nodeRepository.findAll();

        List<Node> workflowNodes = nodes.stream()
                .filter(node -> node.getWorkflow().getId().equals(workflow.getId()))
                .toList();

        List<NodeConnection> workflowConnections = workflowNodes.stream()
                .flatMap(node -> nodeConnectionRepository.findBySourceNodeId(node.getId()).stream())
                .toList();

        for (NodeConnection connection : workflowConnections) {
            connectionStatus.put(
                    connection.getId(),
                    ConnectionExecutionStatus.PENDING
            );
        }

        String triggerType = execution.getTriggerType(),
                requiredNodeType;

        if ("MANUAL".equals(triggerType)) {
            requiredNodeType = "MANUAL_TRIGGER";
        } else if ("WEBHOOK".equals(triggerType)) {
            requiredNodeType = "WEBHOOK";
        } else requiredNodeType = null;


        Node startNode = null;

        if (requiredNodeType != null) {

            for (Node node : workflowNodes) {
                if (!requiredNodeType.equals(String.valueOf(node.getType()))) {
                    continue;
                }
                List<NodeConnection> inComingConnections = nodeConnectionRepository.findByTargetNodeId(node.getId());
                System.out.println(inComingConnections);

                if (inComingConnections.isEmpty()) {
                    startNode = node;
                    break;
                }

            }
        }

        if (startNode == null) {
            int completed = executionRepository.completeExecution(
                    executionId,
                    ExecutionStatus.RUNNING,
                    workerId,
                    ExecutionStatus.FAILED,
                    LocalDateTime.now(),
                    "No starting node found",
                    LocalDateTime.now()
            );

            if (completed == 0) {
                System.out.println("Could not finalize execution "
                        + executionId
                        + ": worker ownership was lost"
                );
            }

            return;
        }

        Queue<Node> queue = new LinkedList<>();
        Set<Long> Visited = new HashSet<>();

        queue.add(startNode);
        List<String> executeNodes = new ArrayList<>();

        ExecutionStatus finalStatus = ExecutionStatus.SUCCESS;
        String finalResult = "";

        try {
            Map<String, Object> triggerInput = new LinkedHashMap<>();
            if ("WEBHOOK".equals(triggerType)) {

                Map<String, Object> payload = new LinkedHashMap<>();

                if (execution.getTriggerData() != null && !execution.getTriggerData().isBlank()) {
                    try {
                        JsonNode payloadNode = objectMapper.readTree(execution.getTriggerData());

                        Object payloadObj = objectMapper.convertValue(payloadNode, Object.class);

                        if (!(payloadObj instanceof Map<?, ?> payloadMap)) {
                            throw new IllegalArgumentException("Webhook payload must be a json object");
                        }

                        payloadMap.forEach((key, value) ->
                                payload.put(String.valueOf(key), value));

                    } catch (Exception e) {
                        throw new RuntimeException("Failed to read execution trigger data", e);
                    }
                }
                triggerInput.put("payload", payload);
            }

            context.setNodeInput(startNode.getId(), triggerInput);


            while (!queue.isEmpty()) {

                if (!leaseValid.get()) {
                    throw new ExecutionLeaseLostExecption("Execution lease lost; stopping further node scheduling.");
                }

                List<Future<NodeExecutionResult>> futures = new ArrayList<>();
                List<Node> currentNodes = new ArrayList<>();

                while (!queue.isEmpty()) {

                    if (!leaseValid.get()) {
                        throw new ExecutionLeaseLostExecption("Execution lease lost; stopping further node scheduling.");
                    }

                    Node currNode = queue.poll();

                    if (!Visited.add(currNode.getId())) {
                        continue;
                    }

                    currentNodes.add(currNode);

                    Future<NodeExecutionResult> future = executorService.submit(
                            () -> executeNode(currNode, execution, context, leaseValid, workerId)
                    );

                    futures.add(future);
                }

                for (int i = 0; i < futures.size(); i++) {
                    Future<NodeExecutionResult> future = futures.get(i);
                    Node currNode = currentNodes.get(i);

                    try {

                        NodeExecutionResult executionResult = future.get();

                        if (!leaseValid.get()) {
                            for (Future<NodeExecutionResult> pendingFuture : futures) {
                                pendingFuture.cancel(true);
                            }
                            throw new ExecutionLeaseLostExecption("Execution lease lost while waiting for node: " + currNode.getName());
                        }

                        executeNodes.add(currNode.getName() + " -> " + executionResult.getOutput());

                        System.out.println("Node Executed: " + currNode.getName() + " -> " + executionResult.getOutput());

                        List<NodeConnection> connections = nodeConnectionRepository.findBySourceNodeId(currNode.getId());
                        for (NodeConnection connection : connections) {
                            boolean selected = executionResult.getBranch() == null
                                    || executionResult.getBranch().equals(connection.getBranch());

                            connectionStatus.put(
                                    connection.getId(),
                                    selected
                                            ? ConnectionExecutionStatus.ACTIVE
                                            : ConnectionExecutionStatus.SKIPPED
                            );

                            resolveTargetReadiness(connection.getTargetNode(), connectionStatus, queue, context);
                        }

                    } catch (Exception e) {

                        ExecutionLeaseLostExecption leaseLostExecption = null;

                        if (e instanceof ExecutionException
                                && e.getCause() instanceof ExecutionLeaseLostExecption) {
                            leaseLostExecption = (ExecutionLeaseLostExecption) e.getCause();

                        } else if (e instanceof ExecutionLeaseLostExecption) {
                            leaseLostExecption = (ExecutionLeaseLostExecption) e;
                        }

                        if (leaseLostExecption != null) {
                            for (Future<NodeExecutionResult> pendingFuture : futures) {
                                pendingFuture.cancel(true);
                            }
                            throw leaseLostExecption;
                        }

                        throw new RuntimeException(e);
                    }
                }


                finalResult = String.join("\n ", executeNodes);
            }

        } catch (ExecutionLeaseLostExecption e) {

            log.warn("Execution {} stopped because its worker lease was lost", executionId);
            return;

        } catch (Exception e) {
            finalStatus = ExecutionStatus.FAILED;

            finalResult = String.join("\n ", executeNodes) + "\nERROR: " + e.getMessage();
        }

        int completed = executionRepository.completeExecution(
                executionId,
                ExecutionStatus.RUNNING,
                workerId,
                finalStatus,
                LocalDateTime.now(),
                finalResult,
                LocalDateTime.now()
        );

        if (completed == 0) {
            System.out.println("Could not finalize execution "
                    + executionId
                    + ": worker ownership was lost"
            );
        }

    }

    private NodeExecutionResult executeNode(Node node, Execution execution, NodeExecutionContext context, AtomicBoolean leaseValid, String workerId) {
        if (!leaseValid.get()) {
            throw new ExecutionLeaseLostExecption("Execution lease lost; refusing to retry node: " + node.getName());
        }

        LocalDateTime startedAt = LocalDateTime.now();
        Long createdNodeExecution = nodeExecutionRepository.createNodeExecutionIfOwned(
                execution.getId(),
                node.getId(),
                workerId,
                NodeExecutionStatus.RUNNING,
                startedAt,
                LocalDateTime.now()
        );

        if (createdNodeExecution == null) {
            throw new ExecutionLeaseLostExecption(
                    "worker no longer owns execution " + execution.getId()
            );
        }

        NodeExecution nodeExecution = nodeExecutionRepository.findById(createdNodeExecution)
                .orElseThrow(() -> new IllegalStateException("Created node execution was not found: " + createdNodeExecution));


        try {
            NodeExecutionResult executionResult = executeWithRetry(node, context, leaseValid);

            if (!leaseValid.get()) {
                throw new ExecutionLeaseLostExecption("Execution lease lost before saving node result: " + node.getName());
            }

            int updated = nodeExecutionRepository.updateResultIfOwned(
                    nodeExecution.getId(),
                    execution.getId(),
                    workerId,
                    ExecutionStatus.RUNNING,
                    NodeExecutionStatus.SUCCESS,
                    LocalDateTime.now(),
                    executionResult.getAttempts(),
                    executionResult.getOutput(),
                    LocalDateTime.now()

            );

            if (updated == 0) {
                throw new ExecutionLeaseLostExecption("worker no longer owns execution; node result was not saved: " + node.getName());
            }

            return executionResult;

        } catch (Exception e) {

            if (e instanceof ExecutionLeaseLostExecption) {
                throw (ExecutionLeaseLostExecption) e;
            }

            LocalDateTime finishedAt = LocalDateTime.now();

            int updated = nodeExecutionRepository.updateResultIfOwned(
                    nodeExecution.getId(),
                    execution.getId(),
                    workerId,
                    ExecutionStatus.RUNNING,
                    NodeExecutionStatus.FAILED,
                    finishedAt,
                    nodeExecution.getAttempts(),
                    e.getMessage() != null ? e.getMessage() : "Node execution failed",
                    LocalDateTime.now()

            );
            if (updated == 0) {
                throw new ExecutionLeaseLostExecption("worker no longer owns execution; node failure was not saved: " + node.getName());
            }

            throw new RuntimeException(e);
        }
    }

    private NodeExecutionResult executeWithRetry(Node node, NodeExecutionContext context, AtomicBoolean leaseValid) {

        for (int attempt = 1; attempt <= MAX_RETRIES + 1; attempt++) {

            if (!leaseValid.get()) {
                throw new ExecutionLeaseLostExecption("Execution lease lost; refusing to retry node: " + node.getName());
            }

            Future<NodeExecutionResult> future = executorService.submit(
                    () -> nodeExecutor.execute(node, context)
            );

            try {
                NodeExecutionResult result = future.get(5, TimeUnit.SECONDS);

                if (!leaseValid.get()) {
                    throw new ExecutionLeaseLostExecption("Execution lease lost after node execution: " + node.getName());
                }
                result.setAttempts(attempt);

                return result;

            } catch (TimeoutException e) {
                future.cancel(true);

                if (!leaseValid.get()) {
                    throw new ExecutionLeaseLostExecption("Execution lease lost during timeout: " + node.getName());
                }

                if (attempt > MAX_RETRIES) {
                    throw new RuntimeException("Node execution timed out after " + attempt + " attempts", e);
                }

                log.warn("Node {} timed out on attempt {}", node.getName(), attempt);

                long delay = (long) Math.pow(2, attempt - 1) * 1000;

            } catch (InterruptedException interruptedException) {
                future.cancel(true);
                Thread.currentThread().interrupt();

                if (!leaseValid.get()) {
                    throw new ExecutionLeaseLostExecption("Execution lease lost; refusing to retry node: " + node.getName());
                }

                throw new RuntimeException("Retry interrupted", interruptedException);

            } catch (ExecutionException e) {
                Throwable cause = e.getCause();

                if (cause instanceof ExecutionLeaseLostExecption leaseLost) {
                    throw leaseLost;
                }

                if (!leaseValid.get()) {
                    throw new ExecutionLeaseLostExecption("Execution lease lost; refusing to retry node: " + node.getName());
                }

                if (attempt > MAX_RETRIES) {
                    throw new RuntimeException(e);
                }
                log.warn("Node {} timed out on attempt {}", node.getName(), attempt);

            } catch (ExecutionLeaseLostExecption e) {
                future.cancel(true);
                throw e;
            }

            long delay = (long) Math.pow(2, attempt - 1) * 1000;

            try {
                Thread.sleep(delay);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();

                if (!leaseValid.get()) {
                    throw new ExecutionLeaseLostExecption(
                            "Execution lease lost during retry delay: " + node.getName()
                    );
                }
                throw new RuntimeException("Retry Interrupted", e);
            }
            if (!leaseValid.get()) {
                throw new ExecutionLeaseLostExecption("Execution lease lost; refusing to retry node: " + node.getName());
            }
        }

        throw new IllegalArgumentException("unexpected retry state");
    }

    private void resolveTargetReadiness(
            Node startNode,
            Map<Long, ConnectionExecutionStatus> connectionStatus,
            Queue<Node> readyQueue,
            NodeExecutionContext context
    ) {

        Queue<Node> candidates = new LinkedList<>();
        Set<Long> visited = new HashSet<>();
        candidates.add(startNode);

        while (!candidates.isEmpty()) {
            Node currNode = candidates.poll();

            if (!visited.add(currNode.getId())) {
                continue;
            }

            List<NodeConnection> incomingConnections = nodeConnectionRepository.findByTargetNodeId(currNode.getId());

            incomingConnections.sort(Comparator.comparing(NodeConnection::getId));

            boolean allResolved = incomingConnections.stream()
                    .allMatch(
                            connection -> {
                                ConnectionExecutionStatus status = connectionStatus.get(connection.getId());

                                return status != null && status != ConnectionExecutionStatus.PENDING;
                            }
                    );

            if (!allResolved) {
                continue;
            }

            boolean hasActiveIncoming = incomingConnections.stream()
                    .anyMatch(connection ->
                            connectionStatus.get(connection.getId()) == ConnectionExecutionStatus.ACTIVE);

            if (hasActiveIncoming) {

                List<List<Map<String, Object>>> inputStreams = new ArrayList<>();
                List<Map<String, Object>> inputs = new ArrayList<>();

                for (NodeConnection connection : incomingConnections) {
                    ConnectionExecutionStatus status = connectionStatus.get(connection.getId());

                    if (status != ConnectionExecutionStatus.ACTIVE) {
                        inputStreams.add(new ArrayList<>());
                        continue;
                    }

                    Node sourceNode = connection.getSourceNode();

                    List<Map<String, Object>> sourceItems = context.getNodeOutputItems(sourceNode.getId());

                    String branch = connection.getBranch();

                    if ("true".equalsIgnoreCase(branch) || "false".equalsIgnoreCase(branch)) {
                        Object branchOutput = context.getNodeOutput(sourceNode.getId(), branch.toLowerCase() + "Items");

                        if (branchOutput instanceof List<?> items) {
                            sourceItems = new ArrayList<>();

                            for (Object item : items) {
                                if (item instanceof Map<?, ?> map) {
                                    Map<String, Object> typedItems = new LinkedHashMap<>();

                                    map.forEach((key, value) -> typedItems.put(String.valueOf(key), value));

                                    sourceItems.add(typedItems);
                                }
                            }
                        }
                    }

                    inputStreams.add(sourceItems);
                    inputs.addAll(sourceItems);
                }
                context.setNodeInputStreams(currNode.getId(), inputStreams);

                context.setNodeInput(
                        currNode.getId(),
                        !inputs.isEmpty() ? inputs.get(0) : new HashMap<>()
                );

                readyQueue.add(currNode);
                continue;
            }

            List<NodeConnection> outgoingConnections = nodeConnectionRepository.findBySourceNodeId(currNode.getId());

            for (NodeConnection connection : outgoingConnections) {
                ConnectionExecutionStatus status = connectionStatus.get(connection.getId());

                if (status == ConnectionExecutionStatus.PENDING) {
                    connectionStatus.put(connection.getId(), ConnectionExecutionStatus.SKIPPED);
                }

                if (status != ConnectionExecutionStatus.ACTIVE) {
                    candidates.add(connection.getTargetNode());
                }
            }

        }
    }
}