package com.project.Automated_Platform.exception;

public class ExecutionLeaseLostException extends RuntimeException {
    public ExecutionLeaseLostException(String message) {
        super(message);
    }


    public ExecutionLeaseLostException(String message, Throwable cause) {
        super(message, cause);
    }

}
