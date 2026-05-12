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
        if (!cfg.isEnabled() || !isConfigured(cfg)) {
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

    /** 使用固定点 id 写入 Qdrant：payload 含可选 title、text、created_at（ISO-8601）。 */
    public void upsertKnowledgePoint(String pointId, String title, String text) {
        AppProperties.Rag cfg = appProperties.getRag();
        if (!cfg.isEnabled() || !isConfigured(cfg)) {
            throw new IllegalStateException("RAG 未启用或未配全 embedding / Qdrant / 集合名");
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

    /** 按点 id 从 Qdrant 删除（幂等：不存在时网关可能仍返回 ok）。 */
    public void deleteKnowledgePoints(List<String> pointIds) {
        if (pointIds == null || pointIds.isEmpty()) {
            return;
        }
        AppProperties.Rag cfg = appProperties.getRag();
        if (!cfg.isEnabled() || !isConfigured(cfg)) {
            throw new IllegalStateException("RAG 未启用或未配全 embedding / Qdrant / 集合名");
        }
        String collection = cfg.getCollection() == null ? "" : cfg.getCollection().strip();
        if (!validCollectionName(collection)) {
            throw new IllegalStateException("默认集合名无效，请检查 uigpt.rag.collection");
        }
        String qRoot = normalizeRoot(cfg.getQdrantUrl());
        String enc = UriUtils.encodePathSegment(collection, StandardCharsets.UTF_8);
        String url = qRoot + "/collections/" + enc + "/points/delete?wait=true";
        ObjectNode body = objectMapper.createObjectNode();
        ArrayNode arr = body.putArray("points");
        for (String id : pointIds) {
            if (id != null && !id.isBlank()) {
                arr.add(id.strip());
            }
        }
        if (arr.isEmpty()) {
            return;
        }
        String json = body.toString();
        if (cfg.getQdrantApiKey() != null && !cfg.getQdrantApiKey().isBlank()) {
            ragRestClient
                    .post()
                    .uri(url)
                    .header("api-key", cfg.getQdrantApiKey().strip())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(json)
                    .retrieve()
                    .toBodilessEntity();
        } else {
            ragRestClient
                    .post()
                    .uri(url)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(json)
                    .retrieve()
                    .toBodilessEntity();
        }
    }

    private static boolean isConfigured(AppProperties.Rag cfg) {
        String q = cfg.getQdrantUrl() == null ? "" : cfg.getQdrantUrl().strip();
        String embUrl = cfg.getEmbeddingBaseUrl() == null ? "" : cfg.getEmbeddingBaseUrl().strip();
        String embKey = cfg.getEmbeddingApiKey() == null ? "" : cfg.getEmbeddingApiKey().strip();
        String col = cfg.getCollection() == null ? "" : cfg.getCollection().strip();
        return !q.isEmpty() && !embUrl.isEmpty() && !embKey.isEmpty() && !col.isEmpty();
    }

    private static String normalizeRoot(String url) {
        if (url == null) {
            return "";
        }
        return url.strip().replaceAll("/+$", "");
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
                        .header("Authorization", "Bearer " + cfg.getEmbeddingApiKey().strip())
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
        RestClient.ResponseSpec retrieve;
        if (cfg.getQdrantApiKey() != null && !cfg.getQdrantApiKey().isBlank()) {
            retrieve =
                    ragRestClient
                            .post()
                            .uri(url)
                            .header("api-key", cfg.getQdrantApiKey().strip())
                            .contentType(MediaType.APPLICATION_JSON)
                            .body(json)
                            .retrieve();
        } else {
            retrieve =
                    ragRestClient
                            .post()
                            .uri(url)
                            .contentType(MediaType.APPLICATION_JSON)
                            .body(json)
                            .retrieve();
        }
        JsonNode res = retrieve.body(JsonNode.class);
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
        if (cfg.getQdrantApiKey() != null && !cfg.getQdrantApiKey().isBlank()) {
            ragRestClient
                    .put()
                    .uri(url)
                    .header("api-key", cfg.getQdrantApiKey().strip())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(json)
                    .retrieve()
                    .toBodilessEntity();
        } else {
            ragRestClient
                    .put()
                    .uri(url)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(json)
                    .retrieve()
                    .toBodilessEntity();
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
