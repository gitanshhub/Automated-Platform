package com.project.Automated_Platform.entity;


import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.context.annotation.Bean;

@Data
@Entity
@NoArgsConstructor
@AllArgsConstructor
@Table(name="node_Connections")
public class NodeConnection {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    @JoinColumn(name = "source_node_id", nullable = false)
    private Node sourceNode;

    @ManyToOne
    @JoinColumn(name = "target_node_id", nullable = false)
    private Node targetNode;
}
