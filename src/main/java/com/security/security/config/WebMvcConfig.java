package com.security.security.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.core.task.TaskDecorator;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.servlet.config.annotation.AsyncSupportConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Configures Spring MVC async support to propagate SecurityContext to async threads.
 *
 * Root problem: ChatController returns Flux<String> (SSE).  When Tomcat
 * dispatches the SSE write to a new async thread, Spring Security's ThreadLocal-
 * based SecurityContextHolder loses the auth set by JwtAuthorizationFilter on
 * the original thread → AnonymousAuthenticationFilter kicks in → Access Denied.
 *
 * Fix: use a TaskDecorator that captures the SecurityContext from the caller
 * thread and restores it on the async worker thread before execution.
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    /**
     * A TaskDecorator that copies the current SecurityContext into each
     * async task so that Spring Security sees the authenticated principal
     * on the new thread.
     */
    private static final TaskDecorator SECURITY_CONTEXT_DECORATOR = runnable -> {
        SecurityContext context = SecurityContextHolder.getContext();
        return () -> {
            try {
                SecurityContextHolder.setContext(context);
                runnable.run();
            } finally {
                SecurityContextHolder.clearContext();
            }
        };
    };

    @Bean(name = "mvcAsyncTaskExecutor")
    public AsyncTaskExecutor mvcAsyncTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(5);
        executor.setMaxPoolSize(10);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("mvc-async-");
        executor.setTaskDecorator(SECURITY_CONTEXT_DECORATOR);
        executor.initialize();
        return executor;
    }

    @Override
    public void configureAsyncSupport(AsyncSupportConfigurer configurer) {
        configurer.setTaskExecutor(mvcAsyncTaskExecutor());
        // 120s timeout for long SSE streams
        configurer.setDefaultTimeout(120_000L);
    }
}
