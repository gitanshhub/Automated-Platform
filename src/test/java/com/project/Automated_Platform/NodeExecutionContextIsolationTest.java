package com.project.Automated_Platform;


import com.project.Automated_Platform.service.execution.NodeExecutionContext;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;

public class NodeExecutionContextIsolationTest {
    @Test
    void separateContextsMustNotShareRuntimeData() {

        // Two simultaneous workflow executions
        NodeExecutionContext contextA = new NodeExecutionContext();
        NodeExecutionContext contextB = new NodeExecutionContext();

        // Each execution receives its own input
        contextA.getData().put("message", "RUN-A");
        contextB.getData().put("message", "RUN-B");

        // Verify each execution sees its own data
        assertEquals("RUN-A", contextA.getData().get("message"));
        assertEquals("RUN-B", contextB.getData().get("message"));

        // Modify execution A
        contextA.getData().put("message", "MODIFIED-A");

        // Execution B must remain unchanged
        assertEquals("MODIFIED-A", contextA.getData().get("message"));
        assertEquals("RUN-B", contextB.getData().get("message"));

        // Verify the underlying maps are distinct objects
        assertNotSame(contextA.getData(), contextB.getData());
    }


}
