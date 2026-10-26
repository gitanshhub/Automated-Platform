package com.project.Automated_Platform.service.executors;


import com.project.Automated_Platform.entity.Node;
import com.project.Automated_Platform.service.execution.NodeExecutionContext;
import com.project.Automated_Platform.service.execution.NodeExecutionResult;
import jakarta.mail.internet.MimeMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.awt.*;
import java.util.*;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class GmailNodeExecutor {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{\\s*([\\w.-]+)\\s*}}");

    @Autowired
    private JavaMailSender mailSender;

    @Autowired
    private NodeConfigurationParser nodeConfigurationParser;

    @Autowired
    private ObjectMapper objectMapper;

    @Value("${spring.mail.username}")
    private String senderAddress;

    public NodeExecutionResult execute(Node node, NodeExecutionContext context) {
        try {
            JsonNode config = nodeConfigurationParser.parse(node.getConfiguration(), node.getType().toString());

            Map<String, Object> inputs = context.getNodeInputs(node.getId());

            if(inputs == null) {
                inputs = Collections.emptyMap();
            }

            String to = resolveRequired(
                    requiredString(config, "to"),
                    inputs, "to"
            );

            String subject = resolveRequired(
                    requiredString(config, "subject"),
                    inputs, "subject"
            );

            String body = resolveRequired(
                    requiredString(config, "body"),
                    inputs, "body"
            );

            boolean isHtml = config.has("isHtml") && config.get("isHtml").asBoolean();

            List<String> recipients = parseAddress(to);
            List<String> cc = resolveOptionalAddresses(config, "cc", inputs);
            List<String> bcc = resolveOptionalAddresses(config, "bcc", inputs);

            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(
                    message, true, "UTF-8"
            );

            helper.setFrom(senderAddress);
            helper.setTo(recipients.toArray(new String[0]));

            if(!cc.isEmpty()) {
                helper.setCc(cc.toArray(new String[0]));
            }
            if(!bcc.isEmpty()) {
                helper.setCc(bcc.toArray(new String[0]));
            }

            helper.setSubject(subject);
            helper.setText(body, isHtml);

            mailSender.send(message);

            Map<String, Object> output = new LinkedHashMap<>();
            output.put("status", "success");
            output.put("to", recipients);
            output.put("subject", subject);
            output.put("sent", true);

            context.setNodeOutputItems(node.getId(), List.of(output));

            return new NodeExecutionResult("Gmail sent successfully to "+ recipients.size() + "recipient(s)", 0, null);

        } catch(Exception e) {
            throw new RuntimeException("Gmail node execution failed: " + e.getMessage(), e);
        }
    }

    private String resolveRequired(String template, Map<String, Object> inputs, String field) {
        String resolved = resolveTemplate(template, inputs);

        if(resolved.isEmpty()) {
            throw new IllegalArgumentException("Resolved Gmail field '" + field + "' is blank");
        }
        return resolved;
    }

    private List<String> resolveOptionalAddresses(JsonNode config, String field, Map<String, Object> inputs) {

        JsonNode value = config.get(field);

        if(value == null || value.isNull()) {
            return Collections.emptyList();
        }

        if(!value.isString()) {
            throw new IllegalArgumentException(
                    "'"+ field +"' must be a string"
            );
        }

        String resolved = resolveTemplate(value.asString(), inputs).trim();

        if(resolved.isEmpty()) {
            return Collections.emptyList();
        }

        return parseAddress(resolved);
    }

    private List<String> parseAddress(String value) {
        List<String> addresses = Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(address -> !address.isEmpty())
                .toList();

        if(addresses.isEmpty()) {
            throw new IllegalArgumentException("At least one mail address is required");
        }

        for(String address : addresses) {
            if(!address.matches("^[A-Za-z0-9.!#$%&'*+/=?^_`{|}~-]+@"
            +"[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?"
            +"(?:\\.[A-Za-z0-9](?:[A-Za-z0-9-]*"
                            +"[A-Za-z0-9])?)+$"
            )) {
                throw new IllegalArgumentException("Invalid email address: " + address);
            }

        }

        return addresses;
    }

    private String requiredString(JsonNode config, String field) {
        JsonNode value = config.get(field);

        if(value == null
                ||value.isNull()
                || !value.isString()
                || value.asString().isBlank()
        ) {
            throw new IllegalArgumentException("Missing or blank Gmail configuration: " + field);
        }

        return value.asString();
    }

    private String resolveTemplate(String template, Map<String, Object> inputs) {
        Matcher matcher =  PLACEHOLDER.matcher(template);
        StringBuilder result = new StringBuilder();

        while(matcher.find()) {
            String path = matcher.group(1);
            Object value = resolvePath(inputs, path);

            if(value == null) {
                throw new IllegalArgumentException("unable to resolve template field: " + path);
            }

            String replacement = value instanceof Map<?, ?> || value instanceof Collection<?>
                    ? objectMapper.writeValueAsString(value)
                    :value.toString();

            matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    private Object resolvePath(Map<String, Object> inputs, String path) {
        Object current = inputs;

        for(String part : path.split("\\.")) {
            if(!(current instanceof Map<?, ?> map) || !map.containsKey(part)) {
                return null;
            }

            current = map.get(part);
        }
        return current;
    }


}
