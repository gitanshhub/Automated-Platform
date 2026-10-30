package com.project.Automated_Platform.controller;

import com.project.Automated_Platform.dto.WorkflowGraphRequest;
import com.project.Automated_Platform.dto.WorkflowGraphResponse;
import com.project.Automated_Platform.entity.Execution;
import com.project.Automated_Platform.entity.Node;
import com.project.Automated_Platform.entity.NodeConnection;
import com.project.Automated_Platform.entity.Workflow;
import com.project.Automated_Platform.service.WorkflowService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class WorkflowController {

    @Autowired
    private WorkflowService workflowService;

    @GetMapping("/workflows")
    public List<Workflow> getAllWorkflows(){
        return workflowService.getAllWorkflows();
    }

    @GetMapping("/workflow/{workflowId}")
    public Workflow getWorkflow(@PathVariable long workflowId) {
        return workflowService.getWorkflow(workflowId);
    }

    @GetMapping("/workflow/{workflowId}/nodes")
    public List<Node> getWorkflowNodes(@PathVariable long workflowId){
        return workflowService.getWorkflowNodes(workflowId);
    }

    @GetMapping("/workflow/{workflowId}/connections")
    public List<NodeConnection> getWorkflowConnections(@PathVariable long workflowId){
        return workflowService.getWorkflowConnections(workflowId);
    }

    @GetMapping("/execution/{executionId}")
    public Execution getExecution(@PathVariable Long executionId) {
        return workflowService.getExecution(executionId);
    }

    @PostMapping("/create-workflow")
    public Workflow createWorkflow(@RequestBody Workflow workflow) {
        return workflowService.createWorkflow(workflow);
    }

    @PostMapping("/workflow/{workflowId}/nodes")
    public Node createNode(
            @PathVariable long workflowId,
            @RequestBody Node node) {
        return workflowService.createNode(workflowId, node);
    }

    @PostMapping("/connection")
    public NodeConnection createNodeConnection(
            @RequestParam Long sourceNodeId,
            @RequestParam Long targetNodeId,
            @RequestParam(required = false) String branch)
    {
        return workflowService.createNodeConnection(sourceNodeId, targetNodeId, branch);
    }

    @PostMapping("/workflow/{workflowId}/execution")
    public Execution createExecution(@PathVariable long workflowId){

        return workflowService.createExecution(workflowId);
    }


    @PostMapping("/webhook/{workflowId}")
    public Execution receiveWebHook(@PathVariable long workflowId,
                                    @RequestBody(required = false) Map<String, Object> payload
    ) {
        return workflowService.createWebhookExecution(workflowId, payload);
    }

    @PutMapping("/workflow/{workflowId}")
    public Workflow updateWorkflow(@PathVariable long workflowId,@RequestBody Workflow updatedWorkflow){
        return workflowService.updateWorkflow(workflowId, updatedWorkflow);
    }

    @PutMapping("/nodes/{nodeId}")
    public Node updateNode(@PathVariable long nodeId, @RequestBody Node updatedNode){
        return workflowService.updateNode(nodeId, updatedNode);
    }

    @DeleteMapping("/nodes/{nodeId}")
    public ResponseEntity<?> deleteNode(@PathVariable long nodeId){
        workflowService.deleteNode(nodeId);

        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/workflow/{workflowId}")
    public ResponseEntity<?> deleteWorkflow(@PathVariable long workflowId){
        workflowService.deleteWorkflow(workflowId);

        return ResponseEntity.noContent().build();
    }

    @PutMapping("/workflow/{workflowId}/graph")
    public WorkflowGraphResponse saveWorkflowResponse(@PathVariable long workflowId, @RequestBody WorkflowGraphRequest request) {
        return workflowService.saveWorkflowGraph(workflowId, request);
    }
}
