package top.uigpt.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import top.uigpt.config.AppProperties;

/**
 * 知识库导入：对过长或含并列连接词的复杂句调用 LLM 改写，便于分块与检索。
 *
 * <p>使用独立 {@link RestClient} 读超时：{@code uigpt.knowledge-import.llm-read-timeout-seconds=0} 时不设有效上限。
 */
@Slf4j
@Service
public class KnowledgeSentenceOptimizerService {

    private static final int COMPLEX_MIN_CHARS = 150;
    private static final String[] COMPLEX_MARKERS = {"同时", "并且", "以及"};

    private static final String KB_SENTENCE_SYSTEM =
            "你是知识库文本预处理助手。用户会提供一句中文，可能过长或含多重并列关系。\n"
                    + "请改写为保留全部信息点、表述清晰、便于向量检索的短句；用中文句号「。」分隔子句。\n"
                    + "不要输出解释、标题、Markdown、引号包裹；只输出改写后的正文。";

    private final AppProperties appProperties;
    private final ObjectMapper objectMapper;
    private final RestClient apiYiKnowledgeChunkRestClient;

    public KnowledgeSentenceOptimizerService(
            AppProperties appProperties,
            ObjectMapper objectMapper,
            @Qualifier("apiYiKnowledgeChunkRestClient") RestClient apiYiKnowledgeChunkRestClient) {
        this.appProperties = appProperties;
        this.objectMapper = objectMapper;
        this.apiYiKnowledgeChunkRestClient = apiYiKnowledgeChunkRestClient;
    }

    public String polishIfComplex(String sentence) {
        if (sentence == null || sentence.isBlank()) {
            return sentence == null ? "" : sentence;
        }
        String s = sentence.strip();
        if (!needsLlmPolish(s)) {
            return s;
        }
        String key = appProperties.getApiYiImage().getApiKey();
        if (key == null || key.isBlank()) {
            log.debug("未配置 APIYi 密钥，跳过复杂句 LLM 改写");
            return s;
        }
        return callChatPolish(s);
    }

    static boolean needsLlmPolish(String s) {
        if (s.length() > COMPLEX_MIN_CHARS) {
            return true;
        }
        for (String m : COMPLEX_MARKERS) {
            if (s.contains(m)) {
                return true;
            }
        }
        return false;
    }

    private String callChatPolish(String userSentence) {
        String base = appProperties.getApiYiImage().getBaseUrl();
        if (base == null || base.isBlank()) {
            return userSentence;
        }
        String root = base.replaceAll("/+$", "");
        String apiUrl = root + "/v1/chat/completions";
        String model = modelForKb();
        int maxTok = Math.min(2048, Math.max(256, appProperties.getApiYiImage().getPromptOptimizeMaxTokens()));

        ObjectNode rootNode = objectMapper.createObjectNode();
        rootNode.put("model", model);
        rootNode.put("max_tokens", maxTok);
        rootNode.put("temperature", 0.3);
        ArrayNode messages = rootNode.putArray("messages");
        ObjectNode sys = messages.addObject();
        sys.put("role", "system");
        sys.put("content", KB_SENTENCE_SYSTEM);
        ObjectNode user = messages.addObject();
        user.put("role", "user");
        user.put("content", "【原句】\n" + userSentence + "\n\n请直接输出改写后的正文。");

        try {
            JsonNode response =
                    apiYiKnowledgeChunkRestClient
                            .post()
                            .uri(apiUrl)
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + appProperties.getApiYiImage().getApiKey().strip())
                            .contentType(MediaType.APPLICATION_JSON)
                            .accept(MediaType.APPLICATION_JSON)
                            .body(rootNode.toString())
                            .retrieve()
                            .body(JsonNode.class);
            if (response == null) {
                return userSentence;
            }
            JsonNode choices = response.path("choices");
            if (!choices.isArray() || choices.isEmpty()) {
                log.warn("知识库复杂句改写缺少 choices: {}", response);
                return userSentence;
            }
            String out = extractChatCompletionTextContent(choices.get(0).path("message").path("content"));
            out = sanitizeKbText(out);
            return out.isBlank() ? userSentence : out.strip();
        } catch (RestClientResponseException e) {
            log.warn(
                    "知识库复杂句改写失败 status={} body={}",
                    e.getStatusCode(),
                    e.getResponseBodyAsString());
            return userSentence;
        } catch (Exception e) {
            log.warn("知识库复杂句改写异常", e);
            return userSentence;
        }
    }

    private String modelForKb() {
        String m = appProperties.getApiYiImage().getPromptOptimizeModel();
        if (m != null && !m.isBlank()) {
            return m.strip();
        }
        String v = appProperties.getApiYiImage().getVisionModel();
        if (v != null && !v.isBlank()) {
            return v.strip();
        }
        return "gpt-4.1-mini";
    }

    private static String extractChatCompletionTextContent(JsonNode contentNode) {
        if (contentNode == null || contentNode.isMissingNode()) {
            return "";
        }
        if (contentNode.isTextual()) {
            return contentNode.asText("");
        }
        if (contentNode.isArray()) {
            StringBuilder sb = new StringBuilder();
            for (JsonNode part : contentNode) {
                if (part != null && part.has("text")) {
                    sb.append(part.get("text").asText(""));
                }
            }
            return sb.toString();
        }
        return contentNode.asText("");
    }

    private static String sanitizeKbText(String raw) {
        if (raw == null) {
            return "";
        }
        String s = raw.strip();
        if (s.startsWith("```")) {
            int nl = s.indexOf('\n');
            if (nl > 0) {
                s = s.substring(nl + 1);
            }
            int fence = s.lastIndexOf("```");
            if (fence >= 0) {
                s = s.substring(0, fence);
            }
        }
        return s.strip();
    }
}
