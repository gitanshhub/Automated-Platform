package com.project.Automated_Platform.service.executors;

import com.project.Automated_Platform.entity.Node;
import com.project.Automated_Platform.service.execution.NodeExecutionContext;
import com.project.Automated_Platform.service.execution.NodeExecutionResult;
import jakarta.persistence.Column;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class WebhookNodeExecutor {
    public NodeExecutionResult execute(Node node, NodeExecutionContext context) {
        Object payload = context.getNodeInputValue(node.getId(), "payload");

        if(payload == null) {
            throw new IllegalArgumentException("Webhook payload is missing");
        }

        List<Map<String, Object>> outputItems = new ArrayList<>();

        if(payload instanceof Map<?,?> payloadMap) {
            outputItems.add(toStringKeyMap(payloadMap));
        }

        else if (payload instanceof List<?> payloadList) {

            for (Object item : payloadList) {

                if(item instanceof Map<?, ?> itemMap) {
                    outputItems.add(toStringKeyMap(itemMap));
                } else {
                    Map<String, Object> wrappedItem = new LinkedHashMap<>();

                    wrappedItem.put("data", item);
                    outputItems.add(wrappedItem);
                }
            }
        }

        else {
            Map<String, Object> wrappedItem = new LinkedHashMap<>();
            wrappedItem.put("data", payload);
            outputItems.add(wrappedItem);
        }

        context.setNodeOutputItems(node.getId(), outputItems);

        return new NodeExecutionResult("Webhook trigger executed with " + outputItems.size() + " item(s)", 0, null);
    }

    private Map<String, Object> toStringKeyMap(Map<?,?> source) {
        Map<String, Object> result = new LinkedHashMap<>();

        source.forEach((key, val) -> result.put(String.valueOf(key), val));

        return result;
    }
}
