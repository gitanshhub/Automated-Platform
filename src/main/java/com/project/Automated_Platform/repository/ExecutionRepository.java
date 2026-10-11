package com.project.Automated_Platform.repository;

import com.project.Automated_Platform.entity.Execution;
import com.project.Automated_Platform.entity.ExecutionStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ExecutionRepository extends JpaRepository<Execution, Long> {


    List<Execution> findByStatus(ExecutionStatus status);
}
