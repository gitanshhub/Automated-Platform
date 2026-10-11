package com.project.Automated_Platform.service.execution;


import lombok.Getter;
import lombok.Setter;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Getter
@Setter
public class NodeExecutionContext {
    private Map<String, Object> data = new ConcurrentHashMap<>();

}
