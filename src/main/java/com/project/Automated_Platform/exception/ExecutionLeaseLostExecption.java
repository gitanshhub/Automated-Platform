package com.project.Automated_Platform.exception;

public class ExecutionLeaseLostExecption extends RuntimeException {
    public ExecutionLeaseLostExecption (String message) {
        super(message);
    }


    public ExecutionLeaseLostExecption (String message, Throwable cause) {
        super(message, cause);
    }

}
