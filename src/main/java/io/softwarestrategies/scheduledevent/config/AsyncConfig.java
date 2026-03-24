package io.softwarestrategies.scheduledevent.config;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * Configuration for async processing and virtual threads.
 */
@Configuration
@EnableAsync
public class AsyncConfig {

	/**
	 * Task executor for async event processing. Uses virtual threads for optimal performance with I/O-bound tasks.
	 */
	@Bean(name = "eventProcessingExecutor")
	public ExecutorService eventProcessingExecutor() {
		return Executors.newVirtualThreadPerTaskExecutor();
	}

}