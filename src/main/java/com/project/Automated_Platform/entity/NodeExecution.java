package com.project.Automated_Platform.entity;

import com.project.Automated_Platform.entity.status.NodeExecutionStatus;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import tools.jackson.databind.JsonNode;

import java.time.LocalDateTime;

@Data
@Entity
@NoArgsConstructor
@AllArgsConstructor
@Table(name="Node_Execution")
public class NodeExecution {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    @JoinColumn(name = "execution_id", nullable = false)
    private Execution execution;

    @ManyToOne
    @JoinColumn(name = "node_id", nullable = false)
    private Node node;

    @Enumerated(EnumType.STRING)
    private NodeExecutionStatus status;

    private LocalDateTime startedAt;

    private LocalDateTime finishedAt;

    private int attempts;

    @Column(columnDefinition = "TEXT")
    private String output;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "output_data", columnDefinition = "jsonb")
    private JsonNode outputData;


}
