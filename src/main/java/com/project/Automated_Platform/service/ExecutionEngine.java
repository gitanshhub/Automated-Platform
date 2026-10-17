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

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class ExecutionEngine {
    @Autowired
    private WorkflowRepository workflowRepository;

    @Autowired
    private NodeExecutor nodeExecutor;

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


    private static final int MAX_RETRIES = 3;

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

        for(NodeConnection connection : workflowConnections) {
            connectionStatus.put(
                    connection.getId(),
                    ConnectionExecutionStatus.PENDING
            );
        }

        Node startNode = null;

        for (Node node : workflowNodes) {
            List<NodeConnection> inComingConnections = nodeConnectionRepository.findByTargetNodeId(node.getId());
            System.out.println(inComingConnections);


            if (inComingConnections.isEmpty()) {
                startNode = node;
                break;
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

            if(completed == 0) {
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
            while (!queue.isEmpty()) {

                if(!leaseValid.get()) {
                    throw new ExecutionLeaseLostExecption("Execution lease lost; stopping further node scheduling.");
                }

                List<Future<NodeExecutionResult>> futures = new ArrayList<>();
                List<Node> currentNodes = new ArrayList<>();

                while (!queue.isEmpty()) {

                    if(!leaseValid.get()) {
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

                        if(!leaseValid.get()) {
                            for(Future<NodeExecutionResult> pendingFuture : futures) {
                                pendingFuture.cancel(true);
                            }
                            throw new ExecutionLeaseLostExecption("Execution lease lost while waiting for node: " + currNode.getName());
                        }

                        context.putNodeOutput(
                                currNode.getId(),
                                "result",
                                executionResult.getOutput()
                        );

                        executeNodes.add(currNode.getName() + " -> " + executionResult.getOutput());

                        System.out.println("Node Executed: " + currNode.getName() + " -> " + executionResult.getOutput());

                        List<NodeConnection> connections = nodeConnectionRepository.findBySourceNodeId(currNode.getId());
                        for (NodeConnection connection : connections) {
                            boolean selected  = executionResult.getBranch() == null
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

                        if(e instanceof ExecutionException
                                && e.getCause() instanceof ExecutionLeaseLostExecption)
                        {
                            leaseLostExecption = (ExecutionLeaseLostExecption) e.getCause();

                        } else if(e instanceof ExecutionLeaseLostExecption) {
                            leaseLostExecption = (ExecutionLeaseLostExecption) e;
                        }

                        if(leaseLostExecption != null) {
                            for(Future<NodeExecutionResult> pendingFuture : futures) {
                                pendingFuture.cancel(true);
                            }
                            throw leaseLostExecption;
                        }

                        throw new RuntimeException(e);
                    }
                }


                finalResult = String.join("\n ", executeNodes);
            }

        } catch(ExecutionLeaseLostExecption e) {

            log.warn("Execution {} stopped because its worker lease was lost", executionId);
            return;

        } catch (Exception e) {
            finalStatus = ExecutionStatus.FAILED;

           finalResult =  String.join("\n ", executeNodes) + "\nERROR: " + e.getMessage();
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

        if(completed == 0) {
            System.out.println("Could not finalize execution "
                    + executionId
                    + ": worker ownership was lost"
            );
        }

    }

    private NodeExecutionResult executeNode(Node node, Execution execution, NodeExecutionContext context, AtomicBoolean leaseValid, String workerId) {
        if(!leaseValid.get()) {
            throw new ExecutionLeaseLostExecption("Execution lease lost; refusing to retry node: "+ node.getName());
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

        if(createdNodeExecution == null) {
            throw new ExecutionLeaseLostExecption(
                    "worker no longer owns execution " + execution.getId()
            );
        }

        NodeExecution nodeExecution = nodeExecutionRepository.findById(createdNodeExecution)
                .orElseThrow(() -> new IllegalStateException("Created node execution was not found: " + createdNodeExecution));



        try {
            NodeExecutionResult executionResult = executeWithRetry(node, context, leaseValid);

            if(!leaseValid.get()) {
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

            if(updated == 0) {
                throw new ExecutionLeaseLostExecption("worker no longer owns execution; node result was not saved: " + node.getName());
            }

            return executionResult;

        } catch (Exception e) {

            if(e instanceof ExecutionLeaseLostExecption) {
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
            if(updated == 0) {
                throw new ExecutionLeaseLostExecption("worker no longer owns execution; node failure was not saved: " + node.getName());
            }

            throw new RuntimeException(e);
        }
    }

    private NodeExecutionResult executeWithRetry(Node node, NodeExecutionContext context, AtomicBoolean leaseValid) {
        int attempt = 0;

        while (attempt <= MAX_RETRIES) {

            if(!leaseValid.get()) {
                throw new ExecutionLeaseLostExecption("Execution lease lost; refusing to retry node: "+ node.getName());
            }

            Future<NodeExecutionResult> future = executorService.submit(
                    () -> nodeExecutor.execute(node, context)
            );

            try {

                NodeExecutionResult result = future.get(5, TimeUnit.SECONDS);

                result.setAttempts(attempt + 1);

                return result;

            } catch (TimeoutException e) {

                future.cancel(true);

                attempt++;

                System.out.println("Node failed: " + node.getName() + " | attempt: " + attempt);

                if (attempt > MAX_RETRIES) {
                    throw new RuntimeException("Node execution timed out after retries");
                }

                long delay = (long) Math.pow(2, attempt - 1) * 1000;

                try {
                    Thread.sleep(delay);

                    if(!leaseValid.get()) {
                        throw new ExecutionLeaseLostExecption("Execution lease lost; refusing to retry node: "+ node.getName());
                    }
                } catch (InterruptedException interruptedException) {

                    Thread.currentThread().interrupt();

                    if(!leaseValid.get()) {
                        throw new ExecutionLeaseLostExecption("Execution lease lost; refusing to retry node: " + node.getName());
                    }

                    throw new RuntimeException("Retry interrupted", interruptedException);
                }


            } catch (Exception e) {
                future.cancel(true);

                if(e instanceof ExecutionException
                        && e.getCause() instanceof ExecutionLeaseLostExecption)
                {
                    throw (ExecutionLeaseLostExecption) e.getCause();
                }

                if(e instanceof ExecutionLeaseLostExecption) {
                    throw (ExecutionLeaseLostExecption) e;
                }

                if(!leaseValid.get()) {
                    throw new ExecutionLeaseLostExecption("Execution lease lost; refusing to retry node: "+ node.getName());
                }

                if(e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("Node execution interrupted", e);
                }

                attempt++;

                System.out.println("Node failed: " + node.getName() + " | attempt: " + attempt);

                if (attempt > MAX_RETRIES) {
                    throw new RuntimeException(e);
                }

                long delay = (long) Math.pow(2, attempt - 1) * 1000;

                try {

                    Thread.sleep(delay);

                } catch (InterruptedException interruptedException) {

                    Thread.currentThread().interrupt();

                    if(!leaseValid.get()) {
                        throw new ExecutionLeaseLostExecption("Execution lease lost; refusing to retry node: " + node.getName());
                    }

                    throw new RuntimeException("Retry interrupted", interruptedException);
                }

                if(!leaseValid.get()) {
                    throw new ExecutionLeaseLostExecption("Execution lease lost; refusing to retry node: " + node.getName());
                }

            }
        }

        throw new RuntimeException("unexpected retry state");
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

            if(!allResolved) {
                continue;
            }

            boolean hasActiveIncoming = incomingConnections.stream()
                    .anyMatch(connection ->
                            connectionStatus.get(connection.getId()) == ConnectionExecutionStatus.ACTIVE);

            if (hasActiveIncoming) {
                if (currNode.getType() == NodeType.MERGE) {
                    List<List<Map<String, Object>>> inputStreams = new ArrayList<>();

                    for (NodeConnection connection : incomingConnections) {
                        ConnectionExecutionStatus status = connectionStatus.get(connection.getId());

                        if (status == ConnectionExecutionStatus.ACTIVE) {
                            Node sourceNode = connection.getSourceNode();

                            inputStreams.add(context.getNodeOutputItems(sourceNode.getId()));

                        } else {
                            inputStreams.add(new ArrayList<>());
                        }
                    }
                    context.setNodeInputStreams(currNode.getId(), inputStreams);

                } else {
                    Map<String, Object> inputs = new HashMap<>();

                    for (NodeConnection connection : incomingConnections) {
                        ConnectionExecutionStatus status = connectionStatus.get(connection.getId());

                        if (status != ConnectionExecutionStatus.ACTIVE) {
                            continue;
                        }
                        Node sourceNode = connection.getSourceNode();

                        Map<String, Object> sourceOutput = context.getNodeOutputs(sourceNode.getId());

                        inputs.putAll(sourceOutput);
                    }
                    context.setNodeInput(currNode.getId(), inputs);
                }
                readyQueue.add(currNode);
                continue;
            }

            List<NodeConnection> outgoingConnections = nodeConnectionRepository.findBySourceNodeId(currNode.getId());

            for(NodeConnection connection : outgoingConnections) {
                ConnectionExecutionStatus status = connectionStatus.get(connection.getId());

                if(status == ConnectionExecutionStatus.PENDING) {
                    connectionStatus.put(connection.getId(), ConnectionExecutionStatus.SKIPPED);
                }

                if(status != ConnectionExecutionStatus.ACTIVE) {
                    candidates.add(connection.getTargetNode());
                }
            }

        }

    }



}