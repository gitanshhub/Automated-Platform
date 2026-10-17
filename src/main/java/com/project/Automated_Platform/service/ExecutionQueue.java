package com.project.Automated_Platform.service;


import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;

@Component
public class ExecutionQueue {

    private final BlockingQueue<Long> queue = new LinkedBlockingQueue<>(500);

    private final Set<Long> queuedExecutionId = ConcurrentHashMap.newKeySet();

    public void submit(Long executionId) {

        if(!queuedExecutionId.add(executionId)) {
            return;
        }

        if(!queue.offer(executionId)) {
            queuedExecutionId.remove(executionId);

            throw new IllegalStateException("Execution queue is full. try again later");
        }
    }

    public Long take() throws InterruptedException {
        Long executionId = queue.take();

        queuedExecutionId.remove(executionId);

        return executionId;
    }

}
