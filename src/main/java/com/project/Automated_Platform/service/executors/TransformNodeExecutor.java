package com.project.Automated_Platform.service.executors;

import com.project.Automated_Platform.entity.Node;
import com.project.Automated_Platform.service.execution.NodeExecutionContext;
import com.project.Automated_Platform.service.execution.NodeExecutionResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class TransformNodeExecutor {
    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private NodeConfigurationParser nodeConfigurationParser;

    public NodeExecutionResult execute(Node node, NodeExecutionContext context) {

        JsonNode config = nodeConfigurationParser.parse(node.getConfiguration(), node.getType().toString());

        JsonNode operations = config == null ? null : config.get("operations");

        System.out.println("config : "+config + "/n is obj: "+ (config != null && config.isObject()));
        System.out.println("type : "+ node.getType());
        System.out.println("operations: " + operations);

        if(operations == null || !operations.isArray()) {
            throw new IllegalArgumentException("TRANSFORM node requires an operation array");
        }
        List<Map<String, Object>> inputItems = getInputItems(node, context),
        outputItems = new ArrayList<>();

        for(Map<String, Object> inputItem : inputItems) {
            Map<String, Object> item = new LinkedHashMap<>(inputItem);

            for(JsonNode operation : operations) {
                applyOperation(operation, item);
            }

            outputItems.add(item);
        }

        context.setNodeOutputItems(node.getId(), outputItems);

        return new NodeExecutionResult("Transformed " + outputItems.size() + "item(s)", 0, null);
    }

    private List<Map<String, Object>> getInputItems(Node node, NodeExecutionContext context) {
        List<List<Map<String, Object>>> inputStreams = context.getNodeInputStream(node.getId());
        List<Map<String, Object>> items = new ArrayList<>();

        if(inputStreams != null && !inputStreams.isEmpty()) {
            for(List<Map<String, Object>> stream : inputStreams) {
                if(stream != null) {
                    items.addAll(stream);
                }
            }
        }
        if(!items.isEmpty()) {
            return items;
        }

        Map<String, Object> fallback = context.getNodeInputs(node.getId());

        if(fallback == null || fallback.isEmpty()) {
            return new ArrayList<>();
        }

        return new ArrayList<>(List.of(new LinkedHashMap<>(fallback)));
    }

    private void applyOperation(JsonNode operation, Map<String, Object> item) {

        String type = requiredString(operation, "type");

        switch (type) {
            case "SELECT_FIELD" -> {
                JsonNode fields = operation.get("field");
                if(fields == null || !fields.isArray()) {
                    throw new IllegalArgumentException("SELECT_FIELD requires a field array");
                }
                Map<String, Object> selected = new LinkedHashMap<>();
                for(JsonNode field : fields) {
                    String fieldName = field.asString();

                    if(item.containsKey(fieldName)) {
                        selected.put(fieldName, item.get(fieldName));
                    }
                }

                item.clear();
                item.putAll(selected);
            }

            case "RENAME" -> {
                String source = requiredString(operation, "sourceField"),
                        target = requiredString(operation, "targetField");
                if(item.containsKey(source)) {
                    Object value = item.remove(source);
                    item.put(target, value);
                }
            }

            case "SET_FIELD" -> {
                String field = requiredString(operation, "targetField");
                JsonNode valueNode = operation.get("value");

                if(valueNode == null) {
                    throw new IllegalArgumentException("SET_FIELD requires a value");
                }

                Object value = objectMapper.convertValue(valueNode, Object.class);

                item.put(field, value);
            }

            case "REMOVE_FIELD" -> {
                String field = requiredString(operation, "sourceField");
                item.remove(field);
            }

            case "UPPERCASE" -> transformString(
                        item,
                        requiredString(operation, "sourceField"),
                        operation.hasNonNull("targetField")
                                ? operation.get("targetField").asString()
                                : null,
                        String::toUpperCase
            );

            case "LOWERCASE" -> transformString(
                    item,
                    requiredString(operation, "sourceField"),
                    operation.hasNonNull("targetField")
                            ? operation.get("targetField").asString()
                            : null,
                    String::toLowerCase
            );

            case "TRIM" -> transformString(
                    item,
                    requiredString(operation, "sourceField"),
                    operation.hasNonNull("targetField")
                            ? operation.get("targetField").asString()
                            : null,
                    String::trim
            );

            case "EXTRACT_DOMAIN" -> {
                String source = requiredString(operation, "sourceField"),
                        target = requiredString(operation, "targetField");

                Object value = item.get(source);

                if(value instanceof String email) {
                    int atIndex = email.lastIndexOf('@');

                    if(atIndex >= 0 && atIndex < email.length()-1) {
                        item.put(target, email.substring(atIndex + 1));
                    }
                }
            }

            case "MAP_FIELDS" -> {
                Map<String, Object> mapped = mapField(item, operation.get("mappings"));

                item.clear();
                item.putAll(mapped);
            }

            default -> throw new IllegalArgumentException("Unsupported TRANSFORM operation" + type);
        }
    }
    private String requiredString(JsonNode config, String field) {
        JsonNode value = config.get(field);

        if(value == null || !value.isString()
                || value.asString().isBlank()
        ) {
            throw new IllegalArgumentException("Missing or invalid configuration field: " + field);
        }

        return value.asString();

    }
    private void transformString(Map<String, Object> item,
                                 String source,
                                 String target,
                                 UnaryOperator<String> transformation
    ) {
        Object value = item.get(source);

        if(value instanceof String text) {
            item.put(target == null ? source : target,
                    transformation.apply(text));
        }

    }

    private Map<String, Object> mapField(Map<String, Object> item, JsonNode mappings) {
        if(mappings == null || !mappings.isObject()) {
            throw new IllegalArgumentException("MAP Field requires a mapping object");
        }

        JsonNode source = objectMapper.valueToTree(item);

        Map<String, Object> output = new LinkedHashMap<>();

        mappings.properties().forEach(entry -> {
            String targetField = entry.getKey();
            JsonNode pathNode = entry.getValue();

            if(!pathNode.isString() || pathNode.asString().isBlank()) {
                throw new IllegalArgumentException("mapping path must be a non-empty string: " + targetField);
            }

            String path = pathNode.asString();
            JsonNode value = resolveJsonPath(source, path);

            if(value == null || value.isMissingNode()) {
                throw new IllegalArgumentException("JSON path '" + path + "' was not found for field '" + targetField + "'");
            }

            output.put(targetField, objectMapper.convertValue(value, Object.class));
        });

        return output;
    }

    private JsonNode resolveJsonPath(JsonNode source, String path) {
        String normalization = path.trim();

        if( normalization.startsWith("$")) {
            normalization = normalization.substring(1);
        }

        if( normalization.startsWith(".")) {
            normalization = normalization.substring(1);
        }

        if(normalization.isEmpty()) {
            return source;
        }

        Matcher matcher = Pattern
                .compile("([^\\.\\[\\]]+)|\\[(\\d+)]")
                .matcher(normalization);

        JsonNode current = source;

        while(matcher.find()) {
            if (current == null || current.isMissingNode() || current.isNull()) {
                return null;
            }

            String field = matcher.group(1);
            String index = matcher.group(2);

            if (field != null) {
                current = current.path(field);
            } else {
                int arrayIdx = Integer.parseInt(index);

                if (!current.isArray() || arrayIdx >= current.size()) {
                    return null;
                }
                current = current.get(arrayIdx);
            }
        }

        return current;
    }
}