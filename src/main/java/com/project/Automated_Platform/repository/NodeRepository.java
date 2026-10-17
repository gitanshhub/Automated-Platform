package com.project.Automated_Platform.repository;

import com.project.Automated_Platform.entity.Node;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface NodeRepository extends JpaRepository<Node, Long> {

    long countByWorkflowId(Long workflowId);
}
