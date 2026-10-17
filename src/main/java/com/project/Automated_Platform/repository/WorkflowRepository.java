package com.project.Automated_Platform.repository;

import com.project.Automated_Platform.entity.Workflow;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface WorkflowRepository extends JpaRepository<Workflow, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT w from Workflow w WHERE w.id = :workflowId")
    Optional<Workflow> findByIdForUpdate(
            @Param("workflowId") Long workflowId
    );
}
