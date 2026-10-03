package com.project.Automated_Platform.repository;

import com.project.Automated_Platform.entity.NodeConnection;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface NodeConnectionRepository extends JpaRepository<NodeConnection, Long> {

    List<NodeConnection> findByTargetNodeId(long targetNodeId);

    List<NodeConnection> findBySourceNodeId(long sourceNodeId);

    boolean existsBySourceNodeIdAndTargetNodeId(long sourceNodeId, long targetNodeId);

}
