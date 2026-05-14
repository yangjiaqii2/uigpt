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

    /** /api/chat/stream：上游 SSE 订阅与 ResponseBodyEmitter 写回，避免占用 Tomcat 工作线程 */
    public static final String SSE_CHAT_FORWARD_EXECUTOR = "sseChatForwardExecutor";

    /** 图片工作台 Nano Banana 多路并行 generateContent，避免占满公共 ForkJoinPool */
    public static final String IMAGE_STUDIO_GENERATION_EXECUTOR = "imageStudioGenerationExecutor";

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

    @Bean(name = SSE_CHAT_FORWARD_EXECUTOR)
    public Executor sseChatForwardExecutor() {
        ThreadPoolTaskExecutor ex = new ThreadPoolTaskExecutor();
        ex.setThreadNamePrefix("sse-chat-");
        ex.setCorePoolSize(8);
        ex.setMaxPoolSize(64);
        ex.setQueueCapacity(500);
        ex.initialize();
        return ex;
    }

    @Bean(name = IMAGE_STUDIO_GENERATION_EXECUTOR)
    public Executor imageStudioGenerationExecutor() {
        ThreadPoolTaskExecutor ex = new ThreadPoolTaskExecutor();
        ex.setThreadNamePrefix("img-studio-");
        ex.setCorePoolSize(4);
        ex.setMaxPoolSize(16);
        ex.setQueueCapacity(200);
        ex.initialize();
        return ex;
    }
}
