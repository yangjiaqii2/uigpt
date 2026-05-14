package top.uigpt.imagestudio.orchestration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import top.uigpt.imagestudio.ImageStudioSkillIds;

/**
 * 从意图 JSON 中解析 RAG embedding 查询句（与历史 {@code ImageStudioGenerationPipeline} 行为一致）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NanoBananaRagQueryExtractor {

    private final ObjectMapper objectMapper;

    public String resolveRagEmbeddingQuery(
            String intentJson, String rawUserPrompt, String merged, String studioSkillId) {
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
                if (ImageStudioSkillIds.INTERIOR_DESIGNER.equals(ImageStudioSkillIds.normalize(studioSkillId))) {
                    return fromHints + " interior design";
                }
                return fromHints;
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
