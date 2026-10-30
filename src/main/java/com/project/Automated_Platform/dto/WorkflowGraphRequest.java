package com.project.Automated_Platform.dto;

import com.project.Automated_Platform.entity.status.NodeType;

import java.util.List;
import java.util.Map;

public record WorkflowGraphRequest(
        String name,
        String description,
        List<NodeRequest> nodes,
        List<ConnectionRequest> connections
) {
    public record NodeRequest(
            Long id,
            String clientId,
            String name,
            NodeType type,
            Map<String, Object> configuration
    ){}

    public record ConnectionRequest(
            String sourceClientId,
            String targetClientId,
            String branch
    ){}
}
