package com.project.Automated_Platform.repository;

import com.project.Automated_Platform.entity.Execution;
import com.project.Automated_Platform.entity.status.ExecutionStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface ExecutionRepository extends JpaRepository<Execution, Long> {

    List<Execution> findByWorkflowId(Long workflowId);

    List<Execution> findByStatus(ExecutionStatus status);

    @Modifying
    @Transactional
    @Query("""
                            UPDATE Execution e 
                            SET
                                        e.status = :running,
                                        e.workerId = :workerId,
                                        e.leaseExpiresAt = :leaseExpiresAt
                            WHERE e.id = :executionId 
                            AND e.status = :queued
            """)
    int claimExecution(
            @Param("executionId") Long executionId,
            @Param("queued") ExecutionStatus queued,
            @Param("running") ExecutionStatus running,
            @Param("workerId") String workerId,
            @Param("leaseExpiresAt")LocalDateTime leaseExpiresAt
            );

    @Modifying
    @Transactional
    @Query("""
                            UPDATE Execution e 
                            SET e.leaseExpiresAt = :newExpiry
                            WHERE 
                                        e.id = :executionId 
                                        AND e.status = :running 
                                        AND e.workerId = :workerId             
                                        AND e.leaseExpiresAt > :now
            """)
    int renewLease(
            @Param("executionId") Long executionId,
            @Param("running") ExecutionStatus running,
            @Param("workerId") String workerId,
            @Param("now")LocalDateTime now,
            @Param("newExpiry") LocalDateTime newExpiry
            );


    @Modifying
    @Transactional
    @Query("""
                            UPDATE Execution e 
                            SET e.leaseExpiresAt = null,
                            e.status = :terminalStatus,
                            e.finishedAt = :finishedAt,
                            e.result = :result,
                            e.workerId = null,
                            e.outputData = :outputData
                            WHERE 
                                        e.id = :executionId 
                                        AND e.workerId = :workerId
                                        AND e.status = :running 
                                        AND e.leaseExpiresAt IS NOT NULL
                                        AND e.leaseExpiresAt > :now   
                                                               
            """)
    int completeExecution(
            @Param("executionId") Long executionId,
            @Param("running") ExecutionStatus running,
            @Param("workerId") String workerId,
            @Param("terminalStatus") ExecutionStatus terminalStatus,
            @Param("finishedAt") LocalDateTime finishedAt,
            @Param("result") String result,
            @Param("now")LocalDateTime now,
            @Param("outputData")JsonNode outputData
            );


    @Modifying
    @Transactional
    @Query("""
                            UPDATE Execution e 
                            SET e.leaseExpiresAt = null,
                            e.status = :failed,
                            e.finishedAt = :now,
                            e.result = :reason,
                            e.workerId = null
                            WHERE 
                                        e.id = :executionId 
                                        AND e.status = :running 
                                        AND e.workerId = :workerId
                                        AND e.leaseExpiresAt IS NOT NULL
                                        AND e.leaseExpiresAt <= :now             
            """)
    int recoverExpiredExecution(
            @Param("executionId") Long executionId,
            @Param("running") ExecutionStatus running,
            @Param("failed") ExecutionStatus failed,
            @Param("now")LocalDateTime now,
            @Param("reason") String reason,
            @Param("workerId") String workerId
            );


}
