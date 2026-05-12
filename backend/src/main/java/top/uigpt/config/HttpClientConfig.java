package top.uigpt.config;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * JDK {@link HttpClient} 单例：复用连接池与调度线程，避免每请求 {@code newBuilder().build()} 带来的开销，
 * 便于多用户同时对话流式拉取、并行下载出图 URL。
 */
@Configuration
public class HttpClientConfig {

    @Bean(destroyMethod = "shutdown")
    public ExecutorService httpClientWorkerExecutor(
            @Value("${uigpt.http-client.worker-threads:64}") int threads) {
        int n = Math.max(8, threads);
        AtomicInteger seq = new AtomicInteger();
        return Executors.newFixedThreadPool(
                n,
                r -> {
                    Thread t = new Thread(r, "uigpt-http-" + seq.incrementAndGet());
                    t.setDaemon(true);
                    return t;
                });
    }

    /** 上游 LLM SSE（DashScope / API易 chat completions） */
    @Bean
    @Qualifier("upstreamChatHttpClient")
    public HttpClient upstreamChatHttpClient(
            @Qualifier("httpClientWorkerExecutor") ExecutorService httpClientWorkerExecutor) {
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .executor(httpClientWorkerExecutor)
                .build();
    }

    /** 下载 ERNIE / R2 等图片 URL */
    @Bean
    @Qualifier("downloadHttpClient")
    public HttpClient downloadHttpClient(
            @Qualifier("httpClientWorkerExecutor") ExecutorService httpClientWorkerExecutor) {
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .executor(httpClientWorkerExecutor)
                .build();
    }
}
