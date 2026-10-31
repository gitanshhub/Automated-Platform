package com.project.Automated_Platform.service;

import com.project.Automated_Platform.config.WorkflowLimitsProperties;
import com.project.Automated_Platform.dto.NodeExecutionResponse;
import com.project.Automated_Platform.dto.WorkflowGraphRequest;
import com.project.Automated_Platform.dto.WorkflowGraphResponse;
import com.project.Automated_Platform.entity.*;
import com.project.Automated_Platform.entity.status.ExecutionStatus;
import com.project.Automated_Platform.repository.ExecutionRepository;
import com.project.Automated_Platform.repository.NodeConnectionRepository;
import com.project.Automated_Platform.repository.NodeExecutionRepository;
import com.project.Automated_Platform.repository.NodeRepository;
import com.project.Automated_Platform.repository.WorkflowRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

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
    private ExecutionQueue executionQueue;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired 
    private NodeExecutionRepository nodeExecutionRepository;

    @Autowired
    private WorkflowValidation workflowValidation;
    @Autowired
    private final WorkflowLimitsProperties limits;

    public WorkflowService(WorkflowLimitsProperties limits) {
        this.limits = limits;
    }

    public List<Workflow> getAllWorkflows() {
        return workflowRepository.findAll();
    }

    public Workflow getWorkflow(long workflowId) {
        return workflowRepository.findById(workflowId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Workflow now found"));
    }

    public List<Node> getWorkflowNodes(long workflowId) {
        getWorkflow(workflowId);

        return nodeRepository.findByWorkflowId(workflowId);
    }

    public List<NodeConnection> getWorkflowConnections(long workflowId) {
        getWorkflow(workflowId);

        return nodeConnectionRepository.findBySourceNodeWorkflowId(workflowId);
    }

    public Execution getExecution(Long executionId) {
        return executionRepository.findById(executionId)
                .orElseThrow(() -> new RuntimeException("Execution not found"));
    }

    public Workflow createWorkflow(Workflow workflow) {
        return workflowRepository.save(workflow);
    }

    @Transactional
    public Node createNode(long workflowId, Node node) {
        Workflow workflow = workflowRepository.findByIdForUpdate(workflowId)
                .orElseThrow(() -> new RuntimeException("workflow not found"));

        long nodeCount = nodeRepository.countByWorkflowId(workflowId);

        if(nodeCount >= limits.maxNodes()){
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Workflow cannot contains more than"+ limits.maxNodes() +" nodes");
        }

        node.setWorkflow(workflow);

        return nodeRepository.save(node);
    }

    @Transactional
    public NodeConnection createNodeConnection(Long sourceNodeId, Long targetNodeId, String branch) {

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

        long connectionCount = nodeConnectionRepository.countBySourceNodeWorkflowId(sourceNode.getWorkflow().getId());
        if(connectionCount >= limits.maxConnections()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "workflow cannot contains more than " + limits.maxConnections() + " connections"
            );
        }

        long outgoingCount = nodeConnectionRepository.countBySourceNodeId(sourceNodeId);
        if(outgoingCount >= limits.maxOutgoingConnectionsPerNode()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "node cannot contains more than " + limits.maxOutgoingConnectionsPerNode() + " outgoing connections"
            );
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
        connection.setBranch(branch);

        return nodeConnectionRepository.save(connection);
    }

    public Execution createExecution(long workflowId) {
        Workflow workflow = workflowRepository.findById(workflowId)
                .orElseThrow(() -> new RuntimeException("workflow not found"));

        List<Node> nodes = nodeRepository.findByWorkflowId(workflowId);
        List<NodeConnection> connections = nodeConnectionRepository.findBySourceNodeWorkflowId(workflowId);
        workflowValidation.validate(workflow, connections, nodes);


        Execution execution = new Execution();
        execution.setWorkflow(workflow);
        execution.setStatus(ExecutionStatus.QUEUED);
        execution.setTriggerType("MANUAL");

        Execution savedExecution = executionRepository.save(execution);

        try {
            executionQueue.submit(savedExecution.getId());
        } catch (IllegalStateException e) {
            savedExecution.setStatus(ExecutionStatus.FAILED);
            savedExecution.setFinishedAt(LocalDateTime.now());
            savedExecution.setResult("Execution rejected: queue is full");

            executionRepository.save(execution);

            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "Execution queue is full. try again later");
        }

        return savedExecution;
    }

    public Execution createWebhookExecution(long workflowId, Map<String, Object> payload) {
        Workflow workflow = workflowRepository.findById(workflowId)
                .orElseThrow(() -> new RuntimeException("Workflow not found"));

        List<Node> nodes = nodeRepository.findByWorkflowId(workflowId);
        List<NodeConnection> connections = nodeConnectionRepository.findBySourceNodeWorkflowId(workflowId);
        workflowValidation.validate(workflow, connections, nodes);

        Execution execution = new Execution();
        execution.setWorkflow(workflow);
        execution.setStatus(ExecutionStatus.QUEUED);

        String triggerData = objectMapper.writeValueAsString(
                payload == null ? Map.of() : payload
        );
        execution.setTriggerType("WEBHOOK");
        execution.setTriggerData(triggerData);

        Execution savedExecution = executionRepository.save(execution);

        try {
            executionQueue.submit(savedExecution.getId());
        } catch (IllegalStateException e) {
            savedExecution.setStatus(ExecutionStatus.FAILED);
            savedExecution.setFinishedAt(LocalDateTime.now());
            savedExecution.setResult(e.getMessage());

            return executionRepository.save(savedExecution);
        }

        return savedExecution;
    }

    @Transactional
    public Workflow updateWorkflow(long workflowId, Workflow updatedWorkflow) {
        Workflow workflow = workflowRepository.findByIdForUpdate(workflowId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Workflow not found"));

        if(updatedWorkflow.getName() != null) {
            if(updatedWorkflow.getName().isBlank()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Workflow name cannot be blank");
            }

            workflow.setName(updatedWorkflow.getName());
        }

        if(updatedWorkflow.getDescription() != null) {
            workflow.setDescription(updatedWorkflow.getDescription());
        }

        workflow.setUpdatedAt(LocalDateTime.now());

        return workflowRepository.save(workflow);
    }

    @Transactional
    public Node updateNode(long nodeId, Node updatedNode) {
        Node existingNode = nodeRepository.findById(nodeId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "node not found"));

        Long workflowId = existingNode.getWorkflow().getId();

        workflowRepository.findByIdForUpdate(workflowId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "workflow not found"));

        Node node = nodeRepository.findByIdForUpdate(nodeId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "node not found"));

        if (updatedNode.getName() != null) {
            if (updatedNode.getName().isBlank()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Node name cannot be blank");
            }
            node.setName(updatedNode.getName());
        }

        if (updatedNode.getDescription() != null) {
            node.setDescription(updatedNode.getDescription());
        }

        if (updatedNode.getConfiguration() != null) {
            node.setConfiguration(updatedNode.getConfiguration());
        }
        return nodeRepository.save(node);
    }

    @Transactional
    public void deleteNode(long nodeId) {
        Node existingNode = nodeRepository.findById(nodeId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "node not found"));

        Long workflowId = existingNode.getWorkflow().getId();

        workflowRepository.findByIdForUpdate(workflowId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "workflow not found"));

        Node node = nodeRepository.findByIdForUpdate(nodeId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "node not found"));


        List<NodeConnection> outgoing = nodeConnectionRepository.findBySourceNodeId(nodeId);
        List<NodeConnection> incoming = nodeConnectionRepository.findByTargetNodeId(nodeId);

        nodeConnectionRepository.deleteAll(outgoing);
        nodeConnectionRepository.deleteAll(incoming);

        nodeRepository.delete(node);

    }

    @Transactional
    public void deleteWorkflow(long workflowId) {
        Workflow workflow = workflowRepository.findByIdForUpdate(workflowId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Workflow not found"));

        List<Execution> executions = executionRepository.findByWorkflowId(workflowId);
        executionRepository.deleteAll(executions);

        List<NodeConnection> connections = nodeConnectionRepository.findBySourceNodeWorkflowId(workflowId);
        nodeConnectionRepository.deleteAll(connections);

        List<Node> nodes = nodeRepository.findByWorkflowId(workflowId);
        nodeRepository.deleteAll(nodes);

        workflowRepository.delete(workflow);
    }

    @Transactional
    public WorkflowGraphResponse saveWorkflowGraph(Long workflowId, WorkflowGraphRequest request) {
        Workflow workflow = workflowRepository.findByIdForUpdate(workflowId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Workflow now found"));

        if(request.name() == null || request.name().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "workflow name cannot be blank");
        }

        workflow.setName(request.name());
        workflow.setDescription(request.description());
        workflow.setUpdatedAt(LocalDateTime.now());

        List<Node> existingNodes = getWorkflowNodes(workflowId);

        Map<Long, Node> existingNodeIds = existingNodes.stream()
                .collect(Collectors.toMap(
                        Node::getId, node -> node
                        )
                );

        Set<Long> submittedNodeIds = request.nodes().stream()
                .map(WorkflowGraphRequest.NodeRequest::id)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        for(Node existingNode : existingNodes) {
            if(!submittedNodeIds.contains(existingNode.getId())) {
                nodeConnectionRepository.deleteBySourceNodeIdOrTargetNodeId(
                        existingNode.getId(),
                        existingNode.getId()
                );

                nodeRepository.delete(existingNode);
            }
        }

        Map<String, Node> nodesByClientId = new HashMap<>();

        for(WorkflowGraphRequest.NodeRequest nodeRequest : request.nodes()) {
            Node node;

            if(nodeRequest.id() == null) {
                node = new Node();
                node.setWorkflow(workflow);
            } else {
                node = existingNodeIds.get(nodeRequest.id());
                if(node == null) {
                    throw new ResponseStatusException(
                            HttpStatus.BAD_REQUEST,
                            "Node does not belong to this workflow: " + nodeRequest.id()
                    );
                }
            }

            node.setName(nodeRequest.name());
            node.setType(nodeRequest.type());

            Map<String, Object> configuration = nodeRequest.configuration() == null
                    ? new HashMap<>() : new HashMap<>(nodeRequest.configuration());

            JsonNode configurationJson = objectMapper.valueToTree(configuration);
            node.setConfiguration(configurationJson);

            Node savedNode = nodeRepository.save(node);
            nodesByClientId.put(nodeRequest.clientId(), savedNode);
        }

        List<NodeConnection>
                oldConnections = nodeConnectionRepository.findBySourceNodeWorkflowId(workflowId),
                savedConnections = new ArrayList<>();

        nodeConnectionRepository.deleteAll(oldConnections);

        for(WorkflowGraphRequest.ConnectionRequest connectionRequest : request.connections()) {
            Node source = nodesByClientId.get(connectionRequest.sourceClientId()),
                 target = nodesByClientId.get(connectionRequest.targetClientId());

            if(source == null || target == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Connection refers to an unknown node");
            }

            if(source.getId().equals(target.getId())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A node cannot connect to itself");
            }

            NodeConnection connection = new NodeConnection();
            connection.setSourceNode(source);
            connection.setTargetNode(target);
            connection.setBranch(connectionRequest.branch());

            savedConnections.add(nodeConnectionRepository.save(connection));
        }

        Workflow saveWorkflow = workflowRepository.save(workflow);

        return new WorkflowGraphResponse(saveWorkflow, new ArrayList<>(nodesByClientId.values()), savedConnections);
    }

    public List<NodeExecutionResponse> getNodeExecution(Long executionId) {
        executionRepository.findById(executionId)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Execution not found"));

        return nodeExecutionRepository
        .findByExecutionIdOrderByStartedAtAsc(executionId)
        .stream()
        .map(nodeExecution -> new NodeExecutionResponse(
            nodeExecution.getId(),
            nodeExecution.getNode().getId(),
            nodeExecution.getNode().getName(),
            nodeExecution.getStatus(),
            nodeExecution.getStartedAt(),
            nodeExecution.getFinishedAt(),
            nodeExecution.getAttempts(),
            nodeExecution.getOutput(),
            nodeExecution.getOutputData()
        ))
        .toList();
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
