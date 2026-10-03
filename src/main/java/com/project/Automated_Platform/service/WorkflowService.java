package com.project.Automated_Platform.service;

import com.project.Automated_Platform.entity.*;
import com.project.Automated_Platform.repository.ExecutionRepository;
import com.project.Automated_Platform.repository.NodeConnectionRepository;
import com.project.Automated_Platform.repository.NodeRepository;
import com.project.Automated_Platform.repository.WorkflowRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.*;

@Service
public class WorkflowService {

    @Autowired
    private WorkflowRepository workflowRepository;

    @Autowired
    private NodeRepository nodeRepository;

    @Autowired
    private NodeConnectionRepository nodeConnectionRepository;

    @Autowired
    private ExecutionRepository executionRepository;

    @Autowired
    private ExecutionEngine executionEngine;

    public Workflow createWorkflow(Workflow workflow) {
        return workflowRepository.save(workflow);

    }

    public Node createNode(long workflowId, Node node) {
        Workflow workflow = workflowRepository.findById(workflowId)
                .orElseThrow(() -> new RuntimeException("workflow not found"));

        node.setWorkflow(workflow);

        return nodeRepository.save(node);
    }

    public NodeConnection createNodeConnection(Long sourceNodeId, Long targetNodeId) {

        if(sourceNodeId.equals(targetNodeId)) {
            throw new RuntimeException("Node cannot connect to itself");
        }

        Node sourceNode = nodeRepository.findById(sourceNodeId)
                .orElseThrow(() -> new RuntimeException("Source node not found"));

        Node targetNode = nodeRepository.findById(targetNodeId)
                .orElseThrow(() -> new RuntimeException("target node not found"));

        if(!sourceNode.getWorkflow().getId()
                .equals(targetNode.getWorkflow().getId()))
        {
            throw new RuntimeException("Nodes must belong to same workflow");
        }

        if(nodeConnectionRepository.existsBySourceNodeIdAndTargetNodeId(sourceNodeId, targetNodeId)) {
            throw new RuntimeException("Connection already exists");
        }

        if(createCycle(sourceNodeId, targetNodeId)){
            throw new RuntimeException("Connection would create cycle");
        }

        NodeConnection connection = new NodeConnection();
        connection.setSourceNode(sourceNode);
        connection.setTargetNode(targetNode);

        return nodeConnectionRepository.save(connection);
    }

    public Execution createExecution(long workflowId) {
        Workflow workflow = workflowRepository.findById(workflowId)
                .orElseThrow(() -> new RuntimeException("workflow not found"));

        Execution execution = new Execution();

        execution.setWorkflow(workflow);
        execution.setStatus(ExecutionStatus.QUEUE);

        execution.setStartedAt(LocalDateTime.now());

        Execution savedExecution = executionRepository.save(execution);

        executionEngine.execute(savedExecution.getId());

        return savedExecution;
    }

    private boolean createCycle(Long sourceNodeId, Long targetNodeId) {
        Set<Long> visited = new HashSet<>();
        Queue<Long> queue = new LinkedList<>();

        queue.add(targetNodeId);

        while(!queue.isEmpty()) {
            Long currNode = queue.poll();

            if(!visited.add(currNode)) {
                continue;
            }

            if(currNode.equals(sourceNodeId)) {
                return true;
            }

            List<NodeConnection> connections = nodeConnectionRepository.findBySourceNodeId(currNode);

            for(NodeConnection connection : connections) {
                queue.add(connection.getTargetNode().getId());
            }
        }

        return false;
    }




}
