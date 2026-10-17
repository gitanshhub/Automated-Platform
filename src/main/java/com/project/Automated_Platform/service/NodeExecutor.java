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

import java.util.*;

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
                try {
                    JsonNode config = objectMapper.readTree(node.getConfiguration());

                    String message = config.has("message")
                            ? config.get("message").asString()
                            : "hello from webhook";

                    context.putNodeOutput(node.getId(), "message", message);

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

                    if (method.equalsIgnoreCase("GET")) {
                        response = restClient.get()
                                .uri(url)
                                .retrieve()
                                .body(String.class);
                    } else {
                        throw new RuntimeException("Unsupported HTTP method: " + method);
                    }

                    context.putNodeOutput(node.getId(), "httpResponse", response);

                    return new NodeExecutionResult("HTTP " + method + " -> " + response, 0, null);

                } catch (Exception e) {
                    throw new RuntimeException("HTTP request failed: " + e.getMessage(), e);
                }

            case DELAY:
                return new NodeExecutionResult("delayed", 0, null);

            case IF:
                try {
                    JsonNode config = objectMapper.readTree(node.getConfiguration());
                    String field = config.get("field").asString();
                    String operation = config.get("operation").asString();
                    String expectedValue = config.get("value").asString();

                    Object actualValue = context.getNodeInputValue(node.getId(), field);

                    if (actualValue == null) {
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
                Object message = context.getNodeInputValue(node.getId(), "message");

                if (message == null) {
                    throw new RuntimeException("Input field 'message' not found for transform node: " + node.getName());
                }

                String transformedMessage = message.toString().toUpperCase();

                context.putNodeOutput(node.getId(), "message", transformedMessage);

                return new NodeExecutionResult("transform: " + transformedMessage, 0, null);

            case MERGE:
                return executeMerge(node, context);

            default:
                throw new RuntimeException("unsupported node type");
        }

    }

    private NodeExecutionResult executeMerge(Node node, NodeExecutionContext context) {
        try {
            JsonNode config = objectMapper.readTree(node.getConfiguration());

            String mode = requiredConfig(config, "mode").toUpperCase();

            List<List<Map<String, Object>>> streams = context.getNodeInputStream(node.getId());

            if (streams.size() != 2) {
                throw new IllegalArgumentException("MERGE requires exactly 2 input streams; received " + streams.size());
            }

            List<Map<String, Object>>
                    input1 = streams.get(0),
                    input2 = streams.get(1),
                    output;
            switch (mode) {
                case "APPEND":
                    output = append(input1, input2);
                    break;

                case "COMBINE":
                    output = combine(input1, input2, config);
                    break;

                case "CHOOSE_BRANCH":
                    output = chooseBranch(input1, input2, config);
                    break;

                default:
                    throw new IllegalArgumentException("Unsupported MERGE mode: " + mode);
            }

            context.setNodeOutputItems(node.getId(), output);

            return new NodeExecutionResult("MERGE " + mode + " completed; output record: " + output.size(), 0, null);


        } catch (Exception e) {
            throw new RuntimeException("Merge execution failed: " + e.getMessage());

        }

    }

    private List<Map<String, Object>> append(
            List<Map<String, Object>> input1,
            List<Map<String, Object>> input2
    ) {
        List<Map<String, Object>> output = new ArrayList<>();

        output.addAll(input1);
        output.addAll(input2);

        return output;
    }

    private List<Map<String, Object>> combine(
            List<Map<String, Object>> input1,
            List<Map<String, Object>> input2,
            JsonNode config

    ) {

        String combineBy = requiredConfig(config, "combineBy").toUpperCase();
        String conflictPolicy = requiredConfig(config, "conflictPolicy").toUpperCase();

        boolean keepUnmatched = config.has("keepUnmatched")
                ? config.get("keepUnmatched").asBoolean() : true;

        validateConflictPolicy(conflictPolicy);

        switch (combineBy) {
            case "POSITION":
                return combineByPosition(input1, input2, conflictPolicy, keepUnmatched);

            case "MATCHING_FIELD":
                List<String> matchingField = readMatchingField(config);

                return combineByMatchingField(
                        input1,
                        input2,
                        matchingField,
                        conflictPolicy,
                        keepUnmatched
                );
            default:
                throw new IllegalArgumentException("Unsupported combineBy: " + combineBy);
        }
    }

    private List<Map<String, Object>> combineByPosition(List<Map<String, Object>> input1,
                                                        List<Map<String, Object>> input2,
                                                        String conflictPolicy,
                                                        boolean keepUnmatched
    ) {
        List<Map<String, Object>> output = new ArrayList<>();

        int pairedCount = Math.min(input1.size(), input2.size());

        for (int i = 0; i < pairedCount; i++) {
            output.add(mergeRecords(input1.get(i), input2.get(i), conflictPolicy));
        }

        if (keepUnmatched) {
            for (int i = pairedCount; i < input1.size(); i++) {
                output.add(new HashMap<>(input1.get(i)));
            }

            for (int i = pairedCount; i < input2.size(); i++) {
                output.add(new HashMap<>(input2.get(i)));
            }
        }

        return output;
    }

    private List<Map<String, Object>> combineByMatchingField(List<Map<String, Object>> input1,
                                                             List<Map<String, Object>> input2,
                                                             List<String> matchingField,
                                                             String conflictPolicy,
                                                             boolean keepUnmatched
    ) {
        List<Map<String, Object>> output = new ArrayList<>();
        Set<Integer> matchedInput2Idx = new HashSet<>();

        for (Map<String, Object> record1 : input1) {
            boolean matched = false;

            for (int i = 0; i < input2.size(); i++) {
                if (matchedInput2Idx.contains(i)) {
                    continue;
                }
                Map<String, Object> record2 = input2.get(i);
                if (recordMatch(record1, record2, matchingField)) {
                    output.add(mergeRecords(record1, record2, conflictPolicy));

                    matchedInput2Idx.add(i);
                    matched = true;
                    break;
                }
            }

            if (!matched && keepUnmatched) {
                output.add(new HashMap<>(record1));
            }
        }

        if (keepUnmatched) {
            for (int i = 0; i < input2.size(); i++) {
                if (!matchedInput2Idx.contains(i)) {
                    output.add(new HashMap<>(input2.get(i)));
                }
            }
        }

        return output;

    }

    private boolean recordMatch(Map<String, Object> record1,
                                Map<String, Object> record2,
                                List<String> matchingField
    ) {
        for (String field : matchingField) {
            if (!record1.containsKey(field) || !record2.containsKey(field)) {
                return false;
            }

            Object value1 = record1.get(field),
                    value2 = record2.get(field);

            if (value1 == null || value2 == null) {
                return false;
            }
        }

        return true;
    }

    private Map<String, Object> mergeRecords(Map<String, Object> record1,
                                             Map<String, Object> record2,
                                             String conflictPolicy
    ) {
        Map<String, Object> merged = new LinkedHashMap<>();

        for (Map.Entry<String, Object> entry : record2.entrySet()) {
            String key = entry.getKey();
            Object value2 = entry.getValue();

            if (!merged.containsKey(key)) {
                merged.put(key, value2);
                continue;
            }

            Object value1 = merged.get(key);
            switch (conflictPolicy) {
                case "PEREFER_INPUT_1":
                    break;
                case "PEREFER_INPUT_2":
                    merged.put(key, value2);
                    break;
                case "ERROR":
                    throw new IllegalArgumentException("Conflicting field '" + key + "' while combining records");
                default:
                    throw new IllegalArgumentException("Unsupported conflict Policy: " + conflictPolicy);
            }
        }

        return merged;
    }

    private List<Map<String, Object>> chooseBranch(
            List<Map<String, Object>> input1,
            List<Map<String, Object>> input2,
            JsonNode config
    ) {
        String selectedInput = requiredConfig(config, "input").toUpperCase();

        switch (selectedInput) {
            case "INPUT_1":
                return new ArrayList<>(input1);

            case "INPUT_2":
                return new ArrayList<>(input2);

            default:
                throw new IllegalArgumentException("CHOOSE_BRANCH input must be INPUT_1 or INPUT_2");
        }
    }

    private List<String> readMatchingField(JsonNode config) {
        JsonNode fieldsNode = config.get("matchingField");

        if (fieldsNode == null || !fieldsNode.isArray() || fieldsNode.isEmpty()) {
            throw new IllegalArgumentException("MATCHING_FIELD requires a non-empty matching fields array");
        }

        List<String> fields = new ArrayList<>();

        for (JsonNode fieldNode : fieldsNode) {
            String field = fieldNode.asString();

            if (field == null || field.isBlank()) {
                throw new IllegalArgumentException("matching Field cannot contain blank field names");
            }

            fields.add(field);
        }

        return fields;
    }

    private String requiredConfig(JsonNode config, String key) {
        JsonNode value = config.get(key);

        if (value == null || value.isNull() || value.asString() == null || value.asString().isBlank()) {
            throw new IllegalArgumentException("Missing or blank MERGE configuration: " + key);
        }

        return value.asString();
    }

    private void validateConflictPolicy(String policy) {
        if (!policy.equals("PREFER_INPUT_1")
                && !policy.equals("PREFER_INPUT_2")
                && !policy.equals("ERROR")
        ) {
            throw new IllegalArgumentException("Unsupported conflictPolicy: " + policy);
        }

    }

}
