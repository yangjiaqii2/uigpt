package top.uigpt.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * 异步执行基础设施；知识库导入使用 {@code knowledgeImportExecutor}，不引入 MQ。
 */
@Configuration
@EnableAsync
public class AsyncConfig {

    public static final String KNOWLEDGE_IMPORT_EXECUTOR = "knowledgeImportExecutor";

    @Bean(name = KNOWLEDGE_IMPORT_EXECUTOR)
    public Executor knowledgeImportExecutor() {
        ThreadPoolTaskExecutor ex = new ThreadPoolTaskExecutor();
        ex.setThreadNamePrefix("kb-import-");
        ex.setCorePoolSize(2);
        ex.setMaxPoolSize(4);
        ex.setQueueCapacity(100);
        ex.initialize();
        return ex;
    }
}
