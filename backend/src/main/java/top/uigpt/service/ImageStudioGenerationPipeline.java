package top.uigpt.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 图片工作台 Nano Banana：用户输入 → 鉴权/COS/拼上下文之后的三段式 Prompt 流水线。
 *
 * <ol>
 *   <li>第一阶段：意图解析（结构化 JSON，含 {@code rag_embedding_query}）
 *   <li>第二阶段：RAG 检索（用检索句查知识库，得到片段）
 *   <li>第三阶段：Prompt 组装（意图 + 知识 + 用户原文 → 最终英文作图 prompt）
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ImageStudioGenerationPipeline {

    private final ApiYiImageService apiYiImageService;
    private final RagService ragService;
    private final ObjectMapper objectMapper;

    /**
     * @param merged 已含会话摘要等的合并输入
     * @param rawUserPrompt 本轮用户框内原文，用于 RAG 检索兜底
     */
    public String buildNanoBananaPrompt(
            String merged,
            String rawUserPrompt,
            String aspectRatio,
            String imageSize,
            String ragCollectionOverride) {
        String intentJson =
                apiYiImageService.imageStudioPhase1IntentJson(merged, aspectRatio, imageSize);
        String ragQuery = resolveRagEmbeddingQuery(intentJson, rawUserPrompt, merged);
        String ragBlock =
                ragService.retrieveKnowledgeBlockForImage(
                        ragQuery, Boolean.TRUE, ragCollectionOverride);
        return apiYiImageService.imageStudioPhase3FinalPrompt(
                merged, intentJson, ragBlock, aspectRatio, imageSize);
    }

    private String resolveRagEmbeddingQuery(String intentJson, String rawUserPrompt, String merged) {
        try {
            String json = extractFirstJsonObject(intentJson);
            JsonNode n = objectMapper.readTree(json);
            String rq = n.path("rag_embedding_query").asText("").strip();
            if (!rq.isBlank()) {
                return rq;
            }
            StringBuilder sb = new StringBuilder();
            for (JsonNode x : n.path("style_en_hints")) {
                if (x != null && x.isTextual()) {
                    String t = x.asText("").strip();
                    if (!t.isBlank()) {
                        if (sb.length() > 0) {
                            sb.append(' ');
                        }
                        sb.append(t);
                    }
                }
            }
            String fromHints = sb.toString().strip();
            if (!fromHints.isBlank()) {
                return fromHints + " interior design";
            }
        } catch (Exception e) {
            log.debug("解析 rag_embedding_query 兜底: {}", e.toString());
        }
        String r = rawUserPrompt == null ? "" : rawUserPrompt.strip();
        if (!r.isBlank()) {
            return r;
        }
        String m = merged == null ? "" : merged.strip();
        return m.length() > 800 ? m.substring(0, 800) : m;
    }

    private static String extractFirstJsonObject(String s) {
        if (s == null) {
            return "";
        }
        int start = s.indexOf('{');
        if (start < 0) {
            return s.strip();
        }
        int depth = 0;
        boolean inString = false;
        boolean escape = false;
        for (int i = start; i < s.length(); i++) {
            char c = s.charAt(i);
            if (inString) {
                if (escape) {
                    escape = false;
                } else if (c == '\\') {
                    escape = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
            } else if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return s.substring(start, i + 1);
                }
            }
        }
        return s.substring(start);
    }
}
