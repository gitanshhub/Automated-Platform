package com.project.Automated_Platform.service.executors;

import com.project.Automated_Platform.entity.Node;
import com.project.Automated_Platform.service.execution.NodeExecutionContext;
import com.project.Automated_Platform.service.execution.NodeExecutionResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.JsonNode;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class TelegramNodeExecutor {

    @Autowired
    private RestClient restClient;

    @Autowired
    private NodeConfigurationParser nodeConfigurationParser;

    private Logger log = LoggerFactory.getLogger(TelegramNodeExecutor.class);

    @Value("${telegram.bot.token:}")
    private String botToken;

    public NodeExecutionResult execute(Node node, NodeExecutionContext context) {
        if(botToken == null || botToken.isBlank()) {
            throw new IllegalArgumentException("Telegram bot token is not configured");
        }

        JsonNode config = nodeConfigurationParser.parse(
                node.getConfiguration(),
                node.getType().toString()
        );

        String chatId = resolveValue(config.get("chatId"), context, node.getId(), "chatId");

        String message = resolveValue(config.get("message"), context, node.getId(), "message");

        if(chatId.isBlank()) {
            throw new IllegalArgumentException("Telegram chat id cannot be blank");
        }

        Map<String, Object> requestBody = new LinkedHashMap<>();
        requestBody.put("chat_id", chatId);
        requestBody.put("text", message);

        try {
            JsonNode response = restClient.post()
                    .uri(
                            "https://api.telegram.org/bot" + botToken + "/sendMessage"
                    )
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(requestBody)
                    .retrieve()
                    .body(JsonNode.class);

            if(response == null || !response.path("ok").asBoolean(false)) {
                throw new IllegalStateException("Telegram API did not confirm message delivery");
            }

            JsonNode result = response.path("result");
            Map<String, Object> output = new LinkedHashMap<>();
            output.put("status", "success");
            output.put("chatId", chatId);
            output.put("messageId" , result.path("message_id").asLong());

            output.put("message", message);

            context.setNodeOutputItems(node.getId(), List.of(output));

            return new NodeExecutionResult("Message send successfully", 0, null);

        } catch(RestClientResponseException e) {

            log.warn(e.getResponseBodyAsString(), e);

            throw new IllegalStateException("Telegram API returned HTTP status " + e.getStatusCode().value(), e);
        }

    }

    private String resolveValue(JsonNode configuredValue,
                                NodeExecutionContext context,
                                Long nodeId,
                                String fieldName

    ) {
        if(configuredValue == null || configuredValue.isNull()) {
            throw new IllegalArgumentException("Telegram configuration requires "+ fieldName);
        }

        if(!configuredValue.isValueNode()) {
            throw new IllegalStateException(
                    "Telegram " + fieldName + " must be scalar value"
            );
        }

        String value = configuredValue.asString();
        Pattern pattern = Pattern.compile("\\{\\{input\\.(\\w+)}}");
        Matcher matcher = pattern.matcher(value);
        StringBuilder resolved = new StringBuilder();
        Map<String, Object> inputs = context.getNodeInputs(nodeId);

        while(matcher.find()) {
            String inputKey = matcher.group(1);
            Object input = inputs == null ? null : inputs.get(inputKey);

            if(input == null) {
                throw new IllegalArgumentException("No upstream input found for: " + inputKey);
            }

            matcher.appendReplacement(resolved, Matcher.quoteReplacement(input.toString()));
        }

        matcher.appendTail(resolved);

        return resolved.toString();

    }

}
