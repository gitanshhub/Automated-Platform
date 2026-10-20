package com.project.Automated_Platform.service.executors;

import com.project.Automated_Platform.entity.Node;
import com.project.Automated_Platform.service.execution.NodeExecutionContext;
import com.project.Automated_Platform.service.execution.NodeExecutionResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class HttpResponseNodeExecutor {

    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private RestClient restClient;

    public NodeExecutionResult execute(Node node, NodeExecutionContext context) {

        JsonNode config = node.getConfiguration();

        if(config == null || config.isNull()) {
            throw new IllegalArgumentException("HTTP_REQUEST requires configuration");
        }

        if(config.isString()) {
            try {
            config = objectMapper.readTree(config.asString());
            } catch (Exception e) {
                throw new IllegalArgumentException("Http request is not valid JSON", e);
            }
        }

        if(!config.isObject()) {
            throw new IllegalArgumentException("Http request configuration must be JSON object");
        }

        String method  = requiredString(config, "method").toUpperCase(),
        url = requiredString(config, "url");

        if(!url.startsWith("http://") && !url.startsWith("https://")) {
            throw new IllegalArgumentException("HTTP_REQUEST URL must HTTP or HTTPS");
        }

        URI uri = buildUri(url, config.get("queryParams"));

        Map<String, String> headers = readStringMap(config.get("headers"));

        Object body = readBody(config.get("body"));

        RestClient.RequestBodySpec request = restClient
                .method(HttpMethod.valueOf(method))
                .uri(uri);

        headers.forEach(request::header);

        if(body != null) {
            request.body(body);
        }

        Map<String, Object> response = request.retrieve().body(Map.class),
        output = new LinkedHashMap<>();

        output.put("status", "success");
        output.put("body", response == null ? Map.of() : response);

        context.setNodeOutputItems(node.getId(), List.of(output));

        return new NodeExecutionResult("HTTP " + method + " request completed", 0, null);
    }

    private URI buildUri(String url, JsonNode queryParams) {
        UriComponentsBuilder builder = UriComponentsBuilder.fromUriString(url);

        if(queryParams != null && queryParams.isObject()) {
            queryParams.properties().forEach(entry ->
                    builder.queryParam(
                            entry.getKey(),
                            entry.getValue().isValueNode() ? entry.getValue().asString() : entry.getValue().toString()
                    ));
        }

        return builder.build().encode().toUri();
    }

    private Map<String, String> readStringMap(JsonNode node) {
        Map<String, String> result = new LinkedHashMap<>();

        if(node  == null || !node.isObject()) {
            return result;
        }

        node.properties().forEach(entry ->
                result.put(entry.getKey(), entry.getValue().asString()));

        return result;
    }

    private Object readBody(JsonNode body) {
        if(body == null || body.isNull()) {
            return null;
        }

        return objectMapper.convertValue(body, Object.class);
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
