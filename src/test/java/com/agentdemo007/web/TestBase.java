package com.agentdemo007.web;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.TestInstance;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.context.event.ApplicationReadyEvent;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 测试基类：固定测试端口 + 等待应用就绪事件，确保嵌入式服务器真正可连接。
 * 所有 Web 集成测试均继承此类。
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT,
        properties = {"server.port=8111", "app.max-body-size=8"}
)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class TestBase {

    static final int PORT = 8111;

    static final CountDownLatch readyLatch = new CountDownLatch(1);

    static String base() {
        return "http://localhost:" + PORT;
    }

    @Configuration
    static class ReadyConfig {
        @Bean
        ApplicationListener<ApplicationReadyEvent> serverReadyListener() {
            return e -> readyLatch.countDown();
        }
    }

    @BeforeAll
    void waitForServer() throws InterruptedException {
        assertTrue(readyLatch.await(45, TimeUnit.SECONDS),
                "嵌入式服务器 45 秒内未就绪");
    }
}
