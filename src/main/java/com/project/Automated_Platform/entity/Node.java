package com.project.Automated_Platform.entity;

import com.project.Automated_Platform.entity.status.NodeType;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import tools.jackson.databind.JsonNode;

@Data
@Entity
@NoArgsConstructor
@AllArgsConstructor
@Table(name="nodes")
public class Node {

        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        private Long id;

        private String name;

        private String description;

        @Enumerated(EnumType.STRING)
        private NodeType type;

        @ManyToOne
        @JoinColumn(name = "workflow_id")
        private Workflow workflow;

        @JdbcTypeCode(SqlTypes.JSON)
        @Column(columnDefinition = "jsonb")
        private JsonNode configuration;
}
