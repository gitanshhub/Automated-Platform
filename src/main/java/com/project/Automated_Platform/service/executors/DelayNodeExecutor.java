package com.project.Automated_Platform.service.executors;

import com.project.Automated_Platform.entity.Node;
import com.project.Automated_Platform.service.execution.NodeExecutionContext;
import com.project.Automated_Platform.service.execution.NodeExecutionResult;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

@Service
public class DelayNodeExecutor {

    public NodeExecutionResult execute(Node node, NodeExecutionContext context) {

        JsonNode config = node.getConfiguration();

        if(config == null || config.get("durationMs") == null) {
            throw new IllegalArgumentException("DELAY requires durationMs configuration");
        }

        long durationMs = config.get("durationMs").asLong();

        if(durationMs < 0 || durationMs > 300_000) {
            throw new IllegalArgumentException("Delay must be between 0 and 300,000 milliseconds");
        }

        try {
            Thread.sleep(durationMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();

            throw new IllegalArgumentException("Delay Interrupted", e);
        }

        return new NodeExecutionResult("Delayed for " + durationMs + " ms", 0, null);



    }
}

