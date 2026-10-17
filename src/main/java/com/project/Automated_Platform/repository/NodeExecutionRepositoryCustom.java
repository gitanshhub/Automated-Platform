package com.project.Automated_Platform.repository;

import com.project.Automated_Platform.entity.NodeExecutionStatus;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;

public interface NodeExecutionRepositoryCustom {
    Long createNodeExecutionIfOwned(
            Long executionId,
            Long nodeId,
            String workerId,
            NodeExecutionStatus status,
            LocalDateTime startedAt,
            LocalDateTime now
    );
}
