package com.project.Automated_Platform.service;

import com.project.Automated_Platform.entity.Node;
import com.project.Automated_Platform.service.execution.NodeExecutionContext;
import com.project.Automated_Platform.service.execution.NodeExecutionResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jackson.autoconfigure.JacksonProperties;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
public class NodeExecutor {

    @Autowired
    private ObjectMapper objectMapper;

    private final RestClient restClient = RestClient.create();

    public NodeExecutionResult execute(Node node, NodeExecutionContext context) {
        switch (node.getType()) {

            case MANUAL_TRIGGER:
                return new NodeExecutionResult("MANUAL TRIGGER executed", 0, null);
            case WEBHOOK:
                try{
                JsonNode config = objectMapper.readTree(node.getConfiguration());

                String message = config.has("message")
                        ? config.get("message").asString()
                        : "hello from webhook";

                context.getData().put("message", message);
                return new NodeExecutionResult("WEBHOOK executed: " + message, 0, null);
                } catch (Exception e) {
                    throw new RuntimeException("webhook execution failed: " + e.getMessage(), e);
                }

            case HTTP_REQUEST:
                try {
                    JsonNode config = objectMapper.readTree(node.getConfiguration());

                    String url = config.get("url").asString();
                    String method = config.get("method").asString();

                    String response;

                    if(method.equalsIgnoreCase("GET")){
                        response = restClient.get()
                                .uri(url)
                                .retrieve()
                                .body(String.class);
                    } else {
                        throw new RuntimeException("Unsupported HTTP method: " + method);
                    }

                    context.getData().put("httpResponse", response);

                    return new NodeExecutionResult("HTTP " + method + " -> " + response, 0, null);
                } catch (Exception e) {
                    throw new RuntimeException("HTTP request failed: " + e.getMessage(), e);
                }

            case DELAY: return new NodeExecutionResult("delayed", 0, null);

            case IF:
                try {
                    JsonNode config = objectMapper.readTree(node.getConfiguration());
                    String field = config.get("field").asString();
                    String operation = config.get("operation").asString();
                    String expectedValue = config.get("value").asString();

                    Object actualValue = context.getData().get(field);

                    if(actualValue == null) {
                        throw new RuntimeException("if field not found in execution context: " + field);
                    }

                    boolean condition;

                    switch (operation) {
                        case "equals":
                            condition = actualValue.toString().equals(expectedValue);
                            break;

                        case "notEquals":
                            condition = !actualValue.toString().equals(expectedValue);
                            break;

                        default:
                            throw new RuntimeException("Unsupported IF operation: " + operation);
                    }

                    String branch = condition ? "true" : "false";

                    return new NodeExecutionResult("IF condition evaluated", 0, branch);

                } catch (Exception e) {
                    throw new RuntimeException("IF condition failed: " + e.getMessage(), e);
                }


            case TRANSFORM:
                Object message = context.getData().get("message");

                return new NodeExecutionResult("transform: " + message, 0, null);

            default: throw new RuntimeException("unsupported node type");
        }

    }

}
