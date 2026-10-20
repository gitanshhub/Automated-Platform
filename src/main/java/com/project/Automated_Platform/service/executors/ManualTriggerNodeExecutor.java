package com.project.Automated_Platform.service.executors;

import com.project.Automated_Platform.entity.Node;
import com.project.Automated_Platform.service.execution.NodeExecutionContext;
import com.project.Automated_Platform.service.execution.NodeExecutionResult;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class ManualTriggerNodeExecutor {
    public NodeExecutionResult execute(Node node, NodeExecutionContext context) {
        Map<String, Object> item = new LinkedHashMap<>();

        context.setNodeOutputItems(node.getId(), List.of(item));

        return new NodeExecutionResult("Manual trigger executed", 0, null);
    }
}
