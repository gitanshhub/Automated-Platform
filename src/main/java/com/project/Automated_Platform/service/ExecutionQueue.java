package com.project.Automated_Platform.service;


import org.springframework.stereotype.Component;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

@Component
public class ExecutionQueue {

    private final BlockingQueue<Long> queue = new LinkedBlockingQueue<>(500);

    public void submit(Long executionId) {

        if(!queue.offer(executionId)){
            throw new IllegalStateException("Execution queue is full. try again later");
        }
    }

    public Long take() throws InterruptedException {
        return queue.take();
    }

}
