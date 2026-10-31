package com.project.Automated_Platform.dto;

import java.time.LocalDateTime;

import com.project.Automated_Platform.entity.status.NodeExecutionStatus;

import tools.jackson.databind.JsonNode;

public record NodeExecutionResponse (
    Long id,
    Long nodeId,
    String nodeName,
    NodeExecutionStatus status,
    LocalDateTime startedAt,
    LocalDateTime finishedAt,
    int attempts,
    String output,
    JsonNode outputData
) {}
