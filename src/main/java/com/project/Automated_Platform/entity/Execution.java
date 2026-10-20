package com.project.Automated_Platform.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.LocalDateTime;

@Data
@Entity
@NoArgsConstructor
@AllArgsConstructor
@Table(name="execution")
public class Execution {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    @JoinColumn(name="workflow_id", nullable = false)
    private Workflow workflow;

    @Enumerated(EnumType.STRING)
    private ExecutionStatus status;

    private LocalDateTime startedAt;

    private LocalDateTime finishedAt;

    @Column(columnDefinition = "TEXT")
    private String result;

    @Column(name = "worker_id")
    private String workerId;

    @Column(name="lease_expires_at")
    private LocalDateTime leaseExpiresAt;

    @Column(name = "trigger_type", nullable = false)
    private String triggerType;

    @Column(name = "trigger_data", columnDefinition = "TEXT")
    private String triggerData;
}
