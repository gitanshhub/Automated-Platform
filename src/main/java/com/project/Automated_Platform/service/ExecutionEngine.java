package com.project.Automated_Platform.service;

import com.project.Automated_Platform.entity.*;
import com.project.Automated_Platform.repository.ExecutionRepository;
import com.project.Automated_Platform.repository.NodeConnectionRepository;
import com.project.Automated_Platform.repository.NodeRepository;
import com.project.Automated_Platform.repository.WorkflowRepository;
import org.springframework.beans.factory.annotation.Autowired;
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

    private static final int MAX_RETRIES = 3;

    public void execute(long executionId) {
        Execution execution = executionRepository.findById(executionId)
                .orElseThrow(() -> new RuntimeException("Execution not found"));

        Workflow workflow = execution.getWorkflow();

        execution.setStatus(ExecutionStatus.RUNNING);
        executionRepository.save(execution);

        List<Node> nodes = nodeRepository.findAll();

        List<Node> workflowNodes = nodes.stream()
                .filter(node -> node.getWorkflow().getId().equals(workflow.getId()))
                .toList();

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
            executionRepository.save(execution);
            return;
        }

        Queue<Node> queue = new LinkedList<>();
        Set<Long> Visited = new HashSet<>();

        queue.add(startNode);
        List<String> executeNodes = new ArrayList<>();

        try {
            while (!queue.isEmpty()) {
                Node currNode = queue.poll();

                if (!Visited.add(currNode.getId())) {
                    continue;
                }

                String result = executeWithRetry(currNode);

                executeNodes.add(currNode.getName() + " -> " + result);

                System.out.println("Node Executed: " + currNode.getName() + " -> " + result);

                List<NodeConnection> connections = nodeConnectionRepository.findBySourceNodeId(currNode.getId());

                for (NodeConnection connection : connections) {
                    Node nextNode = connection.getTargetNode();
                    queue.add(nextNode);
                }
            }

            execution.setStatus(ExecutionStatus.SUCCESS);
            execution.setResult(String.join("\n", executeNodes));

        } catch (Exception e) {
            execution.setStatus(ExecutionStatus.FAILED);

            execution.setResult(String.join("\n", executeNodes) + "\nERROR: " + e.getMessage());
        }

        execution.setFinishedAt(LocalDateTime.now());

        executionRepository.save(execution);
    }

    private String executeWithRetry(Node node) {
        int attempt = 0;

        while (attempt <= MAX_RETRIES) {

            Future<String> future = executorService.submit(
                    () -> nodeExecutor.execute(node)
            );

            try {

                return future.get(5, TimeUnit.SECONDS);

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


}
