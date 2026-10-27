package com.project.Automated_Platform.repository;


import com.project.Automated_Platform.entity.status.NodeExecutionStatus;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public class NodeExecutionRepositoryCustomImpl implements NodeExecutionRepositoryCustom {

    @PersistenceContext
    private EntityManager entityManager;


    @Override
    @Transactional
    public Long createNodeExecutionIfOwned( Long executionId, Long nodeId,String workerId, NodeExecutionStatus status, LocalDateTime startedAt, LocalDateTime now) {

        @SuppressWarnings("unchecked")
        List<Number> ids = entityManager.createNativeQuery("""
                            INSERT INTO node_execution
                            (execution_id, node_id, status, started_at, attempts)
                            SELECT e.id, :nodeId, :nodeStatus, :startedAt, 0 
                            FROM execution e 
                            WHERE e.id = :executionId
                            AND e.status = 'RUNNING'
                            AND e.worker_id = :workerId
                            AND e.lease_expires_at IS NOT NULL
                            AND e.lease_expires_at > :now  
                            RETURNING id
                            """)
                .setParameter("executionId", executionId)
                .setParameter("nodeId", nodeId)
                .setParameter("workerId", workerId)
                .setParameter("nodeStatus", status.name())
                .setParameter("startedAt", startedAt)
                .setParameter("now", now)
                .getResultList();


        return ids.isEmpty() ? null : ids.getFirst().longValue();
    }
}
