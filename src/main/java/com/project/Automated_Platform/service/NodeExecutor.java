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
    private TelegramNodeExecutor telegramNodeExecutor;

    @Autowired
    private ManualTriggerNodeExecutor manualTriggerNodeExecutor;

    @Autowired
    private GmailNodeExecutor gmailNodeExecutor;

    public NodeExecutionResult execute(Node node, NodeExecutionContext context, String idempotencyKey ) {
        return switch (node.getType()) {
            case MANUAL_TRIGGER -> manualTriggerNodeExecutor.execute(node, context);
            case WEBHOOK -> webhookNodeExecutor.execute(node, context);
            case HTTP_REQUEST -> httpResponseNodeExecutor.execute(node, context, idempotencyKey);
            case DELAY -> delayNodeExecutor.execute(node, context);
            case IF -> ifNodeExecutor.execute(node, context);
            case TRANSFORM -> transformNodeExecutor.execute(node, context);
            case MERGE -> mergeNodeExecutor.execute(node, context);
            case TELEGRAM -> telegramNodeExecutor.execute(node, context);
            case GMAIL -> gmailNodeExecutor.execute(node, context);
        };

    }
}
