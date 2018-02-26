package io.micrometer.spring.async;

import io.micrometer.core.Issue;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.test.context.junit4.SpringRunner;

import static java.util.Collections.emptyList;

@RunWith(SpringRunner.class)
@SpringBootTest(classes = ThreadPoolTaskExecutorMetricsIntegrationTest.App.class)
public class ThreadPoolTaskExecutorMetricsIntegrationTest {

    @Autowired
    MeterRegistry registry;

    @Issue("#459")
    @Test
    public void executorMetricsPhase() {
        registry.get("jvm.memory.max").gauge();
    }

    @SpringBootApplication(scanBasePackages = "ignore")
    static class App {
        @Bean
        public AsyncTaskExecutor executor(MeterRegistry registry) {
            return new TimedThreadPoolTaskExecutor(registry, "threads", emptyList());
        }
    }
}
