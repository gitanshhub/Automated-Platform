package com.project.Automated_Platform.repository;

import com.project.Automated_Platform.entity.Node;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface NodeRepository extends JpaRepository<Node, Long> {

    long countByWorkflowId(Long workflowId);

    List<Node> findByWorkflowId(Long workflowId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT n FROM Node n WHERE n.id = :nodeId")
    Optional<Node> findByIdForUpdate(@Param("nodeId") Long nodeId);
}
