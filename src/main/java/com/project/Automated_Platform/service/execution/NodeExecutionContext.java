package com.project.Automated_Platform.service.execution;


import lombok.Getter;
import lombok.Setter;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Getter
@Setter
public class NodeExecutionContext {
    //what each node has produced.
    private Map<Long, Map<String, Object>> nodeOutput = new ConcurrentHashMap<>();

    // Multi-item outputs. Used when a node produces multiple records.
    private Map<Long, List<Map<String, Object>>> nodeOutputItems = new ConcurrentHashMap<>();

    //what data the engine has prepared for each node to consume.
    private Map<Long, Map<String, Object>> nodeInput = new ConcurrentHashMap<>();

    // Ordered input streams for each node.
    // Outer list: incoming inputs in configured order.
    // Inner list: records produced by one incoming input.
    private Map<Long, List<List<Map<String, Object>>>> nodeInputStreams = new ConcurrentHashMap<>();


    public void putNodeOutput(Long nodeId, String key, Object value) {
        nodeOutput
                .computeIfAbsent(nodeId, id -> new ConcurrentHashMap<>())
                .put(key, value);

        if(!"result".equals(key)){
            nodeOutputItems.remove(nodeId);
        }
    }
    public Object getNodeOutput(Long nodeId, String key) {
        Map<String, Object> outputs = nodeOutput.get(nodeId);

        return outputs == null ? null : outputs.get(key);
    }

    public Map<String, Object> getNodeOutputs(Long nodeId) {
        return nodeOutput.getOrDefault(nodeId, Map.of());
    }

    public void setNodeInput(Long nodeId, Map<String, Object> input) {
        nodeInput.put(nodeId, new ConcurrentHashMap<>(input));
    }

    public Object getNodeInputValue(Long nodeId, String key) {
        Map<String, Object> input = nodeInput.get(nodeId);

        return input  == null ? null : input.get(key);
    }

    public Map<String, Object> getNodeInputs(Long nodeId) {
        return nodeInput.getOrDefault(nodeId, Map.of());
    }

    public void setNodeOutputItems(Long nodeId, List<Map<String, Object>> items) {

        List<Map<String, Object>> copiedItems = new ArrayList<>();

        for(Map<String, Object> item : items) {
            copiedItems.add(new HashMap<>(item));
        }

        nodeOutputItems.put(nodeId, copiedItems);

        if(copiedItems.isEmpty()) {
            nodeOutput.put(nodeId, new ConcurrentHashMap<>());

        } else {
            nodeOutput.put(nodeId, new ConcurrentHashMap<>(copiedItems.get(0)));
        }
    }

    public List<Map<String, Object>> getNodeOutputItems(Long nodeId) {
        List<Map<String, Object>> items = nodeOutputItems.get(nodeId);

        if(items != null) {
            return copyItems(items);
        }

        Map<String, Object> output = nodeOutput.get(nodeId);

        return output == null
                ? List.of()
                : List.of(new HashMap<>(output));
    }

    public void setNodeInputStreams(Long nodeId, List<List<Map<String, Object>>> streams) {
        List<List<Map<String, Object>>> copiedStreams = new ArrayList<>();

        for(List<Map<String, Object>> stream : streams) {
            copiedStreams.add(copyItems(stream));
        }

        nodeInputStreams.put(nodeId, copiedStreams);
    }

    public List<List<Map<String, Object>>> getNodeInputStream(Long nodeId) {
        List<List<Map<String, Object>>> streams = nodeInputStreams.get(nodeId);

        if(streams == null) {
            return List.of();
        }

        List<List<Map<String, Object>>> copiedStreams = new ArrayList<>();

        for(List<Map<String, Object>> stream : streams) {
            copiedStreams.add(copyItems(stream));
        }

        return Collections.unmodifiableList(copiedStreams);
    }

    private List<Map<String, Object>> copyItems(List<Map<String, Object>> items) {
        List<Map<String, Object>> copies = new ArrayList<>();

        for(Map<String, Object> item : items) {
            copies.add(new HashMap<>(item));
        }

        return Collections.unmodifiableList(copies);
    }


}
