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

        @Column(columnDefinition = "TEXT")
        private String configuration;
}
