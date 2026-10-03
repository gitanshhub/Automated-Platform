package com.project.Automated_Platform.controller;

import com.project.Automated_Platform.entity.Execution;
import com.project.Automated_Platform.entity.Node;
import com.project.Automated_Platform.entity.NodeConnection;
import com.project.Automated_Platform.entity.Workflow;
import com.project.Automated_Platform.service.WorkflowService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class WorkflowController {

    @Autowired
    private WorkflowService workflowService;


    @PostMapping("/create-workflow")
    public Workflow createWorkflow(@RequestBody Workflow workflow) {
        return workflowService.createWorkflow(workflow);

    }

    @PostMapping("/workflow/{workflowId}/nodes")
    public Node createNode(
            @PathVariable long workflowId,
            @RequestBody Node node)
    {

        return workflowService.createNode(workflowId, node);

    }

    @PostMapping("/connection")
    public NodeConnection createConnection(
            @RequestParam Long sourceNodeId,
            @RequestParam Long targetNodeId)
    {

        return workflowService.createNodeConnection(sourceNodeId, targetNodeId);
    }

    @PostMapping("/workflow/{workflowId}/execution")
    public Execution createExecution(@PathVariable long workflowId){

        return workflowService.createExecution(workflowId);
    }


}
