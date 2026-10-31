package com.project.Automated_Platform.repository;

import com.project.Automated_Platform.entity.status.ExecutionStatus;
import com.project.Automated_Platform.entity.NodeExecution;
import com.project.Automated_Platform.entity.status.NodeExecutionStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface NodeExecutionRepository extends JpaRepository<NodeExecution, Long>, NodeExecutionRepositoryCustom {

    @Modifying
    @Transactional
    @Query("""
            UPDATE NodeExecution ne
            SET ne.status = :status,
            ne.finishedAt = :finishedAt,
            ne.attempts = :attempts,
            ne.output = :output,
            ne.outputData = :outputData
            
            WHERE 
            ne.id = :nodeExecutionId 
            AND ne.execution.id = :executionId 
            AND EXISTS(SELECT e.id FROM Execution e WHERE e.id = :executionId AND e.status = :runningStatus AND e.workerId = :workerId AND e.leaseExpiresAt IS NOT NULL AND e.leaseExpiresAt > :now)
            """)
    int updateResultIfOwned(
            @Param("nodeExecutionId") Long nodeExecutionId,
            @Param("executionId") Long executionId,
            @Param("workerId") String workerId,
            @Param("runningStatus") ExecutionStatus runningStatus,
            @Param("status") NodeExecutionStatus status,
            @Param("finishedAt") LocalDateTime finishedAt,
            @Param("attempts") int attempts,
            @Param("output") String output,
            @Param("now") LocalDateTime now,
            @Param("outputData") JsonNode outputData
    );

    Optional<NodeExecution> findFirstByExecutionIdAndNodeIdAndStartedAt(Long executionId, Long nodeId, LocalDateTime startedAt);

    List<NodeExecution> findByExecutionIdOrderByStartedAtAsc(Long ExecutionId);
}
