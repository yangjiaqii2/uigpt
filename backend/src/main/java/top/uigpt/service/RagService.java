package top.uigpt.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriUtils;
import top.uigpt.config.AppProperties;
import top.uigpt.dto.ChatMessageDto;
import top.uigpt.dto.ChatRequest;

import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 使用 Qdrant 向量检索 + OpenAI 兼容 {@code /v1/embeddings} 为对话注入知识库上下文（首条 system，不落库）。
 */
@Slf4j
@Service
public class RagService {

    private static final int QDRANT_DELETE_POINTS_CHUNK = 100;
    private static final String RAG_SYSTEM_HEADER =
            "【知识库检索】以下片段来自向量库检索，仅供参考。若与用户问题无关、相互矛盾或不足以回答，请如实说明，勿编造。\n\n";

    private final AppProperties appProperties;
    private final ObjectMapper objectMapper;
    private final RestClient ragRestClient;

    public RagService(
            AppProperties appProperties,
            ObjectMapper objectMapper,
            @Qualifier("ragRestClient") RestClient ragRestClient) {
        this.appProperties = appProperties;
        this.objectMapper = objectMapper;
        this.ragRestClient = ragRestClient;
    }

    /**
     * 在发往模型的消息列表最前追加一条 system（在会话记忆 system 之前），失败时返回原请求不打断对话。
     */
    public ChatRequest augment(ChatRequest request, boolean chatPassthrough) {
        if (request == null || request.getMessages() == null) {
            return request;
        }
        AppProperties.Rag cfg = appProperties.getRag();
        if (!cfg.isEnabled() || !isRagVectorPipelineReady(cfg)) {
            return request;
        }
        if (chatPassthrough && !Boolean.TRUE.equals(request.getUseRag())) {
            return request;
        }
        String collection = resolveCollectionName(request, cfg);
        if (collection == null) {
            return request;
        }
        String query = lastUserPlainText(request.getMessages());
        if (query.isBlank()) {
            return request;
        }
        int maxQ = Math.max(256, cfg.getMaxQueryChars());
        if (query.length() > maxQ) {
            query = query.substring(0, maxQ);
        }
        try {
            List<Float> vector = embedQuery(cfg, query);
            if (vector.isEmpty()) {
                return request;
            }
            List<ScoredChunk> chunks = search(cfg, collection, vector);
            if (chunks.isEmpty()) {
                return request;
            }
            String block = buildContextBlock(chunks);
            return prependSystem(request, RAG_SYSTEM_HEADER + block);
        } catch (Exception e) {
            log.warn("RAG 检索失败，跳过注入: {}", e.getMessage());
            return request;
        }
    }

    /**
     * 为文生图 / 图编等接口在发往绘图模型的 prompt 前注入知识库片段（与对话 RAG 同源：embedding + Qdrant）。
     *
     * <p>{@code useRag} 为 {@code false} 时直接返回 {@code mergedPromptForApi}；为 {@code null} 或 {@code true} 且全局
     * RAG 已启用并配全时尝试检索，失败或无命中则原样返回。
     *
     * @param mergedPromptForApi 即将送往绘图 API 的完整 prompt（可已含会话摘要等）
     * @param embeddingQueryText 用于向量检索的短文本，建议用用户本轮指令（如 {@code prompt} / {@code userMessage}）
     * @param useRag 为 {@code false} 时跳过检索
     * @param ragCollectionOverride 可选 Qdrant 集合名；非法或空则用配置默认
     */
    public String augmentPromptForImage(
            String mergedPromptForApi,
            String embeddingQueryText,
            Boolean useRag,
            String ragCollectionOverride) {
        String merged = mergedPromptForApi == null ? "" : mergedPromptForApi;
        if (Boolean.FALSE.equals(useRag)) {
            return merged;
        }
        AppProperties.Rag cfg = appProperties.getRag();
        if (!cfg.isEnabled() || !isRagVectorPipelineReady(cfg)) {
            return merged;
        }
        String collection = resolveCollectionForImage(ragCollectionOverride, cfg);
        if (collection == null) {
            return merged;
        }
        String query = embeddingQueryText == null ? "" : embeddingQueryText.strip();
        if (query.isBlank()) {
            return merged;
        }
        int maxQ = Math.max(256, cfg.getMaxQueryChars());
        if (query.length() > maxQ) {
            query = query.substring(0, maxQ);
        }
        try {
            List<Float> vector = embedQuery(cfg, query);
            if (vector.isEmpty()) {
                return merged;
            }
            List<ScoredChunk> chunks = search(cfg, collection, vector);
            if (chunks.isEmpty()) {
                return merged;
            }
            String block = buildContextBlock(chunks);
            return RAG_SYSTEM_HEADER + block + "\n\n" + merged;
        } catch (Exception e) {
            log.warn("作图 RAG 检索失败，跳过注入: {}", e.getMessage());
            return merged;
        }
    }

    /**
     * 仅执行作图侧向量检索并返回可拼入 Prompt 的知识块（含固定头）；未启用 RAG、无命中或失败时返回空字符串。
     *
     * <p>用于图片工作台三阶段流水线：第二阶段在「意图 JSON」得到 {@code rag_embedding_query} 后再检索。
     */
    public String retrieveKnowledgeBlockForImage(
            String embeddingQueryText, Boolean useRag, String ragCollectionOverride) {
        if (Boolean.FALSE.equals(useRag)) {
            return "";
        }
        AppProperties.Rag cfg = appProperties.getRag();
        if (!cfg.isEnabled() || !isRagVectorPipelineReady(cfg)) {
            return "";
        }
        String collection = resolveCollectionForImage(ragCollectionOverride, cfg);
        if (collection == null) {
            return "";
        }
        String query = embeddingQueryText == null ? "" : embeddingQueryText.strip();
        if (query.isBlank()) {
            return "";
        }
        int maxQ = Math.max(256, cfg.getMaxQueryChars());
        if (query.length() > maxQ) {
            query = query.substring(0, maxQ);
        }
        try {
            List<Float> vector = embedQuery(cfg, query);
            if (vector.isEmpty()) {
                return "";
            }
            List<ScoredChunk> chunks = search(cfg, collection, vector);
            if (chunks.isEmpty()) {
                return "";
            }
            String block = buildContextBlock(chunks);
            return RAG_SYSTEM_HEADER + block;
        } catch (Exception e) {
            log.warn("作图 RAG 仅检索失败: {}", e.getMessage());
            return "";
        }
    }

    private String resolveCollectionForImage(String ragCollectionOverride, AppProperties.Rag cfg) {
        if (ragCollectionOverride != null && !ragCollectionOverride.isBlank()) {
            String s = ragCollectionOverride.strip();
            if (validCollectionName(s)) {
                return s;
            }
            log.warn("忽略非法 ragCollection: {}", s);
        }
        String def = cfg.getCollection() == null ? "" : cfg.getCollection().strip();
        return validCollectionName(def) ? def : null;
    }

    /** 使用固定点 id 写入 Qdrant：payload 含可选 title、text、created_at（ISO-8601）。 */
    public void upsertKnowledgePoint(String pointId, String title, String text) {
        AppProperties.Rag cfg = appProperties.getRag();
        if (!cfg.isEnabled() || !isRagVectorPipelineReady(cfg)) {
            throw new IllegalStateException(ragSetupFailureMessage(cfg));
        }
        String collection = cfg.getCollection() == null ? "" : cfg.getCollection().strip();
        if (!validCollectionName(collection)) {
            throw new IllegalStateException("默认集合名无效，请检查 uigpt.rag.collection");
        }
        if (pointId == null || pointId.isBlank()) {
            throw new IllegalArgumentException("pointId 不能为空");
        }
        String pid = pointId.strip();
        String body = text == null ? "" : text.strip();
        if (body.isEmpty()) {
            throw new IllegalArgumentException("正文不能为空");
        }
        if (body.length() > 32000) {
            body = body.substring(0, 32000);
        }
        List<Float> vector = embedQuery(cfg, body);
        if (vector.isEmpty()) {
            throw new IllegalStateException("embedding 失败，无法写入向量库");
        }
        ObjectNode payload = objectMapper.createObjectNode();
        if (title != null && !title.isBlank()) {
            payload.put("title", title.strip());
        }
        payload.put("text", body);
        payload.put("created_at", Instant.now().toString());
        putPoint(cfg, collection, pid, vector, payload);
    }

    /** 按点 id 从 Qdrant 删除（幂等：不存在时网关可能仍返回 ok）。大批量时分批请求，避免单次 body 过大或网关限制。 */
    public void deleteKnowledgePoints(List<String> pointIds) {
        if (pointIds == null || pointIds.isEmpty()) {
            return;
        }
        AppProperties.Rag cfg = appProperties.getRag();
        if (!cfg.isEnabled() || !isRagVectorPipelineReady(cfg)) {
            throw new IllegalStateException(ragSetupFailureMessage(cfg));
        }
        String collection = cfg.getCollection() == null ? "" : cfg.getCollection().strip();
        if (!validCollectionName(collection)) {
            throw new IllegalStateException("默认集合名无效，请检查 uigpt.rag.collection");
        }
        List<String> ids = new ArrayList<>();
        for (String id : pointIds) {
            if (id != null && !id.isBlank()) {
                ids.add(id.strip());
            }
        }
        if (ids.isEmpty()) {
            return;
        }
        String qRoot = normalizeRoot(cfg.getQdrantUrl());
        assertQdrantRootLooksReasonable(qRoot);
        String enc = UriUtils.encodePathSegment(collection, StandardCharsets.UTF_8);
        String url = qRoot + "/collections/" + enc + "/points/delete?wait=true";
        for (int i = 0; i < ids.size(); i += QDRANT_DELETE_POINTS_CHUNK) {
            int end = Math.min(i + QDRANT_DELETE_POINTS_CHUNK, ids.size());
            deleteQdrantPointsChunk(cfg, url, ids.subList(i, end));
        }
    }

    private void deleteQdrantPointsChunk(AppProperties.Rag cfg, String url, List<String> chunk) {
        ObjectNode body = objectMapper.createObjectNode();
        ArrayNode arr = body.putArray("points");
        for (String id : chunk) {
            arr.add(id);
        }
        String json = body.toString();
        try {
            withQdrantAuthForBody(ragRestClient.post().uri(url), cfg)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(json)
                    .retrieve()
                    .toBodilessEntity();
        } catch (HttpClientErrorException.Unauthorized e) {
            throw mapQdrantUnauthorized(e);
        } catch (HttpClientErrorException e) {
            String rb = e.getResponseBodyAsString();
            if (rb != null && rb.length() > 800) {
                rb = rb.substring(0, 800) + "…";
            }
            throw new IllegalStateException(
                    "向量库删除失败（HTTP "
                            + e.getStatusCode().value()
                            + "）："
                            + (rb == null || rb.isBlank() ? e.getMessage() : rb));
        } catch (RestClientException e) {
            throw new IllegalStateException("向量库删除请求失败: " + e.getMessage(), e);
        }
    }

    /**
     * 若 RAG 已启用且配置齐全，则在 Qdrant 尚无默认集合时自动创建（Cosine，维度见 {@link AppProperties.Rag#getEmbeddingVectorSize()}）。
     * 由 {@link top.uigpt.config.RagStartupDiagnostics} 在启动时调用。
     */
    public void ensureDefaultQdrantCollection() {
        AppProperties.Rag cfg = appProperties.getRag();
        if (!cfg.isEnabled() || !isRagVectorPipelineReady(cfg)) {
            return;
        }
        String collection = cfg.getCollection() == null ? "" : cfg.getCollection().strip();
        if (!validCollectionName(collection)) {
            log.warn("跳过 Qdrant 自动建库：集合名非法 {}", collection);
            return;
        }
        String qRoot = normalizeRoot(cfg.getQdrantUrl());
        assertQdrantRootLooksReasonable(qRoot);
        String enc = UriUtils.encodePathSegment(collection, StandardCharsets.UTF_8);
        String url = qRoot + "/collections/" + enc;
        try {
            withQdrantAuthForHeaders(ragRestClient.get().uri(url), cfg).retrieve().toBodilessEntity();
            log.info("Qdrant 集合已存在: {}", collection);
        } catch (HttpClientErrorException.NotFound e) {
            createQdrantCollectionIfMissing(cfg, qRoot, enc, collection);
        } catch (HttpClientErrorException.Unauthorized e) {
            log.warn("无法检查 Qdrant 集合（401），跳过自动建库: {}", e.getMessage());
        } catch (Exception e) {
            log.warn("检查 Qdrant 集合是否存在时失败，跳过自动建库: {}", e.getMessage());
        }
    }

    private void createQdrantCollectionIfMissing(
            AppProperties.Rag cfg, String qRoot, String encPathSegment, String collection) {
        int dim = Math.max(1, cfg.getEmbeddingVectorSize());
        String url = qRoot + "/collections/" + encPathSegment;
        ObjectNode body = objectMapper.createObjectNode();
        ObjectNode vectors = body.putObject("vectors");
        vectors.put("size", dim);
        vectors.put("distance", "Cosine");
        String json = body.toString();
        try {
            withQdrantAuthForBody(ragRestClient.put().uri(url), cfg)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(json)
                    .retrieve()
                    .toBodilessEntity();
            log.info("Qdrant 已自动创建集合 {}（向量维度 {}，距离 Cosine）", collection, dim);
        } catch (HttpClientErrorException.Conflict e) {
            log.info("Qdrant 集合 {} 已存在（并发创建）", collection);
        } catch (HttpClientErrorException.Unauthorized e) {
            log.warn("自动创建 Qdrant 集合失败（401）: {}", e.getMessage());
        } catch (Exception e) {
            log.error(
                    "自动创建 Qdrant 集合失败: {}（维度 {}）。请核对 UIGPT_RAG_EMBEDDING_VECTOR_SIZE 与模型是否一致，或手动在 Qdrant 建同名集合。",
                    collection,
                    dim,
                    e);
        }
    }

    /**
     * RAG 向量写入/检索是否具备最小配置：Qdrant、Embedding URL、有效 Bearer（含回退到 APIYi 密钥）、集合名。
     */
    private boolean isRagVectorPipelineReady(AppProperties.Rag cfg) {
        String q = cfg.getQdrantUrl() == null ? "" : cfg.getQdrantUrl().strip();
        String embUrl = cfg.getEmbeddingBaseUrl() == null ? "" : cfg.getEmbeddingBaseUrl().strip();
        String embKey = resolveEmbeddingBearer(cfg);
        String col = cfg.getCollection() == null ? "" : cfg.getCollection().strip();
        return !q.isEmpty() && !embUrl.isEmpty() && !embKey.isEmpty() && !col.isEmpty();
    }

    /**
     * 优先 uigpt.rag.embedding-api-key；为空或未配置时回退 {@code uigpt.api-yi-image.api-key}（环境变量 APIYI_API_KEY
     * 等），避免 .env 里写了空 {@code UIGPT_RAG_EMBEDDING_API_KEY=} 导致占位符不再回落到 APIYi。
     */
    private String resolveEmbeddingBearer(AppProperties.Rag cfg) {
        String k = cfg.getEmbeddingApiKey();
        if (k != null && !k.isBlank()) {
            return k.strip();
        }
        if (appProperties.getApiYiImage() == null) {
            return "";
        }
        String apiYi = appProperties.getApiYiImage().getApiKey();
        return apiYi == null ? "" : apiYi.strip();
    }

    /**
     * 知识库写入失败时返回给管理员的可读说明（对应 {@link #isRagVectorPipelineReady} 与 {@link AppProperties.Rag#enabled}）。
     */
    private String ragSetupFailureMessage(AppProperties.Rag cfg) {
        List<String> missing = new ArrayList<>();
        if (!cfg.isEnabled()) {
            missing.add("总开关：设 UIGPT_RAG_ENABLED=true（或 uigpt.rag.enabled=true）");
        }
        String q = nz(cfg.getQdrantUrl());
        if (q.isEmpty()) {
            missing.add("Qdrant 根地址：UIGPT_RAG_QDRANT_URL（例 http://localhost:6333）");
        }
        String embUrl = nz(cfg.getEmbeddingBaseUrl());
        if (embUrl.isEmpty()) {
            missing.add("Embedding 根 URL：UIGPT_RAG_EMBEDDING_BASE_URL（须含 /v1，例 https://api.apiyi.com/v1）");
        }
        String embKey = resolveEmbeddingBearer(cfg);
        if (embKey.isEmpty()) {
            missing.add(
                    "Embedding Bearer：配置 UIGPT_RAG_EMBEDDING_API_KEY，或与 APIYi 共用 APIYI_API_KEY / uigpt.api-yi-image.api-key");
        }
        String col = cfg.getCollection() == null ? "" : cfg.getCollection().strip();
        if (col.isEmpty()) {
            missing.add(
                    "集合名：UIGPT_RAG_COLLECTION（默认 uigpt_kb；启动时会尝试在 Qdrant 自动建库，维度见 UIGPT_RAG_EMBEDDING_VECTOR_SIZE）");
        } else if (!validCollectionName(col)) {
            missing.add("集合名格式非法（仅字母数字下划线与短横线，最长 128）：当前 uigpt.rag.collection");
        }
        if (missing.isEmpty()) {
            return "RAG 配置异常，请核对 application.yml 的 uigpt.rag 与环境变量。";
        }
        return "RAG 未就绪，请补全：" + String.join("；", missing) + "。";
    }

    private static String nz(String s) {
        return s == null ? "" : s.strip();
    }

    private static String normalizeRoot(String url) {
        if (url == null) {
            return "";
        }
        return url.strip().replaceAll("/+$", "");
    }

    /**
     * 若将 Embedding/OpenAI 网关误填为 Qdrant 地址，会出现 401「Must provide an API key…」等易误判为解析失败。
     */
    private static void assertQdrantRootLooksReasonable(String qRoot) {
        if (qRoot == null || qRoot.isBlank()) {
            return;
        }
        try {
            URI u = URI.create(qRoot);
            String host = u.getHost();
            if (host == null) {
                return;
            }
            String h = host.toLowerCase(Locale.ROOT);
            String[] llmHosts = {
                "openai.com",
                "apiyi.com",
                "anthropic.com",
                "groq.com",
                "deepseek.com",
                "x.ai",
                "mistral.ai",
                "cohere.ai"
            };
            for (String s : llmHosts) {
                if (h.equals(s) || h.endsWith("." + s)) {
                    throw new IllegalStateException(
                            "UIGPT_RAG_QDRANT_URL 主机名疑似 LLM/Embedding 服务（"
                                    + host
                                    + "），不是 Qdrant。请改为 Qdrant 根地址，例如 http://localhost:6333 或 http://qdrant:6333");
                }
            }
            if (h.contains("dashscope") || h.contains("generativelanguage.googleapis.com")) {
                throw new IllegalStateException(
                        "UIGPT_RAG_QDRANT_URL 指向了模型网关而非 Qdrant，请改为 Qdrant 根地址（如 http://localhost:6333）");
            }
            if (h.contains("oai.azure.com")) {
                throw new IllegalStateException(
                        "UIGPT_RAG_QDRANT_URL 指向了 Azure OpenAI 而非 Qdrant，请改为 Qdrant 根地址（如 http://localhost:6333）");
            }
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception ignored) {
            // 非绝对 URL 等跳过启发式
        }
    }

    private static IllegalStateException mapQdrantUnauthorized(HttpClientErrorException.Unauthorized e) {
        return new IllegalStateException(
                "向量库返回 401：若 Qdrant 配置了 QDRANT__SERVICE__API_KEY，请在后端设置与之相同的 UIGPT_RAG_QDRANT_API_KEY"
                        + "（未配置密钥时，Qdrant 也会返回与 OpenAI 网关相似的英文提示，易误判）。"
                        + " 同时请确认 UIGPT_RAG_QDRANT_URL 指向 Qdrant（如 http://127.0.0.1:6333），勿与 Embedding 的 https://…/v1 混用。"
                        + " 原始响应: "
                        + e.getMessage(),
                e);
    }

    /**
     * Qdrant GET 等无 body 请求：加 api-key 头。与 {@link #withQdrantAuthForBody} 分名，避免重载解析到
     * {@link RestClient.RequestHeadersSpec} 导致后续无 {@code contentType}。
     */
    private RestClient.RequestHeadersSpec<?> withQdrantAuthForHeaders(
            RestClient.RequestHeadersSpec<?> spec, AppProperties.Rag cfg) {
        String key = cfg.getQdrantApiKey();
        if (key == null || key.isBlank()) {
            return spec;
        }
        String k = key.strip();
        return spec.header("api-key", k).header("Authorization", "Bearer " + k);
    }

    /** Qdrant POST/PUT JSON：加头后仍可 {@code .contentType().body()}。 */
    @SuppressWarnings("unchecked")
    private <T extends RestClient.RequestBodySpec> T withQdrantAuthForBody(T spec, AppProperties.Rag cfg) {
        String key = cfg.getQdrantApiKey();
        if (key == null || key.isBlank()) {
            return spec;
        }
        String k = key.strip();
        return (T) spec.header("api-key", k).header("Authorization", "Bearer " + k);
    }

    private String resolveCollectionName(ChatRequest request, AppProperties.Rag cfg) {
        String fromReq = request.getRagCollection();
        if (fromReq != null && !fromReq.isBlank()) {
            String s = fromReq.strip();
            if (validCollectionName(s)) {
                return s;
            }
            log.warn("忽略非法 ragCollection: {}", s);
        }
        String def = cfg.getCollection() == null ? "" : cfg.getCollection().strip();
        return validCollectionName(def) ? def : null;
    }

    private static boolean validCollectionName(String s) {
        return s != null && !s.isEmpty() && s.length() <= 128 && s.matches("[a-zA-Z0-9_-]+");
    }

    private static String lastUserPlainText(List<ChatMessageDto> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            ChatMessageDto m = messages.get(i);
            String role = m.getRole();
            if (role != null && "user".equalsIgnoreCase(role.strip())) {
                return m.getContent() == null ? "" : m.getContent().strip();
            }
        }
        return "";
    }

    private ChatRequest prependSystem(ChatRequest in, String systemContent) {
        ChatRequest out = new ChatRequest();
        out.setConversationId(in.getConversationId());
        out.setSkillContext(in.getSkillContext());
        out.setSkillId(in.getSkillId());
        out.setTierMode(in.getTierMode());
        out.setFastFreeformModel(in.getFastFreeformModel());
        out.setDeepReasoning(in.getDeepReasoning());
        out.setUseRag(in.getUseRag());
        out.setRagCollection(in.getRagCollection());
        List<ChatMessageDto> list = new ArrayList<>();
        ChatMessageDto sys = new ChatMessageDto();
        sys.setRole("system");
        sys.setContent(systemContent);
        list.add(sys);
        list.addAll(in.getMessages());
        out.setMessages(list);
        return out;
    }

    private List<Float> embedQuery(AppProperties.Rag cfg, String input) {
        String root = normalizeRoot(cfg.getEmbeddingBaseUrl());
        String url = root + "/embeddings";
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", cfg.getEmbeddingModel());
        body.put("input", input);
        JsonNode res =
                ragRestClient
                        .post()
                        .uri(url)
                        .header("Authorization", "Bearer " + resolveEmbeddingBearer(cfg))
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(body.toString())
                        .retrieve()
                        .body(JsonNode.class);
        if (res == null || !res.path("data").isArray() || res.path("data").isEmpty()) {
            log.warn("embedding 响应无 data");
            return List.of();
        }
        JsonNode emb = res.path("data").get(0).path("embedding");
        if (!emb.isArray()) {
            return List.of();
        }
        List<Float> out = new ArrayList<>(emb.size());
        for (JsonNode n : emb) {
            if (n.isNumber()) {
                out.add((float) n.doubleValue());
            }
        }
        return out;
    }

    private List<ScoredChunk> search(AppProperties.Rag cfg, String collection, List<Float> vector) {
        String qRoot = normalizeRoot(cfg.getQdrantUrl());
        assertQdrantRootLooksReasonable(qRoot);
        String enc = UriUtils.encodePathSegment(collection, StandardCharsets.UTF_8);
        String url = qRoot + "/collections/" + enc + "/points/search";
        ObjectNode body = objectMapper.createObjectNode();
        ArrayNode vec = body.putArray("vector");
        for (Float f : vector) {
            vec.add(f);
        }
        int topK = Math.max(1, Math.min(32, cfg.getTopK()));
        body.put("limit", topK);
        body.put("with_payload", true);
        String json = body.toString();
        JsonNode res;
        try {
            res =
                    withQdrantAuthForBody(ragRestClient.post().uri(url), cfg)
                            .contentType(MediaType.APPLICATION_JSON)
                            .body(json)
                            .retrieve()
                            .body(JsonNode.class);
        } catch (HttpClientErrorException.Unauthorized e) {
            throw mapQdrantUnauthorized(e);
        }
        JsonNode result = res == null ? null : res.get("result");
        if (result == null || !result.isArray()) {
            return List.of();
        }
        double minScore = cfg.getMinScore();
        List<ScoredChunk> out = new ArrayList<>();
        for (JsonNode hit : result) {
            double score = hit.path("score").asDouble(0);
            if (minScore > 0 && score < minScore) {
                continue;
            }
            String text = extractPayloadText(hit.path("payload"));
            if (text.isBlank()) {
                continue;
            }
            out.add(new ScoredChunk(score, text.strip()));
        }
        return out;
    }

    private static String extractPayloadText(JsonNode payload) {
        if (payload == null || payload.isMissingNode() || payload.isNull()) {
            return "";
        }
        JsonNode t = payload.get("text");
        if (t != null && t.isTextual()) {
            return t.asText("");
        }
        t = payload.get("content");
        if (t != null && t.isTextual()) {
            return t.asText("");
        }
        return "";
    }

    private void putPoint(
            AppProperties.Rag cfg,
            String collection,
            String pointId,
            List<Float> vector,
            ObjectNode payload) {
        String qRoot = normalizeRoot(cfg.getQdrantUrl());
        assertQdrantRootLooksReasonable(qRoot);
        String enc = UriUtils.encodePathSegment(collection, StandardCharsets.UTF_8);
        String url = qRoot + "/collections/" + enc + "/points?wait=true";
        ObjectNode point = objectMapper.createObjectNode();
        point.put("id", pointId);
        ArrayNode arr = point.putArray("vector");
        for (Float f : vector) {
            arr.add(f);
        }
        point.set("payload", payload);
        ObjectNode body = objectMapper.createObjectNode();
        body.putArray("points").add(point);
        String json = body.toString();
        try {
            withQdrantAuthForBody(ragRestClient.put().uri(url), cfg)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(json)
                    .retrieve()
                    .toBodilessEntity();
        } catch (HttpClientErrorException.Unauthorized e) {
            throw mapQdrantUnauthorized(e);
        }
    }

    private static String buildContextBlock(List<ScoredChunk> chunks) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < chunks.size(); i++) {
            ScoredChunk c = chunks.get(i);
            sb.append("--- 片段 ")
                    .append(i + 1)
                    .append("（相似度 ")
                    .append(String.format(Locale.ROOT, "%.4f", c.score()))
                    .append("） ---\n");
            sb.append(c.text()).append("\n\n");
        }
        return sb.toString().strip();
    }

    private record ScoredChunk(double score, String text) {}
}
