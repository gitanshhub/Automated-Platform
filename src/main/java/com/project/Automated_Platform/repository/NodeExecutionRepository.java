package com.project.Automated_Platform.repository;

import com.project.Automated_Platform.entity.NodeExecution;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface NodeExecutionRepository extends JpaRepository<NodeExecution, Long> {
}
