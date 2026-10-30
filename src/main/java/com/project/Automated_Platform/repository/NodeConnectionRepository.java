package com.project.Automated_Platform.repository;

import com.project.Automated_Platform.entity.Node;
import com.project.Automated_Platform.entity.NodeConnection;
import com.project.Automated_Platform.entity.Workflow;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface NodeConnectionRepository extends JpaRepository<NodeConnection, Long> {

    List<NodeConnection> findByTargetNodeId(long targetNodeId);

    List<NodeConnection> findBySourceNodeId(long sourceNodeId);

    boolean existsBySourceNodeIdAndTargetNodeId(long sourceNodeId, long targetNodeId);

    List<NodeConnection> findBySourceNodeWorkflowId(Long workflowId);

    long countBySourceNodeWorkflowId( Long workflowId);

    long countBySourceNodeId( Long sourceNodeId);

    void deleteBySourceNodeIdOrTargetNodeId(
            Long sourceNodeId,
            Long targetNodeId
    );
}
