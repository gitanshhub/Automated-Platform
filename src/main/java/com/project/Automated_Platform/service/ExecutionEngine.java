package com.project.Automated_Platform.service;

import com.project.Automated_Platform.entity.*;
import com.project.Automated_Platform.entity.status.ExecutionStatus;
import com.project.Automated_Platform.entity.status.NodeExecutionStatus;
import com.project.Automated_Platform.exception.ExecutionLeaseLostException;
import com.project.Automated_Platform.exception.HttpStatusException;
import com.project.Automated_Platform.exception.NodeExecutionOutcomeUnknownException;
import com.project.Automated_Platform.repository.*;
import com.project.Automated_Platform.service.execution.ConnectionExecutionStatus;
import com.project.Automated_Platform.service.execution.NodeExecutionContext;
import com.project.Automated_Platform.service.execution.NodeExecutionResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
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
                    LocalDateTime.now(),
                    null
            );

            if (completed == 0) {
                System.out.println("Could not finalize execution "
                        + executionId
                        + ": starting node not found"
                );
            }

            return;
        }

        Queue<Node> queue = new LinkedList<>();
        Set<Long> Visited = new HashSet<>();

        queue.add(startNode);
        List<String> executeNodes = new ArrayList<>();

        Node lastNode = null;

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
                    throw new ExecutionLeaseLostException("Execution lease lost; stopping further node scheduling.");
                }

                List<Future<NodeExecutionResult>> futures = new ArrayList<>();
                List<Node> currentNodes = new ArrayList<>();

                while (!queue.isEmpty()) {

                    if (!leaseValid.get()) {
                        throw new ExecutionLeaseLostException("Execution lease lost; stopping further node scheduling.");
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
                        lastNode = currNode;

                        if (!leaseValid.get()) {
                            for (Future<NodeExecutionResult> pendingFuture : futures) {
                                pendingFuture.cancel(true);
                            }
                            throw new ExecutionLeaseLostException("Execution lease lost while waiting for node: " + currNode.getName());
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

                        ExecutionLeaseLostException leaseLostException = null;

                        if (e instanceof ExecutionException
                                && e.getCause() instanceof ExecutionLeaseLostException) {
                            leaseLostException = (ExecutionLeaseLostException) e.getCause();

                        } else if (e instanceof ExecutionLeaseLostException) {
                            leaseLostException = (ExecutionLeaseLostException) e;
                        }

                        if (leaseLostException != null) {
                            for (Future<NodeExecutionResult> pendingFuture : futures) {
                                pendingFuture.cancel(true);
                            }
                            throw leaseLostException;
                        }

                        if(e instanceof ExecutionException && e.getCause() instanceof NodeExecutionOutcomeUnknownException outcomeUnknownException

                        ) {
                            for (Future<NodeExecutionResult> pendingFuture : futures) {
                                pendingFuture.cancel(true);
                            }

                            throw outcomeUnknownException;
                        }

                        if(e instanceof ExecutionException
                                && e.getCause() instanceof RuntimeException runtimeException
                                && runtimeException.getCause() instanceof NodeExecutionOutcomeUnknownException outcomeUnknownException
                        ) {
                            for (Future<NodeExecutionResult> pendingFuture : futures) {
                                pendingFuture.cancel(true);
                            }

                            throw outcomeUnknownException;
                        }

                        throw new RuntimeException(e);
                    }
                }

                finalResult = String.join("\n ", executeNodes);
            }

        } catch (ExecutionLeaseLostException e) {

            log.warn("Execution {} stopped because its worker lease was lost", executionId);
            return;

        } catch (Exception e) {
            finalStatus = ExecutionStatus.FAILED;

            finalResult = String.join("\n ", executeNodes) + "\nERROR: " + e.getMessage();
        }

        List<Map<String, Object>> finalItems = List.of();

        if(!executeNodes.isEmpty()) {
            finalItems = context.getNodeOutputItems(
                    lastNode != null ? lastNode.getId() : null
            );
        }

        JsonNode finalOutput = objectMapper.valueToTree(Map.of(
                "Items", finalItems,
                "nodeCount", executeNodes.size()
        ));

        int completed = executionRepository.completeExecution(
                executionId,
                ExecutionStatus.RUNNING,
                workerId,
                finalStatus,
                LocalDateTime.now(),
                finalResult,
                LocalDateTime.now(),
                finalOutput

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
            throw new ExecutionLeaseLostException("Execution lease lost; refusing to retry node: " + node.getName());
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
            throw new ExecutionLeaseLostException(
                    "worker no longer owns execution " + execution.getId()
            );
        }

        NodeExecution nodeExecution = nodeExecutionRepository.findById(createdNodeExecution)
                .orElseThrow(() -> new IllegalStateException("Created node execution was not found: " + createdNodeExecution));

        try {
            NodeExecutionResult executionResult = executeWithRetry(node, context, leaseValid, execution.getId());

            if (!leaseValid.get()) {
                throw new ExecutionLeaseLostException("Execution lease lost before saving node result: " + node.getName());
            }

            List<Map<String, Object>> items = context.getNodeOutputItems(node.getId()),
            inputItems = new ArrayList<>();

            for(List<Map<String, Object>> stream : context.getNodeInputStream(node.getId())) {
                inputItems.addAll(stream);
            }

            if(inputItems.isEmpty()) {
                Map<String, Object> single = context.getNodeInputs(node.getId());
                if(single != null && !single.isEmpty()) {
                    inputItems.add(new LinkedHashMap<>(single));
                }
            }

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("message", executionResult.getOutput());
            payload.put("inputItems", inputItems);
            payload.put("items", items);

            JsonNode outputData = objectMapper.valueToTree(payload);

            int updated = nodeExecutionRepository.updateResultIfOwned(
                    nodeExecution.getId(),
                    execution.getId(),
                    workerId,
                    ExecutionStatus.RUNNING,
                    NodeExecutionStatus.SUCCESS,
                    LocalDateTime.now(),
                    executionResult.getAttempts(),
                    executionResult.getOutput(),
                    LocalDateTime.now(),
                    outputData
            );

            if (updated == 0) {
                throw new ExecutionLeaseLostException("worker no longer owns execution; node result was not saved: " + node.getName());
            }

            return executionResult;

        } catch (Exception e) {

            if (e instanceof ExecutionLeaseLostException leaseLostException) {
                throw leaseLostException;
            }

            LocalDateTime finishedAt = LocalDateTime.now();

            NodeExecutionStatus failureStatus = e instanceof NodeExecutionOutcomeUnknownException
                    ? NodeExecutionStatus.OUTCOME_UNKNOWN : NodeExecutionStatus.FAILED;

            Map<String, Object> errorDetails = new LinkedHashMap<>();
            errorDetails.put("Status", "error");
            errorDetails.put("message", e.getMessage() != null ? e.getMessage() : "Node execution failed");

            if(e instanceof HttpStatusException httpStatusException) {
                errorDetails.put("Status Code", httpStatusException.getStatusCode());
            }

            JsonNode errorOutput = objectMapper.valueToTree(errorDetails);

            int updated = nodeExecutionRepository.updateResultIfOwned(
                    nodeExecution.getId(),
                    execution.getId(),
                    workerId,
                    ExecutionStatus.RUNNING,
                    failureStatus,
                    finishedAt,
                    nodeExecution.getAttempts(),
                    e.getMessage() != null ? e.getMessage() : "Node execution failed",
                    LocalDateTime.now(),
                    errorOutput

            );
            if (updated == 0) {
                throw new ExecutionLeaseLostException("worker no longer owns execution; node failure was not saved: " + node.getName());
            }

            if(e instanceof NodeExecutionOutcomeUnknownException outcomeUnknownException) {
                throw outcomeUnknownException;
            }

            if(e instanceof HttpStatusException httpStatusException) {
                throw httpStatusException;
            }

            throw new RuntimeException(e);
        }
    }

    private NodeExecutionResult executeWithRetry(Node node, NodeExecutionContext context, AtomicBoolean leaseValid, Long executionId) {

        for (int attempt = 1; attempt <= MAX_RETRIES + 1; attempt++) {

            if (!leaseValid.get()) {
                throw new ExecutionLeaseLostException("Execution lease lost; refusing to retry node: " + node.getName());
            }

            Future<NodeExecutionResult> future = executorService.submit(
                    () -> nodeExecutor.execute(node, context, "execution-" + executionId + "-node-" + node.getId())
            );

            try {
                NodeExecutionResult result = future.get(60, TimeUnit.SECONDS);

                if (!leaseValid.get()) {
                    throw new ExecutionLeaseLostException("Execution lease lost after node execution: " + node.getName());
                }
                result.setAttempts(attempt);

                return result;

            } catch (TimeoutException e) {
                future.cancel(true);
                if (!leaseValid.get()) {
                    throw new ExecutionLeaseLostException("Execution lease lost during timeout: " + node.getName());
                }

                if("HTTP_REQUEST".equals(String.valueOf(node.getType()))) {
                    throw new NodeExecutionOutcomeUnknownException("HTTP request timed out; the external outcome is unknown for node: " + node.getName(), e);
                }

                if (attempt > MAX_RETRIES) {
                    throw new RuntimeException("Node execution timed out after " + attempt + " attempts", e);
                }
                log.warn("Node {} timed out on attempt {}", node.getName(), attempt);

            } catch (InterruptedException interruptedException) {
                future.cancel(true);
                Thread.currentThread().interrupt();

                if (!leaseValid.get()) {
                    throw new ExecutionLeaseLostException("Execution lease lost; refusing to retry node: " + node.getName());
                }

                throw new RuntimeException("Retry interrupted", interruptedException);

            } catch (ExecutionException e) {
                Throwable cause = e.getCause();

                if (cause instanceof ExecutionLeaseLostException leaseLost) {
                    throw leaseLost;
                }

                if(cause instanceof NodeExecutionOutcomeUnknownException outcomeUnknown) {
                    throw outcomeUnknown;
                }

                if(cause instanceof HttpStatusException httpStatusException) {
                    throw httpStatusException;
                }

                if (!leaseValid.get()) {
                    throw new ExecutionLeaseLostException("Execution lease lost; refusing to retry node: " + node.getName());
                }


                if (attempt > MAX_RETRIES) {
                    throw new RuntimeException(e);
                }

                log.warn("Problem in Node {} execution, attempt made: {}", node.getName(), attempt);

            } catch (ExecutionLeaseLostException e) {
                future.cancel(true);
                throw e;
            }

            long delay = (long) Math.pow(2, attempt - 1) * 1000;

            try {
                Thread.sleep(delay);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();

                if (!leaseValid.get()) {
                    throw new ExecutionLeaseLostException(
                            "Execution lease lost during retry delay: " + node.getName()
                    );
                }
                throw new RuntimeException("Retry Interrupted", e);
            }
            if (!leaseValid.get()) {
                throw new ExecutionLeaseLostException("Execution lease lost; refusing to retry node: " + node.getName());
            }
        }

        throw new IllegalArgumentException("Unexpected retry state");
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

