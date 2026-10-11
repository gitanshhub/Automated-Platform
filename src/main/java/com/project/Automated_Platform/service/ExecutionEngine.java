package com.project.Automated_Platform.service;

import com.project.Automated_Platform.entity.*;
import com.project.Automated_Platform.repository.*;
import com.project.Automated_Platform.service.execution.ConnectionExecutionStatus;
import com.project.Automated_Platform.service.execution.NodeExecutionContext;
import com.project.Automated_Platform.service.execution.NodeExecutionResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;

@Service
public class ExecutionEngine {
    @Autowired
    private WorkflowRepository workflowRepository;

    @Autowired
    private NodeExecutor nodeExecutor;

    @Autowired
    private NodeRepository nodeRepository;

    @Autowired
    private NodeConnectionRepository nodeConnectionRepository;

    @Autowired
    private ExecutionRepository executionRepository;

    private final ExecutorService executorService = Executors.newCachedThreadPool();

    @Autowired
    private NodeExecutionRepository nodeExecutionRepository;

    private ConnectionExecutionStatus connectionExecutionStatus;


    private static final int MAX_RETRIES = 3;


    public void execute(long executionId) {
        Execution execution = executionRepository.findById(executionId)
                .orElseThrow(() -> new RuntimeException("Execution not found"));

        NodeExecutionContext context = new NodeExecutionContext();

        Workflow workflow = execution.getWorkflow();

        Map<Long, ConnectionExecutionStatus> connectionStatus = new HashMap<>();

        execution.setStatus(ExecutionStatus.RUNNING);
        execution.setStartedAt(LocalDateTime.now());
        executionRepository.save(execution);

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
            execution.setStatus(ExecutionStatus.FAILED);
            execution.setResult("No starting node found");
            execution.setFinishedAt(LocalDateTime.now());

            executionRepository.save(execution);
            return;
        }

        Queue<Node> queue = new LinkedList<>();
        Set<Long> Visited = new HashSet<>();

        queue.add(startNode);
        List<String> executeNodes = new ArrayList<>();

        try {
            while (!queue.isEmpty()) {
                List<Future<NodeExecutionResult>> futures = new ArrayList<>();
                List<Node> currentNodes = new ArrayList<>();

                while (!queue.isEmpty()) {
                    Node currNode = queue.poll();

                    if (!Visited.add(currNode.getId())) {
                        continue;
                    }

                    currentNodes.add(currNode);

                    Future<NodeExecutionResult> future = executorService.submit(
                            () -> executeNode(currNode, execution, context)
                    );

                    futures.add(future);
                }

                for (int i = 0; i < futures.size(); i++) {
                    Future<NodeExecutionResult> future = futures.get(i);
                    Node currNode = currentNodes.get(i);

                    try {

                        NodeExecutionResult executionResult = future.get();

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

                            resolveTargetReadiness(connection.getTargetNode(), connectionStatus, queue);
                        }

                    } catch (Exception e) {

                        throw new RuntimeException(e);
                    }
                }


                execution.setStatus(ExecutionStatus.SUCCESS);
                execution.setResult(String.join("\n ", executeNodes));
            }

        } catch (Exception e) {
            execution.setStatus(ExecutionStatus.FAILED);

            execution.setResult(String.join("\n ", executeNodes) + "\nERROR: " + e.getMessage());
        }

        execution.setFinishedAt(LocalDateTime.now());

        executionRepository.save(execution);
    }

    private NodeExecutionResult executeNode(Node node, Execution execution, NodeExecutionContext context) {

        NodeExecution nodeExecution = new NodeExecution();

        nodeExecution.setExecution(execution);
        nodeExecution.setNode(node);
        nodeExecution.setStatus(NodeExecutionStatus.RUNNING);
        nodeExecution.setStartedAt(LocalDateTime.now());

        nodeExecutionRepository.save(nodeExecution);

        try {
            NodeExecutionResult executionResult = executeWithRetry(node, context);

            nodeExecution.setStatus(NodeExecutionStatus.SUCCESS);
            nodeExecution.setFinishedAt(LocalDateTime.now());
            nodeExecution.setAttempts(executionResult.getAttempts());
            nodeExecution.setOutput(executionResult.getOutput());

            nodeExecutionRepository.save(nodeExecution);

            return executionResult;

        } catch (Exception e) {
            nodeExecution.setStatus(NodeExecutionStatus.FAILED);
            nodeExecution.setFinishedAt(LocalDateTime.now());
            nodeExecution.setOutput(e.getMessage());

            nodeExecutionRepository.save(nodeExecution);

            throw e;

        }
    }

    private void markBranchSkipped(Node startNode, Set<Long> skippedNode, Set<Long> activeNodes) {
        Queue<Node> queue = new LinkedList<>();
        Set<Long> visited = new HashSet<>();
        queue.add(startNode);

        while (!queue.isEmpty()) {
            Node currNode = queue.poll();

            if (!visited.add(currNode.getId())) {
                continue;
            }

            if(activeNodes.contains(currNode.getId())) {
                continue;
            }

            List<NodeConnection> incomingConnections = nodeConnectionRepository.findByTargetNodeId(currNode.getId());

            boolean hasActiveIncomingConnection = false;

            for(NodeConnection connection : incomingConnections) {
                if(activeNodes.contains(connection.getSourceNode().getId())) {
                    hasActiveIncomingConnection = true;
                    break;
                }
            }

            if(hasActiveIncomingConnection) {
                continue;
            }

            skippedNode.add(currNode.getId());

            List<NodeConnection> connections = nodeConnectionRepository.findBySourceNodeId(currNode.getId());

            for(NodeConnection connection : connections) {
                queue.add(connection.getTargetNode());
            }
        }
    }

    private NodeExecutionResult executeWithRetry(Node node, NodeExecutionContext context) {
        int attempt = 0;

        while (attempt <= MAX_RETRIES) {

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
                } catch (InterruptedException interruptedException) {

                    Thread.currentThread().interrupt();

                    throw new RuntimeException("Retry interrupted", interruptedException);
                }
            } catch (Exception e) {
                future.cancel(true);

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

                    throw new RuntimeException("Retry interrupted", interruptedException);
                }
            }
        }

        throw new RuntimeException("unexpected retry state");
    }

    private void resolveTargetReadiness(
            Node startNode,
            Map<Long, ConnectionExecutionStatus> connectionStatus,
            Queue<Node> readyQueue
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

            if(hasActiveIncoming) {
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
