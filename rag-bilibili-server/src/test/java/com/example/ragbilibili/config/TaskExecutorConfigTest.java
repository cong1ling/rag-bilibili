package com.example.ragbilibili.config;

import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.concurrent.ThreadPoolExecutor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class TaskExecutorConfigTest {

    private final TaskExecutorConfig config = new TaskExecutorConfig();

    @Test
    void shouldCreateThreadPoolTaskExecutorWithExpectedSettings() {
        ThreadPoolTaskExecutor executor = assertInstanceOf(ThreadPoolTaskExecutor.class, config.taskExecutor());

        assertEquals(10, executor.getCorePoolSize());
        assertEquals(20, executor.getMaxPoolSize());
        assertEquals(100, executor.getQueueCapacity());
        assertEquals("chat-async-", executor.getThreadNamePrefix());
        assertEquals(true, ReflectionTestUtils.getField(executor, "waitForTasksToCompleteOnShutdown"));
        assertInstanceOf(ThreadPoolExecutor.CallerRunsPolicy.class, executor.getThreadPoolExecutor().getRejectedExecutionHandler());
    }
}
