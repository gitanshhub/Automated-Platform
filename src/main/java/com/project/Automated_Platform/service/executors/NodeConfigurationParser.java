package com.project.Automated_Platform.service.executors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
public class NodeConfigurationParser {

    @Autowired
    private ObjectMapper objectMapper;

    public JsonNode parse(JsonNode configuration, String nodeType) {
        if(configuration == null || configuration.isNull()) {
            throw new IllegalArgumentException(nodeType + "requires configuration");
        }

        if(configuration.isString()) {
            try {
                configuration = objectMapper.readTree(
                        configuration.asString()
                );

            } catch (Exception e) {
                throw new IllegalArgumentException(nodeType + "configuration contains invalid JSON", e);
            }
        }

        if(!configuration.isObject()) {
            throw new IllegalArgumentException(nodeType + "configuration must be a JSON object");
        }

        return configuration;
    }
}
