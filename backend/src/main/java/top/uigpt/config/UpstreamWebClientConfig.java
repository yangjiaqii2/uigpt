package top.uigpt.config;

import io.netty.channel.ChannelOption;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;

import java.time.Duration;

/**
 * 上游 OpenAI 兼容 {@code /v1/chat/completions} 流式专用 {@link WebClient}：复用 TCP 连接池。
 */
@Configuration
public class UpstreamWebClientConfig {

    public static final String UPSTREAM_CHAT_WEB_CLIENT = "upstreamChatWebClient";

    public static final String UPSTREAM_CHAT_REACTOR_HTTP_CLIENT = "upstreamChatReactorHttpClient";

    @Bean(name = UPSTREAM_CHAT_REACTOR_HTTP_CLIENT)
    public HttpClient upstreamChatReactorHttpClient(
            @Value("${uigpt.upstream-chat.max-connections:200}") int maxConnections,
            @Value("${uigpt.upstream-chat.pending-acquire-max:2000}") int pendingAcquireMax,
            @Value("${uigpt.upstream-chat.max-idle-seconds:60}") int maxIdleSeconds,
            @Value("${uigpt.upstream-chat.response-timeout-minutes:10}") int responseTimeoutMinutes) {
        ConnectionProvider provider =
                ConnectionProvider.builder("uigpt-upstream-chat")
                        .maxConnections(Math.max(32, maxConnections))
                        .pendingAcquireMaxCount(Math.max(64, pendingAcquireMax))
                        .maxIdleTime(Duration.ofSeconds(Math.max(10, maxIdleSeconds)))
                        .build();
        return HttpClient.create(provider)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 30_000)
                .responseTimeout(Duration.ofMinutes(Math.max(1, responseTimeoutMinutes)))
                .keepAlive(true);
    }

    @Bean(name = UPSTREAM_CHAT_WEB_CLIENT)
    public WebClient upstreamChatWebClient(
            @Qualifier(UPSTREAM_CHAT_REACTOR_HTTP_CLIENT) HttpClient reactorHttpClient,
            @Value("${uigpt.upstream-chat.codecs-max-in-memory-kb:256}") int codecsMaxKb) {
        int maxBytes = Math.max(16_384, codecsMaxKb * 1024);
        return WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(reactorHttpClient))
                .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(maxBytes))
                .build();
    }
}
