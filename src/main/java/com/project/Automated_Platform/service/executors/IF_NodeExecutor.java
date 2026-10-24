package com.project.Automated_Platform.service.executors;

import com.project.Automated_Platform.entity.Node;
import com.project.Automated_Platform.service.execution.NodeExecutionContext;
import com.project.Automated_Platform.service.execution.NodeExecutionResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class IF_NodeExecutor {

    @Autowired
    private NodeConfigurationParser nodeConfigurationParser;

    public NodeExecutionResult execute(Node node, NodeExecutionContext context) {

        JsonNode config = nodeConfigurationParser.parse(node.getConfiguration(), node.getType().toString());

        String field = requiredString(config, "field");
        String operation = requiredString(config, "operation");
        JsonNode expectedValue = config.get( "value");

        if(expectedValue == null) {
            throw new IllegalArgumentException("IF required a value configuration");
        }

        Object expected = expectedValue.isNull()
                ? null
                : expectedValue.isValueNode()
                  ? expectedValue.asString()
                  : expectedValue.toString();

        List<Map<String, Object>>
                trueItems = new ArrayList<>(),
                falseItems = new ArrayList<>(),
                inputItems = getInputItems(node, context);

        for (Map<String, Object> item : inputItems) {
            Object actual= item.get(field);
            
            if(evaluate(actual, expected, operation)) {
                trueItems.add(new LinkedHashMap<>(item));
            } else {
                falseItems.add(new LinkedHashMap<>(item));
            }
        }
        
        context.setNodeOutputItems(node.getId(), trueItems);
        context.putNodeOutput(node.getId(), "trueItems", trueItems);
        context.putNodeOutput(node.getId(), "falseItems", falseItems);

        return new NodeExecutionResult("IF evaluated " + inputItems.size() 
                + " item(s): true=" + trueItems.size() 
                + ", false=" + falseItems.size(),
                0, 
                null
        );
    }

    private List<Map<String, Object>> getInputItems(Node node, NodeExecutionContext context) {
       List<List<Map<String, Object>>> streams = context.getNodeInputStream(node.getId());
        List<Map<String, Object>> items = new ArrayList<>();

        if(streams != null && !streams.isEmpty()) {
            for(List<Map<String,Object>> stream : streams) {
                if(stream != null) {
                    items.addAll(stream);
                }
            }
        }

        if(!items.isEmpty()) {
            return items;
        }

        Map<String, Object> input =  context.getNodeInputs(node.getId());

       return input == null || input.isEmpty()
               ? new ArrayList<>()
               : new ArrayList<>(List.of(new LinkedHashMap<>(input)));
    }

    private boolean evaluate(Object actual, Object expected, String operation) {

        return switch (operation) {
            case "EQUALS" -> equalValues(actual, expected);
            case "NOT_EQUALS" -> !equalValues(actual, expected);
            case "GREATER_THAN" -> compareNumbers(actual, expected) > 0;
            case "GREATER_THAN_OR_EQUAL" -> compareNumbers(actual, expected) >= 0;
            case "LESS_THAN" -> compareNumbers(actual, expected) < 0;
            case "LESS_THAN_OR_EQUAL" -> compareNumbers(actual, expected) <= 0;
            default -> throw new IllegalArgumentException("Unsupported IF operations: " + operation);
        };
    }

    private boolean equalValues(Object actual, Object expected) {
        if(actual == null || expected == null) {
            return actual == expected;
        }

        if(actual instanceof Number && expected instanceof Number) {
            return new BigDecimal(actual.toString()).compareTo(new BigDecimal(expected.toString())) == 0;
        }

        return actual.toString().equals(expected.toString());
    }
    private int compareNumbers(Object actual, Object expected) {
        if(!(actual instanceof Number) || !(expected instanceof Number)) {
            throw new IllegalArgumentException("Comparison operators require numeric value");
        }

        return new BigDecimal(actual.toString()).compareTo(new BigDecimal(expected.toString()));
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
}
