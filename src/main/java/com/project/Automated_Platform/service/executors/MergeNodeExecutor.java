package com.project.Automated_Platform.service.executors;

import com.project.Automated_Platform.entity.Node;
import com.project.Automated_Platform.service.execution.NodeExecutionContext;
import com.project.Automated_Platform.service.execution.NodeExecutionResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.*;

@Service
public class MergeNodeExecutor {

    @Autowired
    private NodeConfigurationParser nodeConfigurationParser;

    public NodeExecutionResult execute(Node node, NodeExecutionContext context) {
        try {
            JsonNode config = nodeConfigurationParser.parse(node.getConfiguration(), node.getType().toString());

            String mode = requiredConfig(config, "mode").toUpperCase(Locale.ROOT);

            List<List<Map<String, Object>>> streams = context.getNodeInputStream(node.getId());

            if (streams  == null || streams.size() != 2) {

                throw new IllegalArgumentException("MERGE requires exactly 2 input streams; received " + (streams == null ? 0 : streams.size()));
            }

            List<Map<String, Object>>
                    input1 = streams.get(0),
                    input2 = streams.get(1),
                    output;

            if(input1 == null || input2 == null) {
                throw new IllegalArgumentException("MERGE input streams cannot be null");
            }

            output = switch (mode) {
                case "APPEND" -> append(input1, input2);
                case "COMBINE" -> combine(input1, input2, config);
                case "CHOOSE_BRANCH" -> chooseBranch(input1, input2, config);
                case "NAMESPACE" -> namespace(input1, input2, config);
                default -> throw new IllegalArgumentException("Unsupported MERGE mode: " + mode);
            };

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

            if (value1 == null || !value1.equals(value2)) {
                return false;
            }
        }

        return true;
    }

    private Map<String, Object> mergeRecords(Map<String, Object> record1,
                                             Map<String, Object> record2,
                                             String conflictPolicy
    ) {
        Map<String, Object> merged = new LinkedHashMap<>(record1);

        for (Map.Entry<String, Object> entry : record2.entrySet()) {
            String key = entry.getKey();
            Object value2 = entry.getValue();

            if (!merged.containsKey(key)) {
                merged.put(key, value2);
                continue;
            }

            Object value1 = merged.get(key);
            switch (conflictPolicy) {
                case "PREFER_INPUT_1":
                    break;
                case "PREFER_INPUT_2":
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

    private List<Map<String, Object>> namespace(List<Map<String, Object>> input1, List<Map<String, Object>> input2, JsonNode config) {
        String input1_Name = optionalNameSpace(config, "input1_Name", "input1");

        String input2_Name = optionalNameSpace(config, "input2_Name", "input2");

        if (input1_Name.equals(input2_Name))
        {
            throw new IllegalArgumentException("NAMESPACE input names must be different");
        }

        List<Map<String, Object>> output = new ArrayList<>();
        int recordCount = Math.max(input1.size(), input2.size());

        for(int i = 0; i < recordCount; i++) {
            Map<String, Object> merged = new LinkedHashMap<>();

            if(i < input1.size()) {
                merged.put(input1_Name, new LinkedHashMap<>(input1.get(i)));
            }

            if(i < input2.size()) {
                merged.put(input2_Name, new LinkedHashMap<>(input2.get(i)));
            }
            output.add(merged);
        }

        return output;

    }

    private String optionalNameSpace(JsonNode config, String key, String defaultValue) {
        JsonNode value = config.get(key);

        if(value == null) {
            return defaultValue;
        }

        if(!value.isString() || value.asString().isBlank()) {
            throw new IllegalArgumentException(key + " must be a non-blank string");
        }
        return value.asString().trim();
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
