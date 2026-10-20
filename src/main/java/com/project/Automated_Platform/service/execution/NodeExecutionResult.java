package com.project.Automated_Platform.service.execution;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@NoArgsConstructor
@AllArgsConstructor
@Getter
@Setter
public class NodeExecutionResult {
    private String output;
    private int attempts;
    private String branch;
}
