package com.project.Automated_Platform.dto;

import com.project.Automated_Platform.entity.Node;
import com.project.Automated_Platform.entity.NodeConnection;
import com.project.Automated_Platform.entity.Workflow;

import java.util.List;

public record WorkflowGraphResponse(
        Workflow workflow,
        List<Node> nodes,
        List<NodeConnection> connections
) {}
