package com.project.Automated_Platform.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "workflow.limits")
public record WorkflowLimitsProperties(
        int maxNodes,
        int maxConnections,
        int maxOutgoingConnectionsPerNode
) {}

