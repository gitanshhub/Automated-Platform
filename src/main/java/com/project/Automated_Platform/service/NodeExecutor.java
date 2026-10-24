package com.project.Automated_Platform.service;

import com.project.Automated_Platform.entity.Node;
import com.project.Automated_Platform.service.execution.NodeExecutionContext;
import com.project.Automated_Platform.service.execution.NodeExecutionResult;
import com.project.Automated_Platform.service.executors.*;
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
    private HttpResponseNodeExecutor httpResponseNodeExecutor;

    @Autowired
    private IF_NodeExecutor ifNodeExecutor;

    @Autowired
    private MergeNodeExecutor mergeNodeExecutor;

    @Autowired
    private TransformNodeExecutor transformNodeExecutor;

    @Autowired
    private DelayNodeExecutor delayNodeExecutor;

    @Autowired
    private WebhookNodeExecutor webhookNodeExecutor;

    @Autowired
    private ManualTriggerNodeExecutor manualTriggerNodeExecutor;

    public NodeExecutionResult execute(Node node, NodeExecutionContext context, String idempotencyKey ) {
        switch (node.getType()) {
            case MANUAL_TRIGGER:
                return manualTriggerNodeExecutor.execute(node, context);

            case WEBHOOK:
                return webhookNodeExecutor.execute(node, context);

            case HTTP_REQUEST:
                return httpResponseNodeExecutor.execute(node, context, idempotencyKey);

            case DELAY:
                return delayNodeExecutor.execute(node, context);

            case IF:
                return ifNodeExecutor.execute(node, context);

            case TRANSFORM:
                return transformNodeExecutor.execute(node, context);

            case MERGE:
                return mergeNodeExecutor.execute(node, context);

            default:
                throw new RuntimeException("unsupported node type");
        }

    }
}
