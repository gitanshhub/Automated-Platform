package com.project.Automated_Platform.service;


import com.project.Automated_Platform.entity.Node;
import com.project.Automated_Platform.entity.NodeConnection;
import com.project.Automated_Platform.entity.NodeType;
import com.project.Automated_Platform.entity.Workflow;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class WorkflowValidation {

    public void validate(Workflow workflow, List<NodeConnection> connections, List<Node> nodes) {

        if(workflow == null) {
            throw new IllegalArgumentException("workflow cannot be null");
        }

        if(nodes == null || nodes.isEmpty()) {
            throw new IllegalStateException("workflow must contains at least one node");
        }

        if(connections == null) {
            throw new IllegalArgumentException("Connections cannot be null");
        }

        Set<Long> nodeIds = new HashSet<>();

        for(Node node : nodes) {
            if (node == null || node.getId() == null) {
                throw new IllegalStateException("workflow contains a node without an ID");
            }


            if (!nodeIds.add(node.getId())) {
                throw new IllegalStateException("Duplicate node ID: " + node.getId());
            }

            if(node.getWorkflow() == null
                    || node.getWorkflow().getId() == null
                    || !node.getWorkflow().getId().equals(workflow.getId())
            ) {
                throw new IllegalStateException("Node does not belongs to this workflow:" + node.getId());

            }
        }

        for(NodeConnection connection : connections) {
            if(connection == null) {
                throw new IllegalStateException("workflow contains null connection");
            }

            Node source = connection.getSourceNode(),
            target = connection.getTargetNode();

            if(source == null || target == null || source.getId() == null || target.getId() == null) {
                throw new IllegalStateException("workflow must have valid source and target nodes");
            }

            if(source.getId().equals(target.getId())) {
                throw new IllegalStateException("A node cannot connect to itself: " + source.getId());
            }
        }

        validateNoCycle(connections, nodes);

        validateIfBranch(connections, nodes);

        triggerValidation(connections, nodes);
    }

    private void validateNoCycle( List<NodeConnection> connections, List<Node> nodes) {

        Map<Long, List<Long>> adjacencyList = new HashMap<>();

        for(Node node : nodes) {
            adjacencyList.put(node.getId(), new ArrayList<>());
        }
        for(NodeConnection connection : connections) {
            Long sourceId = connection.getSourceNode().getId(),
            targetId = connection.getTargetNode().getId();

            adjacencyList.get(sourceId).add(targetId);
        }

        // 0 = unvisited, 1 = visiting, 2 = completed
        Map<Long, Integer> state = new HashMap<>();

        for(Node node : nodes) {
            Long startId = node.getId();

            if(state.getOrDefault(startId, 0) != 0)
            {
                continue;
            }

            Deque<Map.Entry<Long, Iterator<Long>>> stack = new ArrayDeque<>();

            state.put(startId, 1);

            stack.push(Map.entry(startId, adjacencyList.get(startId).iterator()));

            while(!stack.isEmpty()) {
                var frame = stack.peek();
                Long currId = frame.getKey();
                var neighbors = frame.getValue();

                if(neighbors.hasNext()) {
                    Long neighborId = neighbors.next();
                    int neighborState = state.getOrDefault(neighborId, 0);

                    if(neighborState == 1) {
                        throw new IllegalStateException("workflow contains a cycle involving node: " + neighborId);
                    }

                    if(neighborState == 0) {
                        state.put(neighborId, 1);

                        stack.push(Map.entry(
                                neighborId,
                                adjacencyList.get(neighborId).iterator()));

                    }
                } else {
                    state.put(currId, 2);
                    stack.pop();
                }
            }
        }
    }

    private void validateIfBranch(List<NodeConnection> connections, List<Node> nodes) {

        Set<Long> nodeIds = new HashSet<>();

        for(Node node : nodes) {
            if(node.getType() == NodeType.IF) {
                nodeIds.add(node.getId());
            }
        }

        for(NodeConnection connection : connections) {
            Long source = connection.getSourceNode().getId();
            String branch = connection.getBranch();

            if(nodeIds.contains(source)) {
               if(!"true".equals(branch) && !"false".equals(branch)) {
                   throw new IllegalStateException("IF node connections must have branch 'true' or 'false'. " + "Source node ID: " + source);
               }

            } else if(branch != null && !branch.isBlank()) {
                throw new IllegalStateException(
                        "Only IF nodes can have branch labels. " + "Source node ID: " + source
                );

            }
        }
    }

    private void triggerValidation(List<NodeConnection> connections, List<Node> nodes) {
        Set<Long> nodeIdsWithIncomingConnections = new HashSet<>();

        for(NodeConnection connection : connections) {
            nodeIdsWithIncomingConnections.add(connection.getTargetNode().getId());
        }

        int webHookCnt = 0;
        boolean hasTrigger = false;

        for(Node node : nodes) {
            NodeType type = node.getType();

            boolean isTrigger = type == NodeType.MANUAL_TRIGGER || type == NodeType.WEBHOOK;

            boolean hasIncomingConnection = nodeIdsWithIncomingConnections.contains(node.getId());

            if(isTrigger) {
                hasTrigger = true;

                if (hasIncomingConnection) {
                    throw new IllegalStateException("Trigger nodes cannot have incoming connections. Node ID: " + node.getId());
                }
            } else if(!hasIncomingConnection) {
                throw new IllegalStateException("Only trigger nodes can be starting nodes Node ID: " + node.getId());
            }

            if(type == NodeType.WEBHOOK) {
                webHookCnt++;

                if(webHookCnt > 1) {
                    throw new IllegalStateException("A workflow can have at most one WEBHOOK node.");
                }
            }
        }
        if(!hasTrigger) {
            throw new IllegalStateException("A workflow must contain one WEBHOOK or MANUAL TRIGGER node.");
        }
    }
}


