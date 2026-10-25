package com.project.Automated_Platform.service.executors;

import com.project.Automated_Platform.entity.Node;
import com.project.Automated_Platform.exception.HttpStatusException;
import com.project.Automated_Platform.exception.NodeExecutionOutcomeUnknownException;
import com.project.Automated_Platform.service.execution.NodeExecutionContext;
import com.project.Automated_Platform.service.execution.NodeExecutionResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class HttpResponseNodeExecutor {

    private static final int MAX_ERROR_BODY_LENGTH= 2000;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private RestClient restClient;

    private Logger log = LoggerFactory.getLogger(HttpResponseNodeExecutor.class);

    @Autowired
    private NodeConfigurationParser nodeConfigurationParser;

    public NodeExecutionResult execute(Node node, NodeExecutionContext context, String idempotencyKey) {

        JsonNode config = nodeConfigurationParser.parse(node.getConfiguration(), node.getType().toString());

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

        String methodName  = requiredString(config, "method").toUpperCase(),
        url = requiredString(config, "url");

        HttpMethod method;
        try {
            method = HttpMethod.valueOf(methodName);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unsupported HTTP method: " + methodName, e);
        }

//        if(!url.startsWith("http://") && !url.startsWith("https://")) {
//            throw new IllegalArgumentException("HTTP_REQUEST URL must HTTP or HTTPS");
//        }

        URI uri = buildUri(url, config.get("queryParams"));
        Map<String, String> headers = readStringMap(config.get("headers"));
        Object body = readBody(config.get("body"));

        RestClient.RequestBodySpec request = restClient
                .method(method)
                .uri(uri);

        headers.forEach(request::header);

        if(body != null) {
            request.body(body);
        }

        Map<String, Object> response ,
        output = new LinkedHashMap<>();

        try {
            response = request.exchange((httpRequest, httpResponse) -> {
                int statusCode = httpResponse.getStatusCode().value();



//               Map<String, Object> result = new LinkedHashMap<>();
//                result.put("Status Code", statusCode);

                String contentType = httpResponse.getHeaders().getContentType() != null
                        ? httpResponse.getHeaders().getContentType().toString() : "";

                byte[] responseBytes = httpResponse.getBody().readAllBytes();

                Object responseBody = parseResponseBody(responseBytes, contentType);

                if (httpResponse.getStatusCode().isError()) {
                    String errorBody = responseBytes.length == 0
                            ? ""
                            : new String(responseBytes, StandardCharsets.UTF_8);

                    if(errorBody.length() > MAX_ERROR_BODY_LENGTH) {
                        errorBody = errorBody.substring(
                                0, MAX_ERROR_BODY_LENGTH
                        ) + "...[truncated]";
                    }

                    String message ="HTTP request returned status " + statusCode;

                    if(!errorBody.isBlank()) {
                        message += "; response body: " + errorBody;
                    }

                    throw new HttpStatusException(statusCode, message);
                }

                Map<String, Object> result = new LinkedHashMap<>();
                result.put("Status Code", statusCode);
                result.put("body", responseBody);
                result.put("headers", httpResponse.getHeaders().toSingleValueMap());

                return result;
            });

        } catch (HttpStatusException e) {
            throw e;

        } catch (Exception e) {
            log.error("HTTP node '{}' failed with {}", node.getName(), e.getClass().getSimpleName());

            throw new NodeExecutionOutcomeUnknownException("HTTP request may have reached the server, but its result could not be obtained or processed. "
                    ,e);
        }

        output.put("status", "success");
        output.put("body", response);

        context.setNodeOutputItems(node.getId(), List.of(output));

        return new NodeExecutionResult("HTTP " + method + " request completed", 0, null);
    }

    private URI buildUri(String url, JsonNode queryParams) {

        if(!url.startsWith("http://") && !url.startsWith("https://")) {
            throw new IllegalArgumentException("HTTP Request URL must use http or https");
        }

        UriComponentsBuilder builder = UriComponentsBuilder.fromUriString(url);

        if(queryParams != null && queryParams.isObject()) {
            if(!queryParams.isObject()) {
                throw new IllegalArgumentException("queryParams must be JSON object");
            }

            queryParams.properties().forEach(entry -> {
                JsonNode value = entry.getValue();

                if (value.isNull()) {
                    builder.queryParam(entry.getKey(), "");
                } else if (value.isValueNode()) {
                    builder.queryParam(entry.getKey(), value.asString());
                } else {
                    builder.queryParam(
                            entry.getKey(), value.toString()
                    );
                }
            });
        }

        URI uri = builder.build().encode().toUri();

        if(uri.getHost() == null || uri.getUserInfo() != null || uri.getFragment() != null) {
            throw new IllegalArgumentException("HTTP request URL is invalid");
        }

        return uri;
    }

    private Object parseResponseBody(byte[] responseBody, String contentType)  {
        if(responseBody.length == 0) {
            return null;
        }

        String normalizedContentType = contentType.toLowerCase(Locale.ROOT);

        if(normalizedContentType.contains("json") || normalizedContentType.contains("+json")) {
            return objectMapper.readTree(responseBody);
        }

        return new String(responseBody, StandardCharsets.UTF_8);
    }

    private Map<String, String> readStringMap(JsonNode node) {
        Map<String, String> result = new LinkedHashMap<>();

        if(node  == null || node.isNull()) {
            return result;
        }

        if(!node.isObject()) {
            throw new IllegalArgumentException("headers must be a JSON object");
        }

        node.properties().forEach(entry -> {
            JsonNode value = entry.getValue();

            if (!value.isValueNode() || value.isNull()) {
                throw new IllegalArgumentException("header value must be scalar value: " + entry.getKey());
            }

            String headerName = entry.getKey();

            if(headerName.isBlank() || headerName.indexOf("\r") >= 0 || headerName.indexOf("\n") >= 0) {
                throw new IllegalArgumentException("Invalid header name");
            }
            String headerValue = value.asString();

            if(headerValue.indexOf("\r") >= 0 || headerValue.indexOf("\n") >= 0) {
                throw new IllegalArgumentException("Invalid value for header: " + headerName);
            }

            result.put(headerName, headerValue);
        });

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
