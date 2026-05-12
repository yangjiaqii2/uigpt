package top.uigpt.config;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.net.http.HttpClient;
import java.time.Duration;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        // 开发环境可能通过局域网 IP/主机名访问 Vite；代理会把 Origin 带到后端，固定 localhost 会导致 CORS 403。
        // 认证走 Authorization Bearer，无需携带 Cookie，故 allowCredentials=false 可与通配源配合。
        registry.addMapping("/api/**")
                .allowedOriginPatterns("*")
                .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(false);
    }

    @Bean
    public RestClient restClient(RestClient.Builder builder) {
        return builder.build();
    }

    /**
     * APIYi 文生图 / 编辑可能耗时较长；单独拉长读超时（默认 300s）。
     * 与 {@link HttpClientConfig#upstreamChatHttpClient} 共用 {@link HttpClient}，复用连接池。
     */
    @Bean
    @Qualifier("apiYiRestClient")
    public RestClient apiYiRestClient(
            @Qualifier("upstreamChatHttpClient") HttpClient upstreamChatHttpClient) {
        JdkClientHttpRequestFactory rf = new JdkClientHttpRequestFactory(upstreamChatHttpClient);
        rf.setReadTimeout(Duration.ofSeconds(300));
        return RestClient.builder().requestFactory(rf).build();
    }

    /**
     * 识图预分析单独较短读超时，避免与文生图共用 300s 导致主对话首包被长时间阻塞。
     */
    @Bean
    @Qualifier("apiYiVisionRestClient")
    public RestClient apiYiVisionRestClient(
            @Qualifier("upstreamChatHttpClient") HttpClient upstreamChatHttpClient,
            @Value("${uigpt.api-yi-image.vision-read-timeout-seconds:18}") int visionReadTimeoutSeconds) {
        JdkClientHttpRequestFactory rf = new JdkClientHttpRequestFactory(upstreamChatHttpClient);
        int sec = Math.max(5, visionReadTimeoutSeconds);
        rf.setReadTimeout(Duration.ofSeconds(sec));
        return RestClient.builder().requestFactory(rf).build();
    }

    @Bean
    @Qualifier("ragRestClient")
    public RestClient ragRestClient(
            @Qualifier("upstreamChatHttpClient") HttpClient upstreamChatHttpClient,
            @Value("${uigpt.rag.read-timeout-seconds:45}") int ragReadTimeoutSeconds) {
        JdkClientHttpRequestFactory rf = new JdkClientHttpRequestFactory(upstreamChatHttpClient);
        int sec = Math.max(5, ragReadTimeoutSeconds);
        rf.setReadTimeout(Duration.ofSeconds(sec));
        return RestClient.builder().requestFactory(rf).build();
    }
}
