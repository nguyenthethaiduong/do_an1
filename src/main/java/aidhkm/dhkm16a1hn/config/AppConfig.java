package aidhkm.dhkm16a1hn.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.concurrent.Executor;

@Configuration
@EnableAsync
public class AppConfig {
    
    @Bean
    public ObjectMapper objectMapper() {
        return new ObjectMapper();
    }
    
    @Bean(name = "processingTaskExecutor")
    public Executor processingTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(5);
        executor.setQueueCapacity(10);
        executor.setThreadNamePrefix("AsyncTask-");
        executor.initialize();
        return executor;
    }
} 