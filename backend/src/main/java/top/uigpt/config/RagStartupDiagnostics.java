package top.uigpt.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import top.uigpt.service.RagService;

import java.net.URI;

/**
 * 启动时汇总 RAG / Qdrant / Embedding 配置，便于排查「未 source .env」「Qdrant 开了 Key 但未配」等问题；不打密钥明文。
 */
@Slf4j
@Component
@Order(510)
@RequiredArgsConstructor
public class RagStartupDiagnostics implements ApplicationRunner {

    private final AppProperties appProperties;
    private final RagService ragService;

    @Override
    public void run(ApplicationArguments args) {
        AppProperties.Rag rag = appProperties.getRag();
        boolean enabled = rag.isEnabled();
        String qUrl = rag.getQdrantUrl() == null ? "" : rag.getQdrantUrl().strip();
        String qKey = rag.getQdrantApiKey() == null ? "" : rag.getQdrantApiKey().strip();
        String embUrl = rag.getEmbeddingBaseUrl() == null ? "" : rag.getEmbeddingBaseUrl().strip();
        String embDirect = rag.getEmbeddingApiKey() == null ? "" : rag.getEmbeddingApiKey().strip();
        String apiYiKey =
                appProperties.getApiYiImage() == null || appProperties.getApiYiImage().getApiKey() == null
                        ? ""
                        : appProperties.getApiYiImage().getApiKey().strip();
        String embEffective = !embDirect.isBlank() ? embDirect : apiYiKey;
        String col = rag.getCollection() == null ? "" : rag.getCollection().strip();

        log.info(
                "RAG：总开关={}；Qdrant URL={}；Embedding URL={}；collection={}",
                enabled,
                qUrl.isEmpty() ? "（未配置，用 yml 默认）" : qUrl,
                embUrl.isEmpty() ? "（未配置，用 yml 默认）" : embUrl,
                col.isEmpty() ? "（未配置）" : col);
        log.info(
                "RAG：密钥 — Qdrant api-key={}；Embedding 专用={}；Embedding 实际 Bearer={}（专用为空时与 RagService 相同，回退 APIYi）",
                secretHint(qKey),
                secretHint(embDirect),
                secretHint(embEffective));

        warnIfQdrantHostMisusedForLocalJvm(qUrl);

        if (enabled) {
            if (qKey.isBlank()) {
                log.info(
                        "RAG：Qdrant api-key 当前为空。若 Qdrant 启用了 QDRANT__SERVICE__API_KEY，请设置 UIGPT_RAG_QDRANT_API_KEY，否则写入/检索会 401。");
            }
            if (embEffective.isBlank()) {
                log.warn(
                        "RAG：Embedding Bearer 为空（UIGPT_RAG_EMBEDDING_API_KEY 与 APIYI_API_KEY 均未生效）。知识库向量与检索将失败。");
            } else {
                ragService.ensureDefaultQdrantCollection();
            }
        }
    }

    /** 未配置 / 已配置(***后缀)，避免日志泄露完整密钥 */
    private static String secretHint(String s) {
        if (s == null || s.isBlank()) {
            return "未配置";
        }
        String t = s.strip();
        if (t.length() <= 4) {
            return "已配置(长度" + t.length() + ")";
        }
        return "已配置(***" + t.substring(t.length() - 4) + ")";
    }

    /**
     * {@code qdrant} 仅为 Docker Compose 服务名；在宿主机/WSL 直接跑 Spring 时应使用 {@code http://127.0.0.1:6333}。
     */
    private static void warnIfQdrantHostMisusedForLocalJvm(String qUrl) {
        if (qUrl == null || qUrl.isBlank()) {
            return;
        }
        try {
            URI u = URI.create(qUrl);
            String host = u.getHost();
            if (host != null && "qdrant".equalsIgnoreCase(host.strip())) {
                log.warn(
                        "RAG：当前 UIGPT_RAG_QDRANT_URL 使用主机名「qdrant」。仅当后端进程与 Qdrant 在同一 Docker 网络内才可达；"
                                + "在本地 IDE / mvn 运行时请改为 http://127.0.0.1:6333（或 localhost），并避免 .env 里对同一变量重复赋值导致后者覆盖前者。");
            }
        } catch (Exception ignored) {
            // ignore
        }
    }
}
