package com.project.Automated_Platform.service;

import com.project.Automated_Platform.entity.Node;
import org.springframework.stereotype.Service;

@Service
public class NodeExecutor {

    public String execute(Node node) {
        switch (node.getType()) {

            case MANUAL_TRIGGER:
                return "manual trigger executed";
            case WEBHOOK:
                return "webhook executed";

            case HTTP_REQUEST: return "http executed";

            case DELAY: return "delayed";

            case IF: return "IF done";

            case TRANSFORM: return "transform";

            default: throw new RuntimeException("unsupported node type");
        }

    }

}
