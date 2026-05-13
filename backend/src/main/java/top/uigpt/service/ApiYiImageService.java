package top.uigpt.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.server.ResponseStatusException;
import top.uigpt.UserFacingMessages;
import top.uigpt.config.AppProperties;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.net.URLEncoder;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import javax.imageio.ImageIO;
import top.uigpt.dto.Sora2SubmitRequest;

/**
 * APIYi 图像能力：OpenAI 兼容 <code>/v1/images/generations</code>、Gemini <code>:generateContent</code>
 *（文生图 / 参考图编辑）、<code>/v1/images/edits</code>、<code>/v1/chat/completions</code>。
 *
 * <p>高速纯文生图默认按权重在 OpenAI generations（GPT 图）与 Gemini generateContent 之间轮流，GPT 占比更高（默
 * 认 3:1）。
 *
 * <p>模型为 {@code gpt-image-2-vip} 时按 API易文档锁定 {@code size}（30 档之一或 {@code auto}，半角 {@code x}），不传
 * {@code n}/{@code quality}/{@code aspect_ratio}。概览默认响应为 {@code url}（R2，约 24h），亦可 {@code b64_json}
 *（常含完整 data URL）；解析见 {@link #decodeDataUrlOrBase64}。
 *
 * <p>图片工作台 Nano Banana（Gemini {@code :generateContent}）无 OpenAI 风格 {@code n}；多候选由控制器并行多次调用
 * {@link #nanoBananaTextToImage} / {@link #nanoBananaEditImages} 实现。
 */
@Slf4j
@Service
public class ApiYiImageService {

    /** 加权轮流计数（周期 = OpenAI 权重 + Gemini 权重）。 */
    private final AtomicInteger ttiRoundRobin = new AtomicInteger(0);

    private final AppProperties appProperties;
    private final ObjectMapper objectMapper;
    private final RestClient apiYiRestClient;
    /** 仅 {@link #visionChatAnalyze}：较短读超时，尽快失败或返回，减少堵主对话流首包 */
    private final RestClient apiYiVisionRestClient;
    private final HttpClient downloadHttpClient;

    public ApiYiImageService(
            AppProperties appProperties,
            ObjectMapper objectMapper,
            @Qualifier("apiYiRestClient") RestClient apiYiRestClient,
            @Qualifier("apiYiVisionRestClient") RestClient apiYiVisionRestClient,
            @Qualifier("downloadHttpClient") HttpClient downloadHttpClient) {
        this.appProperties = appProperties;
        this.objectMapper = objectMapper;
        this.apiYiRestClient = apiYiRestClient;
        this.apiYiVisionRestClient = apiYiVisionRestClient;
        this.downloadHttpClient = downloadHttpClient;
    }

    /**
     * 已配置 API易密钥时可调用网关（高速出图 / 对话补全等）；与 {@code APIYI_IMAGE_ENABLED} 解耦——仅不想配密钥时留空即可关闭。
     */
    public boolean isReady() {
        AppProperties.ApiYiImage c = appProperties.getApiYiImage();
        return c != null && c.getApiKey() != null && !c.getApiKey().isBlank();
    }

    /** 已配置 API易且开启识图开关、并配置了 vision 模型 id 时可用 */
    public boolean isVisionReady() {
        if (!isReady()) {
            return false;
        }
        AppProperties.ApiYiImage c = appProperties.getApiYiImage();
        if (!c.isVisionEnabled()) {
            return false;
        }
        String vm = c.getVisionModel();
        return vm != null && !vm.isBlank();
    }

    private String visionModelId() {
        String m = appProperties.getApiYiImage().getVisionModel();
        return m == null || m.isBlank() ? "gpt-4.1-mini" : m.strip();
    }

    private int visionMaxTokensResolved() {
        int n = appProperties.getApiYiImage().getVisionMaxTokens();
        return Math.min(4096, Math.max(256, n));
    }

    /**
     * 图像理解（识图）：OpenAI 兼容 <code>POST /v1/chat/completions</code>，user 消息含 text + 多图
     * <code>image_url</code>。
     *
     * @param instructionText 视觉任务说明（建议中文）
     * @param imageUrlsOrDataUrls {@code data:image/...;base64,...} 或 {@code https://...}，1～4 张
     * @return 模型返回的正文
     */
    public String visionChatAnalyze(String instructionText, List<String> imageUrlsOrDataUrls) {
        if (!isVisionReady()) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE, "API易识图未启用或未配置 vision 模型");
        }
        if (imageUrlsOrDataUrls == null || imageUrlsOrDataUrls.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "识图请求缺少图片");
        }
        String apiUrl = baseUrl() + "/v1/chat/completions";
        ObjectNode root = objectMapper.createObjectNode();
        root.put("model", visionModelId());
        root.put("max_tokens", visionMaxTokensResolved());
        ArrayNode messages = root.putArray("messages");
        ObjectNode userMsg = messages.addObject();
        userMsg.put("role", "user");
        ArrayNode content = userMsg.putArray("content");
        ObjectNode textPart = content.addObject();
        textPart.put("type", "text");
        textPart.put("text", instructionText == null ? "" : instructionText);
        int imgCount = 0;
        for (String u : imageUrlsOrDataUrls) {
            if (u == null || u.isBlank()) {
                continue;
            }
            ObjectNode imgPart = content.addObject();
            imgPart.put("type", "image_url");
            ObjectNode imageUrlObj = objectMapper.createObjectNode();
            imageUrlObj.put("url", u.strip());
            imgPart.set("image_url", imageUrlObj);
            imgCount++;
            if (imgCount >= 4) {
                break;
            }
        }
        if (imgCount == 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "识图请求无有效图片地址");
        }
        try {
            JsonNode response =
                    apiYiVisionRestClient
                            .post()
                            .uri(apiUrl)
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey())
                            .contentType(MediaType.APPLICATION_JSON)
                            .accept(MediaType.APPLICATION_JSON)
                            .body(root.toString())
                            .retrieve()
                            .body(JsonNode.class);
            if (response == null) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_GATEWAY, UserFacingMessages.NETWORK_TRY_LATER);
            }
            JsonNode choices = response.path("choices");
            if (!choices.isArray() || choices.isEmpty()) {
                log.warn("APIYi 识图响应缺少 choices: {}", response);
                throw new ResponseStatusException(
                        HttpStatus.BAD_GATEWAY, UserFacingMessages.NETWORK_TRY_LATER);
            }
            String out = choices.get(0).path("message").path("content").asText("");
            if (out.isBlank()) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_GATEWAY, UserFacingMessages.NETWORK_TRY_LATER);
            }
            return out.strip();
        } catch (RestClientResponseException e) {
            log.warn(
                    "APIYi 识图失败 status={} body={}",
                    e.getStatusCode(),
                    e.getResponseBodyAsString());
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY, UserFacingMessages.NETWORK_TRY_LATER);
        }
    }

    private static final String IMAGE_STUDIO_PROMPT_OPTIMIZER_SYSTEM =
            "你是专业的 AI 绘画提示词工程师。用户会提供简短中文描述以及当前创作参数（工具、风格、比例、画质）。"
                    + "请输出一条可直接用于 Gemini / Nano Banana 文生图或图生图的优化提示词。\n"
                    + "要求：\n"
                    + "1. 以简体中文为主，必要时保留专有英文名词。\n"
                    + "2. 在保留用户核心意图的前提下，适度补充：主体与场景、构图与视角、光影与氛围、色彩与材质、风格关键词；避免空洞堆砌。\n"
                    + "3. 结合比例与工具类型做轻微适配（如竖幅可强调纵向构图；图生图强调在参考基础上的改动）。\n"
                    + "4. 不要输出解释、标题、前后缀、Markdown 代码块或引号包裹；只输出提示词正文本身。";

    private static final String VIDEO_STUDIO_PROMPT_OPTIMIZER_SYSTEM =
            "你是专业的 AI 视频生成提示词工程师（面向文生视频、图生视频、镜头延展等场景，如 Sora 类模型）。"
                    + "用户会提供简短中文描述以及当前创作参数（模式、风格、画幅比例、分辨率与时长、运动幅度等）。"
                    + "请输出一条可直接用于视频生成的优化提示词。\n"
                    + "要求：\n"
                    + "1. 以简体中文为主，必要时保留专有英文名词。\n"
                    + "2. 在保留用户核心意图的前提下，适度补充：主体与动作、环境与空间关系、镜头语言（景别、机位、推拉摇移、节奏）、光影与色调、氛围与情绪；避免空洞堆砌。\n"
                    + "3. 结合画幅比例做适配（如竖屏可强调手持/短视频观感；超宽可强调横向调度）。结合时长暗示信息密度（短片段强化单一镜头焦点）。\n"
                    + "4. 不要输出解释、标题、前后缀、Markdown 代码块或引号包裹；只输出提示词正文本身。";

    /**
     * 图片工作台：扩写/优化绘画提示词（纯文本 {@code /v1/chat/completions}）。
     *
     * <p>使用较短读超时的 {@link #apiYiVisionRestClient}，与识图一致。
     */
    public String optimizeImageStudioPrompt(
            String rawPrompt,
            String toolId,
            String styleLabel,
            String aspectLabel,
            String qualityLabel,
            String medium) {
        requireReady();
        final int maxIn = 6000;
        String p = rawPrompt == null ? "" : rawPrompt.strip();
        if (p.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请先输入描述");
        }
        if (p.length() > maxIn) {
            p = p.substring(0, maxIn);
        }
        boolean video = medium != null && "video".equalsIgnoreCase(medium.strip());
        String model = promptOptimizeModelResolved();
        int maxTok = promptOptimizeMaxTokensResolved();
        String apiUrl = baseUrl() + "/v1/chat/completions";
        ObjectNode root = objectMapper.createObjectNode();
        root.put("model", model);
        root.put("max_tokens", maxTok);
        root.put("temperature", 0.65);
        ArrayNode messages = root.putArray("messages");
        ObjectNode sysTurn = messages.addObject();
        sysTurn.put("role", "system");
        sysTurn.put(
                "content",
                video ? VIDEO_STUDIO_PROMPT_OPTIMIZER_SYSTEM : IMAGE_STUDIO_PROMPT_OPTIMIZER_SYSTEM);
        ObjectNode userTurn = messages.addObject();
        userTurn.put("role", "user");
        userTurn.put(
                "content",
                buildImageStudioOptimizeUserPayload(
                        p, toolId, styleLabel, aspectLabel, qualityLabel, video));

        try {
            JsonNode response =
                    apiYiVisionRestClient
                            .post()
                            .uri(apiUrl)
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey())
                            .contentType(MediaType.APPLICATION_JSON)
                            .accept(MediaType.APPLICATION_JSON)
                            .body(root.toString())
                            .retrieve()
                            .body(JsonNode.class);
            if (response == null) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_GATEWAY, UserFacingMessages.NETWORK_TRY_LATER);
            }
            JsonNode choices = response.path("choices");
            if (!choices.isArray() || choices.isEmpty()) {
                log.warn("APIYi 提示词优化缺少 choices: {}", response);
                throw new ResponseStatusException(
                        HttpStatus.BAD_GATEWAY, UserFacingMessages.NETWORK_TRY_LATER);
            }
            String out = extractChatCompletionTextContent(choices.get(0).path("message").path("content"));
            out = sanitizeOptimizedPrompt(out);
            if (out.isBlank()) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_GATEWAY, UserFacingMessages.NETWORK_TRY_LATER);
            }
            return out;
        } catch (RestClientResponseException e) {
            log.warn(
                    "APIYi 提示词优化失败 status={} body={}",
                    e.getStatusCode(),
                    e.getResponseBodyAsString());
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY, UserFacingMessages.NETWORK_TRY_LATER);
        }
    }

    private String promptOptimizeModelResolved() {
        String m = appProperties.getApiYiImage().getPromptOptimizeModel();
        if (m != null && !m.isBlank()) {
            return m.strip();
        }
        return visionModelId();
    }

    private int promptOptimizeMaxTokensResolved() {
        int n = appProperties.getApiYiImage().getPromptOptimizeMaxTokens();
        return Math.min(2048, Math.max(128, n));
    }

    private int interiorPromptOptimizeMaxTokensResolved() {
        int n = appProperties.getApiYiImage().getInteriorPromptOptimizeMaxTokens();
        return Math.min(4096, Math.max(256, n));
    }

    /**
     * 第一阶段：从合并后的用户输入解析结构化意图 JSON（含 {@code rag_embedding_query} 供向量检索）。未配 API 或失败时返回兜底
     * JSON。
     */
    public String imageStudioPhase1IntentJson(String merged, String aspectRatio, String imageSize) {
        String m = merged == null ? "" : merged.strip();
        if (m.isEmpty()) {
            return fallbackIntentJson("");
        }
        if (!isReady()) {
            return fallbackIntentJson(m);
        }
        final int maxIn = 8000;
        if (m.length() > maxIn) {
            m = m.substring(0, maxIn);
        }
        String ar = aspectRatio == null || aspectRatio.isBlank() ? "1:1" : aspectRatio.strip();
        String sz = imageSize == null || imageSize.isBlank() ? "2K" : imageSize.strip();
        String user =
                "【出图参数】画幅比例："
                        + ar
                        + "；出图分辨率档："
                        + sz
                        + "\n\n【用户合并输入】\n"
                        + m;
        String raw =
                chatCompletionImageStudio(
                        ImageStudioIntentPrompts.PHASE1_INTENT_SYSTEM,
                        user,
                        Math.min(1024, Math.max(256, interiorPromptOptimizeMaxTokensResolved() / 2)),
                        0.25);
        if (raw == null || raw.isBlank()) {
            return fallbackIntentJson(m);
        }
        String cleaned = sanitizeOptimizedPrompt(raw);
        try {
            String json = extractFirstJsonObject(cleaned);
            JsonNode n = objectMapper.readTree(json);
            String rq = n.path("rag_embedding_query").asText("").strip();
            if (rq.isBlank()) {
                return fallbackIntentJson(m);
            }
            return json;
        } catch (Exception e) {
            log.warn("图片工作台第一阶段意图 JSON 解析失败: {}", e.toString());
            return fallbackIntentJson(m);
        }
    }

    /**
     * 第三阶段：综合意图 JSON + 知识库片段 + 用户原文，输出家装英文 SD 的 JSON（正/负提示合并为一段）。失败时回退为「知识块
     * + 原文」或原文。
     */
    public String imageStudioPhase3FinalPrompt(
            String merged,
            String phase1IntentJson,
            String ragKnowledgeBlock,
            String aspectRatio,
            String imageSize) {
        String m = merged == null ? "" : merged.strip();
        String intent = phase1IntentJson == null ? "{}" : phase1IntentJson.strip();
        String rag = ragKnowledgeBlock == null ? "" : ragKnowledgeBlock.strip();
        if (m.isEmpty()) {
            return m;
        }
        if (!isReady()) {
            return mergeRagPrefixWithUserText(rag, m);
        }
        final int maxIn = 8000;
        String mCut = m.length() > maxIn ? m.substring(0, maxIn) : m;
        String sys =
                InteriorNanoBananaPromptOptimizer.SYSTEM_PROMPT
                        + ImageStudioIntentPrompts.PHASE3_SYSTEM_APPENDIX;
        String user =
                InteriorNanoBananaPromptOptimizer.buildPhase3UserMessage(
                        mCut, intent, rag, aspectRatio, imageSize);
        String raw =
                chatCompletionImageStudio(
                        sys, user, interiorPromptOptimizeMaxTokensResolved(), 0.35);
        if (raw == null || raw.isBlank()) {
            return mergeRagPrefixWithUserText(rag, mCut);
        }
        String out = sanitizeOptimizedPrompt(raw);
        try {
            String json = extractFirstJsonObject(out);
            JsonNode n = objectMapper.readTree(json);
            String pos = n.path("prompt").asText("").strip();
            String neg = n.path("negative_prompt").asText("").strip();
            String combined = mergeInteriorPositiveNegative(pos, neg);
            if (combined.isBlank()) {
                return mergeRagPrefixWithUserText(rag, mCut);
            }
            return combined;
        } catch (Exception e) {
            log.warn("图片工作台第三阶段 Prompt 解析失败: {}", e.toString());
            return mergeRagPrefixWithUserText(rag, mCut);
        }
    }

    private static String mergeRagPrefixWithUserText(String ragBlock, String userText) {
        if (ragBlock == null || ragBlock.isBlank()) {
            return userText;
        }
        return ragBlock + "\n\n" + userText;
    }

    private String fallbackIntentJson(String mergedSnippet) {
        try {
            ObjectNode o = objectMapper.createObjectNode();
            o.put("room", "living room");
            o.putArray("style_tags");
            ArrayNode hints = o.putArray("style_en_hints");
            hints.add("modern minimalist interior design");
            o.putArray("material_light_furniture");
            String c = mergedSnippet == null ? "" : mergedSnippet.strip();
            if (c.length() > 800) {
                c = c.substring(0, 800);
            }
            o.put("constraints", c);
            o.put(
                    "rag_embedding_query",
                    "modern minimalist living room interior design photorealistic 8k architectural visualization");
            return objectMapper.writeValueAsString(o);
        } catch (Exception e) {
            return "{\"room\":\"living room\",\"style_tags\":[],\"style_en_hints\":[\"modern minimalist interior\"],"
                    + "\"material_light_furniture\":[],\"constraints\":\"\","
                    + "\"rag_embedding_query\":\"modern minimalist living room interior design photorealistic\"}";
        }
    }

    private String chatCompletionImageStudio(
            String systemPrompt, String userContent, int maxTokens, double temperature) {
        if (!isReady()) {
            return null;
        }
        String model = promptOptimizeModelResolved();
        int maxTok = Math.min(4096, Math.max(64, maxTokens));
        String apiUrl = baseUrl() + "/v1/chat/completions";
        ObjectNode root = objectMapper.createObjectNode();
        root.put("model", model);
        root.put("max_tokens", maxTok);
        root.put("temperature", temperature);
        ArrayNode messages = root.putArray("messages");
        ObjectNode sysTurn = messages.addObject();
        sysTurn.put("role", "system");
        sysTurn.put("content", systemPrompt == null ? "" : systemPrompt);
        ObjectNode userTurn = messages.addObject();
        userTurn.put("role", "user");
        userTurn.put("content", userContent == null ? "" : userContent);
        try {
            JsonNode response =
                    apiYiVisionRestClient
                            .post()
                            .uri(apiUrl)
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey())
                            .contentType(MediaType.APPLICATION_JSON)
                            .accept(MediaType.APPLICATION_JSON)
                            .body(root.toString())
                            .retrieve()
                            .body(JsonNode.class);
            if (response == null) {
                log.warn("APIYi 图片流水线 chat 响应为空");
                return null;
            }
            JsonNode choices = response.path("choices");
            if (!choices.isArray() || choices.isEmpty()) {
                log.warn("APIYi 图片流水线 chat 缺少 choices");
                return null;
            }
            String out =
                    extractChatCompletionTextContent(choices.get(0).path("message").path("content"));
            return out == null ? null : out.strip();
        } catch (RestClientResponseException e) {
            log.warn(
                    "APIYi 图片流水线 chat 失败 status={} body={}",
                    e.getStatusCode(),
                    e.getResponseBodyAsString());
            return null;
        } catch (Exception e) {
            log.warn("APIYi 图片流水线 chat 异常: {}", e.toString());
            return null;
        }
    }

    private static String buildImageStudioOptimizeUserPayload(
            String prompt,
            String toolId,
            String styleLabel,
            String aspectLabel,
            String qualityLabel,
            boolean video) {
        StringBuilder sb = new StringBuilder();
        sb.append("【用户原始描述】\n").append(prompt).append("\n\n【当前参数】\n");
        sb.append("工具/模式：").append(blankToDash(toolId)).append('\n');
        sb.append("风格：").append(blankToDash(styleLabel)).append('\n');
        sb.append("画幅比例：").append(blankToDash(aspectLabel)).append('\n');
        if (video) {
            sb.append("分辨率·时长·运动等：").append(blankToDash(qualityLabel)).append('\n');
        } else {
            sb.append("画质倾向：").append(blankToDash(qualityLabel)).append('\n');
        }
        sb.append("\n请直接输出优化后的提示词正文。");
        return sb.toString();
    }

    private static String blankToDash(String s) {
        return s == null || s.isBlank() ? "—" : s.strip();
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

    /** 从模型输出中截取第一个平衡花括号 JSON 对象（忽略前后说明文字）。 */
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

    private static String mergeInteriorPositiveNegative(String positive, String negative) {
        String p = positive == null ? "" : positive.strip();
        String n = negative == null ? "" : negative.strip();
        if (p.isEmpty()) {
            return "";
        }
        if (n.isEmpty()) {
            return p;
        }
        return p + "\n\nAvoid / negative guidance:\n" + n;
    }

    private static String sanitizeOptimizedPrompt(String raw) {
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
        if ((s.startsWith("\"") && s.endsWith("\"")) || (s.startsWith("「") && s.endsWith("」"))) {
            s = s.substring(1, s.length() - 1).strip();
        }
        return s.strip();
    }

    private String baseUrl() {
        String b = appProperties.getApiYiImage().getBaseUrl();
        return b == null ? "" : b.replaceAll("/+$", "");
    }

    private String apiKey() {
        return appProperties.getApiYiImage().getApiKey().trim();
    }

    private String model() {
        String m = appProperties.getApiYiImage().getModel();
        return m == null || m.isBlank() ? "gpt-image-2-all" : m.strip();
    }

    private String geminiImageModelId() {
        String m = appProperties.getApiYiImage().getGeminiImageModel();
        return m == null || m.isBlank() ? "gemini-3.1-flash-image-preview" : m.strip();
    }

    private String geminiImageSize() {
        String s = appProperties.getApiYiImage().getGeminiImageSize();
        return s == null || s.isBlank() ? "2K" : s.strip();
    }

    private String generationsResponseFormat() {
        String f = appProperties.getApiYiImage().getGenerationsResponseFormat();
        if (f == null || f.isBlank()) {
            return "url";
        }
        String x = f.strip().toLowerCase(Locale.ROOT);
        return "b64_json".equals(x) ? "b64_json" : "url";
    }

    /** VIP {@code size} 档位：{@code 1k} / {@code 2k} / {@code 4k}（含 {@code fast}/{@code detail} 别名）。 */
    private String vipSizeTierToken() {
        String t = appProperties.getApiYiImage().getVipSizeTier();
        if (t == null || t.isBlank()) {
            return "2k";
        }
        String x = t.strip().toLowerCase(Locale.ROOT);
        if ("1k".equals(x) || "fast".equals(x)) {
            return "1k";
        }
        if ("4k".equals(x) || "detail".equals(x)) {
            return "4k";
        }
        return "2k";
    }

    /**
     * 单次请求覆盖 VIP / Gemini 尺寸：{@code standard}=约 1K、{@code hd}=2K、{@code ultra}=4K；空白则沿用全局配置。
     */
    private String resolveVipTierFromQualityTier(String qualityTier) {
        if (qualityTier == null || qualityTier.isBlank()) {
            return vipSizeTierToken();
        }
        String q = qualityTier.strip().toLowerCase(Locale.ROOT);
        if ("standard".equals(q)) {
            return "1k";
        }
        if ("ultra".equals(q)) {
            return "4k";
        }
        if ("hd".equals(q)) {
            return "2k";
        }
        return vipSizeTierToken();
    }

    private String geminiImageSizeResolved(String qualityTier) {
        if (qualityTier == null || qualityTier.isBlank()) {
            return geminiImageSize();
        }
        String q = qualityTier.strip().toLowerCase(Locale.ROOT);
        if ("standard".equals(q)) {
            return "1K";
        }
        if ("ultra".equals(q)) {
            return "4K";
        }
        if ("hd".equals(q)) {
            return "2K";
        }
        return geminiImageSize();
    }

    /** 显式覆盖或回退到配置的 generations/edits 响应格式。 */
    private String resolveImageResponseFormat(String explicit) {
        if (explicit != null && !explicit.isBlank()) {
            String x = explicit.strip().toLowerCase(Locale.ROOT);
            if ("url".equals(x) || "b64_json".equals(x)) {
                return x;
            }
        }
        return generationsResponseFormat();
    }

    /** 是否为 API易 {@code gpt-image-2-vip}（尺寸须在官方 30 档内）。 */
    static boolean isGptImage2VipModel(String modelId) {
        return modelId != null && "gpt-image-2-vip".equalsIgnoreCase(modelId.strip());
    }

    /**
     * 高速文生图：默认按权重轮流（GPT/OpenAI 占比更高）；{@code rotate-tti-providers=false} 时固定 OpenAI
     * generations。
     */
    public byte[] generateImageFromTextPrompt(String prompt, String aspectKey) {
        return generateImageFromTextPrompt(prompt, aspectKey, null);
    }

    /**
     * @param qualityTier 可选 {@code standard}/{@code hd}/{@code ultra}，映射 VIP / Gemini 输出档位
     */
    public byte[] generateImageFromTextPrompt(String prompt, String aspectKey, String qualityTier) {
        requireReady();
        AppProperties.ApiYiImage cfg = appProperties.getApiYiImage();
        if (!cfg.isRotateTtiProviders()) {
            return generateViaOpenAiImagesApi(prompt, aspectKey, qualityTier);
        }
        int wOpen = Math.max(1, cfg.getTtiOpenAiWeight());
        int wGem = Math.max(1, cfg.getTtiGeminiWeight());
        int cycle = wOpen + wGem;
        int turn = ttiRoundRobin.getAndIncrement();
        int slot = Math.floorMod(turn, cycle);
        if (slot < wOpen) {
            log.info(
                    "APIYi 文生图路由 [#{}, slot {}/{}]: OpenAI /v1/images/generations（GPT，权重 {}）",
                    turn,
                    slot,
                    cycle,
                    wOpen);
            return generateViaOpenAiImagesApi(prompt, aspectKey, qualityTier);
        }
        log.info(
                "APIYi 文生图路由 [#{}, slot {}/{}]: Gemini :generateContent（{}，权重 {}）",
                turn,
                slot,
                cycle,
                geminiImageModelId(),
                wGem);
        return generateViaGeminiGenerateContent(prompt, aspectKey, qualityTier);
    }

    /**
     * OpenAI 兼容文生图：<code>POST /v1/images/generations</code>。
     *
     * <p>{@code gpt-image-2-vip}：必填合法 {@code size}（或 {@code auto}），见 {@link
     * #mapAspectToGptImage2VipSize}；不传 {@code n}/{@code quality}/{@code aspect_ratio}。
     */
    private byte[] generateViaOpenAiImagesApi(String prompt, String aspectKey, String qualityTier) {
        String url = baseUrl() + "/v1/images/generations";
        ObjectNode body = objectMapper.createObjectNode();
        String mid = model();
        body.put("model", mid);
        body.put("prompt", prompt == null ? "" : prompt);
        body.put("response_format", generationsResponseFormat());
        if (isGptImage2VipModel(mid)) {
            String tierTok = resolveVipTierFromQualityTier(qualityTier);
            String vipSize = mapAspectToGptImage2VipSize(aspectKey, tierTok);
            body.put("size", vipSize);
            log.debug(
                    "APIYi gpt-image-2-vip generations size={} tier={} response_format={}",
                    vipSize,
                    tierTok,
                    generationsResponseFormat());
        } else {
            String openAiSize = mapAspectToOpenAiImageSize(aspectKey);
            if (openAiSize != null) {
                body.put("size", openAiSize);
            }
        }
        try {
            String raw =
                    apiYiRestClient
                            .post()
                            .uri(url)
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey())
                            .contentType(MediaType.APPLICATION_JSON)
                            .body(body.toString())
                            .retrieve()
                            .body(String.class);
            return bytesFromGenerationsLikeResponse(raw);
        } catch (RestClientResponseException e) {
            log.warn("APIYi images/generations 失败 status={} body={}", e.getStatusCode(), e.getResponseBodyAsString());
            throw new ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_GATEWAY, UserFacingMessages.NETWORK_TRY_LATER);
        }
    }

    /**
     * Gemini 文生图：<code>POST /v1beta/models/{model}:generateContent</code>（仅文本 parts）。
     */
    private byte[] generateViaGeminiGenerateContent(String prompt, String aspectKey, String qualityTier) {
        String url =
                baseUrl()
                        + "/v1beta/models/"
                        + geminiImageModelId()
                        + ":generateContent";
        ObjectNode root = objectMapper.createObjectNode();
        ArrayNode contents = root.putArray("contents");
        ObjectNode contentTurn = contents.addObject();
        ArrayNode parts = contentTurn.putArray("parts");
        parts.addObject().put("text", prompt == null ? "" : prompt);

        ObjectNode genCfg = root.putObject("generationConfig");
        genCfg.putArray("responseModalities").add("IMAGE");
        ObjectNode imageCfg = genCfg.putObject("imageConfig");
        imageCfg.put("aspectRatio", mapAspectToGeminiAspectRatio(aspectKey));
        imageCfg.put("imageSize", geminiImageSizeResolved(qualityTier));

        try {
            String raw =
                    apiYiRestClient
                            .post()
                            .uri(url)
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey())
                            .contentType(MediaType.APPLICATION_JSON)
                            .body(root.toString())
                            .retrieve()
                            .body(String.class);
            return bytesFromGeminiGenerateContentResponse(raw);
        } catch (RestClientResponseException e) {
            log.warn(
                    "APIYi Gemini generateContent 失败 status={} body={}",
                    e.getStatusCode(),
                    e.getResponseBodyAsString());
            throw new ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_GATEWAY, UserFacingMessages.NETWORK_TRY_LATER);
        }
    }

    /**
     * Gemini 参考图编辑：同一 <code>:generateContent</code>，parts 含 text + inlineData（base64）。
     */
    public byte[] geminiGenerateContentWithInlineImage(
            String prompt, byte[] imageBytes, String mimeType, String aspectKey) {
        requireReady();
        if (imageBytes == null || imageBytes.length == 0) {
            throw new ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST, "参考图为空");
        }
        String url =
                baseUrl()
                        + "/v1beta/models/"
                        + geminiImageModelId()
                        + ":generateContent";
        ObjectNode root = objectMapper.createObjectNode();
        ArrayNode contents = root.putArray("contents");
        ObjectNode contentTurn = contents.addObject();
        ArrayNode parts = contentTurn.putArray("parts");
        parts.addObject().put("text", prompt == null ? "" : prompt);
        ObjectNode inlinePart = parts.addObject();
        ObjectNode inlineData = inlinePart.putObject("inlineData");
        String mt =
                mimeType != null && !mimeType.isBlank() ? mimeType.strip() : "image/jpeg";
        inlineData.put("mimeType", mt);
        inlineData.put("data", Base64.getEncoder().encodeToString(imageBytes));

        ObjectNode genCfg = root.putObject("generationConfig");
        genCfg.putArray("responseModalities").add("IMAGE");
        ObjectNode imageCfg = genCfg.putObject("imageConfig");
        imageCfg.put("aspectRatio", mapAspectToGeminiAspectRatio(aspectKey));
        imageCfg.put("imageSize", geminiImageSize());

        try {
            String raw =
                    apiYiRestClient
                            .post()
                            .uri(url)
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey())
                            .contentType(MediaType.APPLICATION_JSON)
                            .body(root.toString())
                            .retrieve()
                            .body(String.class);
            return bytesFromGeminiGenerateContentResponse(raw);
        } catch (RestClientResponseException e) {
            log.warn(
                    "APIYi Gemini 参考图编辑失败 status={} body={}",
                    e.getStatusCode(),
                    e.getResponseBodyAsString());
            throw new ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_GATEWAY, UserFacingMessages.NETWORK_TRY_LATER);
        }
    }

    private String nanoBananaImageModelId() {
        String m = appProperties.getApiYiImage().getNanoBananaImageModel();
        return m == null || m.isBlank() ? "gemini-3-pro-image-preview" : m.strip();
    }

    /** API 易 Nano Banana Pro 文档中的 {@code aspectRatio} 枚举。 */
    static String mapAspectToNanoBananaRatio(String aspectKey) {
        if (aspectKey == null || aspectKey.isBlank()) {
            return "1:1";
        }
        String k = aspectKey.strip();
        return switch (k) {
            case "1:1", "2:3", "3:2", "3:4", "4:3", "4:5", "5:4", "9:16", "16:9", "21:9" -> k;
            default -> "1:1";
        };
    }

    /**
     * Gemini 3.x 图像模型官方 REST：{@code aspectRatio}/{@code imageSize} 须放在 {@code
     * generationConfig.responseFormat.image}；旧字段 {@code imageConfig} 会被忽略，导致始终按默认比例（多为 1:1）出图。
     *
     * @see <a href="https://ai.google.dev/gemini-api/docs/image-generation">Image generation</a>
     */
    private static void putGemini3ImageFormatInGenerationConfig(
            ObjectNode generationConfig, String aspectRatio, String imageSize) {
        generationConfig.putArray("responseModalities").add("TEXT").add("IMAGE");
        ObjectNode responseFormat = generationConfig.putObject("responseFormat");
        ObjectNode image = responseFormat.putObject("image");
        image.put("aspectRatio", mapAspectToNanoBananaRatio(aspectRatio));
        image.put("imageSize", normalizeNanoBananaImageSize(imageSize));
    }

    static String normalizeNanoBananaImageSize(String raw) {
        if (raw == null || raw.isBlank()) {
            return "2K";
        }
        String u = raw.strip().toUpperCase(Locale.ROOT);
        if ("1K".equals(u) || "2K".equals(u) || "4K".equals(u)) {
            return u;
        }
        return "2K";
    }

    /**
     * Nano Banana Pro 文生图：<code>POST /v1beta/models/gemini-3-pro-image-preview:generateContent</code>（仅 text
     * parts）。
     */
    public byte[] nanoBananaTextToImage(String prompt, String aspectRatio, String imageSize) {
        requireReady();
        String url =
                baseUrl()
                        + "/v1beta/models/"
                        + nanoBananaImageModelId()
                        + ":generateContent";
        ObjectNode root = objectMapper.createObjectNode();
        ArrayNode contents = root.putArray("contents");
        ObjectNode contentTurn = contents.addObject();
        ArrayNode parts = contentTurn.putArray("parts");
        parts.addObject().put("text", prompt == null ? "" : prompt);

        ObjectNode genCfg = root.putObject("generationConfig");
        putGemini3ImageFormatInGenerationConfig(genCfg, aspectRatio, imageSize);

        try {
            String raw =
                    apiYiRestClient
                            .post()
                            .uri(url)
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey())
                            .contentType(MediaType.APPLICATION_JSON)
                            .body(root.toString())
                            .retrieve()
                            .body(String.class);
            return bytesFromGeminiGenerateContentResponse(raw);
        } catch (RestClientResponseException e) {
            log.warn(
                    "APIYi Nano Banana 文生图失败 status={} body={}",
                    e.getStatusCode(),
                    truncate(e.getResponseBodyAsString()));
            throw new ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_GATEWAY, UserFacingMessages.NETWORK_TRY_LATER);
        }
    }

    /**
     * Nano Banana Pro 图片编辑：一个 text part + N 个 inlineData part（与谷歌契约一致，不可在同一 part 混 text 与
     * inlineData）。
     */
    public byte[] nanoBananaEditImages(
            String prompt, List<NanoBananaInlineImage> images, String aspectRatio, String imageSize) {
        requireReady();
        if (images == null || images.isEmpty()) {
            throw new ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST, "图片编辑至少需要一张参考图");
        }
        if (images.size() > 8) {
            throw new ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST, "参考图至多 8 张");
        }
        String url =
                baseUrl()
                        + "/v1beta/models/"
                        + nanoBananaImageModelId()
                        + ":generateContent";
        ObjectNode root = objectMapper.createObjectNode();
        ArrayNode contents = root.putArray("contents");
        ObjectNode contentTurn = contents.addObject();
        ArrayNode parts = contentTurn.putArray("parts");
        parts.addObject().put("text", prompt == null ? "" : prompt);
        for (NanoBananaInlineImage img : images) {
            if (img.bytes() == null || img.bytes().length == 0) {
                throw new ResponseStatusException(
                        org.springframework.http.HttpStatus.BAD_REQUEST, "参考图数据为空");
            }
            ObjectNode inlinePart = parts.addObject();
            ObjectNode inlineData = inlinePart.putObject("inlineData");
            inlineData.put("mimeType", normalizeNanoBananaMime(img.mimeType()));
            inlineData.put("data", Base64.getEncoder().encodeToString(img.bytes()));
        }

        ObjectNode genCfg = root.putObject("generationConfig");
        putGemini3ImageFormatInGenerationConfig(genCfg, aspectRatio, imageSize);

        try {
            String raw =
                    apiYiRestClient
                            .post()
                            .uri(url)
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey())
                            .contentType(MediaType.APPLICATION_JSON)
                            .body(root.toString())
                            .retrieve()
                            .body(String.class);
            return bytesFromGeminiGenerateContentResponse(raw);
        } catch (RestClientResponseException e) {
            log.warn(
                    "APIYi Nano Banana 图片编辑失败 status={} body={}",
                    e.getStatusCode(),
                    truncate(e.getResponseBodyAsString()));
            throw new ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_GATEWAY, UserFacingMessages.NETWORK_TRY_LATER);
        }
    }

    private static String normalizeNanoBananaMime(String mimeType) {
        if (mimeType == null || mimeType.isBlank()) {
            return "image/jpeg";
        }
        String m = mimeType.strip().toLowerCase(Locale.ROOT);
        if (m.contains("png")) {
            return "image/png";
        }
        return "image/jpeg";
    }

    /** 传入 Nano Banana <code>generateContent</code> 的多张参考图。 */
    public record NanoBananaInlineImage(String mimeType, byte[] bytes) {}

    /** Gemini imageConfig.aspectRatio，与前端比例键对齐。 */
    static String mapAspectToGeminiAspectRatio(String aspectKey) {
        if (aspectKey == null || aspectKey.isBlank()) {
            return "1:1";
        }
        return switch (aspectKey.strip()) {
            case "1:1" -> "1:1";
            case "16:9" -> "16:9";
            case "9:16" -> "9:16";
            case "4:3" -> "4:3";
            case "3:4" -> "3:4";
            case "3:2" -> "3:2";
            case "21:9" -> "21:9";
            default -> "1:1";
        };
    }

    private byte[] bytesFromGeminiGenerateContentResponse(String rawJson) {
        try {
            JsonNode root = objectMapper.readTree(rawJson);
            JsonNode candidates = root.path("candidates");
            if (!candidates.isArray() || candidates.isEmpty()) {
                log.warn("Gemini 响应无 candidates: {}", truncate(rawJson));
                throw new ResponseStatusException(
                        org.springframework.http.HttpStatus.BAD_GATEWAY, UserFacingMessages.NETWORK_TRY_LATER);
            }
            JsonNode parts = candidates.get(0).path("content").path("parts");
            if (!parts.isArray()) {
                log.warn("Gemini 响应无 content.parts: {}", truncate(rawJson));
                throw new ResponseStatusException(
                        org.springframework.http.HttpStatus.BAD_GATEWAY, UserFacingMessages.NETWORK_TRY_LATER);
            }
            for (JsonNode part : parts) {
                JsonNode inline = part.path("inlineData");
                if (inline.isMissingNode()) {
                    inline = part.path("inline_data");
                }
                if (!inline.isMissingNode() && inline.hasNonNull("data")) {
                    return decodeDataUrlOrBase64(inline.get("data").asText(""));
                }
            }
            log.warn("Gemini 响应 parts 中无 inlineData: {}", truncate(rawJson));
            throw new ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_GATEWAY, UserFacingMessages.NETWORK_TRY_LATER);
        } catch (IOException e) {
            log.warn("解析 Gemini JSON 失败", e);
            throw new ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_GATEWAY, UserFacingMessages.NETWORK_TRY_LATER);
        }
    }

    /**
     * 图像编辑（单图或多图）：<code>POST /v1/images/edits</code>，multipart。
     *
     * @param responseFormat {@code url} 或 {@code b64_json}
     */
    public byte[] editImages(
            List<byte[]> imageBytesList,
            List<String> filenames,
            List<String> contentTypes,
            String prompt,
            String responseFormat) {
        requireReady();
        if (imageBytesList == null || imageBytesList.isEmpty()) {
            throw new ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST, "图像编辑至少需要一张参考图");
        }
        if (filenames == null || filenames.size() != imageBytesList.size()) {
            throw new ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST, "文件名数量与图片不一致");
        }
        if (contentTypes == null || contentTypes.size() != imageBytesList.size()) {
            throw new ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST, "Content-Type 数量与图片不一致");
        }
        String fmt = resolveImageResponseFormat(responseFormat);
        String url = baseUrl() + "/v1/images/edits";

        MultiValueMap<String, Object> mp = new LinkedMultiValueMap<>();
        String mid = model();
        mp.add("model", mid);
        mp.add("prompt", prompt == null ? "" : prompt);
        mp.add("response_format", fmt);
        if (isGptImage2VipModel(mid)) {
            mp.add("size", "auto");
        }
        for (int i = 0; i < imageBytesList.size(); i++) {
            byte[] bytes = imageBytesList.get(i);
            String fn = filenames.get(i);
            String ct = contentTypes.get(i);
            MediaType mediaType =
                    ct != null && !ct.isBlank()
                            ? MediaType.parseMediaType(ct)
                            : MediaType.APPLICATION_OCTET_STREAM;
            ByteArrayResource resource =
                    new ByteArrayResource(bytes) {
                        @Override
                        public String getFilename() {
                            return fn != null && !fn.isBlank() ? fn : "image.png";
                        }

                        @Override
                        public long contentLength() {
                            return bytes.length;
                        }
                    };
            HttpHeaders partHeaders = new HttpHeaders();
            partHeaders.setContentType(mediaType);
            String dispName = fn != null && !fn.isBlank() ? fn : "image.png";
            partHeaders.setContentDispositionFormData("image", dispName);
            mp.add("image", new HttpEntity<>(resource, partHeaders));
        }

        try {
            String raw =
                    apiYiRestClient
                            .post()
                            .uri(url)
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey())
                            .contentType(MediaType.MULTIPART_FORM_DATA)
                            .body(mp)
                            .retrieve()
                            .body(String.class);
            return bytesFromGenerationsLikeResponse(raw);
        } catch (RestClientResponseException e) {
            log.warn("APIYi images/edits 失败 status={} body={}", e.getStatusCode(), e.getResponseBodyAsString());
            throw new ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_GATEWAY, UserFacingMessages.NETWORK_TRY_LATER);
        }
    }

    /**
     * 单图编辑 + 蒙版（OpenAI 兼容 <code>/v1/images/edits</code>）：透明像素表示需重绘区域。
     *
     * @param responseFormat {@code url} 或 {@code b64_json}
     */
    public byte[] editImageWithMask(
            byte[] imageBytes,
            String imageFilename,
            String imageContentType,
            byte[] maskPngBytes,
            String prompt,
            String responseFormat) {
        requireReady();
        if (imageBytes == null || imageBytes.length == 0) {
            throw new ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST, "图像编辑需要参考图");
        }
        if (maskPngBytes == null || maskPngBytes.length == 0) {
            throw new ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST, "请提供重绘区域蒙版（PNG）");
        }
        String fmt = resolveImageResponseFormat(responseFormat);
        String url = baseUrl() + "/v1/images/edits";

        MultiValueMap<String, Object> mp = new LinkedMultiValueMap<>();
        String mid = model();
        mp.add("model", mid);
        mp.add("prompt", prompt == null ? "" : prompt);
        mp.add("response_format", fmt);
        if (isGptImage2VipModel(mid)) {
            mp.add("size", "auto");
        }

        String imgFn = imageFilename != null && !imageFilename.isBlank() ? imageFilename : "image.png";
        MediaType imgMt =
                imageContentType != null && !imageContentType.isBlank()
                        ? MediaType.parseMediaType(imageContentType)
                        : MediaType.APPLICATION_OCTET_STREAM;
        ByteArrayResource imgRes =
                new ByteArrayResource(imageBytes) {
                    @Override
                    public String getFilename() {
                        return imgFn;
                    }

                    @Override
                    public long contentLength() {
                        return imageBytes.length;
                    }
                };
        HttpHeaders imgHeaders = new HttpHeaders();
        imgHeaders.setContentType(imgMt);
        imgHeaders.setContentDispositionFormData("image", imgFn);
        mp.add("image", new HttpEntity<>(imgRes, imgHeaders));

        ByteArrayResource maskRes =
                new ByteArrayResource(maskPngBytes) {
                    @Override
                    public String getFilename() {
                        return "mask.png";
                    }

                    @Override
                    public long contentLength() {
                        return maskPngBytes.length;
                    }
                };
        HttpHeaders maskHeaders = new HttpHeaders();
        maskHeaders.setContentType(MediaType.IMAGE_PNG);
        maskHeaders.setContentDispositionFormData("mask", "mask.png");
        mp.add("mask", new HttpEntity<>(maskRes, maskHeaders));

        try {
            String raw =
                    apiYiRestClient
                            .post()
                            .uri(url)
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey())
                            .contentType(MediaType.MULTIPART_FORM_DATA)
                            .body(mp)
                            .retrieve()
                            .body(String.class);
            return bytesFromGenerationsLikeResponse(raw);
        } catch (RestClientResponseException e) {
            log.warn("APIYi images/edits+mask 失败 status={} body={}", e.getStatusCode(), e.getResponseBodyAsString());
            throw new ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_GATEWAY, UserFacingMessages.NETWORK_TRY_LATER);
        }
    }

    /**
     * 对话式生成（用户文本 + 参考图 URL 或 data URL）：<code>POST /v1/chat/completions</code>。
     *
     * <p>解析响应中的 <code>data[0].url</code> / <code>b64_json</code>（与官方示例一致）。
     */
    public byte[] chatCompletionWithImage(String userText, String imageUrlOrDataUrl) {
        requireReady();
        String url = baseUrl() + "/v1/chat/completions";
        ObjectNode root = objectMapper.createObjectNode();
        root.put("model", model());
        ArrayNode messages = root.putArray("messages");
        ObjectNode userMsg = messages.addObject();
        userMsg.put("role", "user");
        ArrayNode content = userMsg.putArray("content");

        ObjectNode textPart = content.addObject();
        textPart.put("type", "text");
        textPart.put("text", userText == null ? "" : userText);

        ObjectNode imgPart = content.addObject();
        imgPart.put("type", "image_url");
        ObjectNode imageUrlObj = objectMapper.createObjectNode();
        imageUrlObj.put("url", imageUrlOrDataUrl == null ? "" : imageUrlOrDataUrl);
        imgPart.set("image_url", imageUrlObj);

        try {
            String raw =
                    apiYiRestClient
                            .post()
                            .uri(url)
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey())
                            .contentType(MediaType.APPLICATION_JSON)
                            .body(root.toString())
                            .retrieve()
                            .body(String.class);
            return bytesFromGenerationsLikeResponse(raw);
        } catch (RestClientResponseException e) {
            log.warn("APIYi chat/completions 失败 status={} body={}", e.getStatusCode(), e.getResponseBodyAsString());
            throw new ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_GATEWAY, UserFacingMessages.NETWORK_TRY_LATER);
        }
    }

    /**
     * Sora 2 官转：提交文生视频任务（{@code POST /v1/videos}，JSON）。
     *
     * @see <a href="https://docs.apiyi.com/api-capabilities/sora-2/overview">API易 · Sora 2</a>
     */
    public JsonNode sora2CreateVideoTask(Sora2SubmitRequest req) {
        requireReady();
        NormalizedSoraSubmit n = normalizeSoraSubmit(
                req.getPrompt(), req.getModel(), req.getSeconds(), req.getSize());

        ObjectNode root = objectMapper.createObjectNode();
        root.put("model", n.model());
        root.put("prompt", n.prompt());
        root.put("seconds", n.seconds());
        root.put("size", n.size());

        String apiUrl = baseUrl() + "/v1/videos";
        try {
            return apiYiRestClient
                    .post()
                    .uri(apiUrl)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(root.toString())
                    .retrieve()
                    .body(JsonNode.class);
        } catch (RestClientResponseException e) {
            log.warn(
                    "APIYi Sora2 POST /v1/videos status={} body={}",
                    e.getStatusCode(),
                    e.getResponseBodyAsString());
            throw upstreamToResponseStatus(e);
        }
    }

    /**
     * Sora 2 图生视频：{@code POST /v1/videos}，{@code multipart/form-data}，字段 {@code input_reference}。
     *
     * <p>参考图 MIME 须为 image/jpeg、image/png、image/webp；像素应与 {@code size} 一致（服务端对 ImageIO 可解码的图做校验）。
     */
    public JsonNode sora2CreateVideoTaskMultipart(
            String prompt,
            String model,
            String seconds,
            String size,
            byte[] inputReferenceBytes,
            String referenceFilename,
            String referenceContentType) {
        requireReady();
        if (inputReferenceBytes == null || inputReferenceBytes.length == 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "参考图内容为空");
        }
        NormalizedSoraSubmit n = normalizeSoraSubmit(prompt, model, seconds, size);
        assertInputReferenceMatchesSize(inputReferenceBytes, n.size());

        String fn =
                referenceFilename == null || referenceFilename.isBlank()
                        ? "reference.png"
                        : referenceFilename.strip();
        MediaType refMediaType = resolveSoraReferenceMediaType(referenceContentType);

        MultiValueMap<String, Object> mp = new LinkedMultiValueMap<>();
        mp.add("model", n.model());
        mp.add("prompt", n.prompt());
        mp.add("seconds", n.seconds());
        mp.add("size", n.size());

        ByteArrayResource resource =
                new ByteArrayResource(inputReferenceBytes) {
                    @Override
                    public String getFilename() {
                        return fn;
                    }

                    @Override
                    public long contentLength() {
                        return inputReferenceBytes.length;
                    }
                };
        HttpHeaders partHeaders = new HttpHeaders();
        partHeaders.setContentType(refMediaType);
        partHeaders.setContentDispositionFormData("input_reference", fn);
        mp.add("input_reference", new HttpEntity<>(resource, partHeaders));

        String apiUrl = baseUrl() + "/v1/videos";
        try {
            return apiYiRestClient
                    .post()
                    .uri(apiUrl)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey())
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(mp)
                    .retrieve()
                    .body(JsonNode.class);
        } catch (RestClientResponseException e) {
            log.warn(
                    "APIYi Sora2 multipart POST /v1/videos status={} body={}",
                    e.getStatusCode(),
                    e.getResponseBodyAsString());
            throw upstreamToResponseStatus(e);
        }
    }

    private record NormalizedSoraSubmit(String prompt, String model, String seconds, String size) {}

    private NormalizedSoraSubmit normalizeSoraSubmit(
            String prompt, String model, String seconds, String size) {
        String p = prompt == null ? "" : prompt.strip();
        if (p.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请先输入描述");
        }
        if (p.length() > 8000) {
            p = p.substring(0, 8000);
        }
        String m =
                model == null || model.isBlank()
                        ? "sora-2"
                        : model.strip().toLowerCase(Locale.ROOT);
        if (!"sora-2".equals(m) && !"sora-2-pro".equals(m)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "模型仅支持 sora-2 或 sora-2-pro");
        }
        String sec =
                seconds == null || seconds.isBlank()
                        ? "8"
                        : seconds.strip();
        if (!Set.of("4", "8", "12").contains(sec)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "时长仅支持 4、8、12 秒");
        }
        String sz =
                size == null || size.isBlank()
                        ? "1280x720"
                        : size.strip().toLowerCase(Locale.ROOT).replace('×', 'x');
        validateSoraSizeForModel(m, sz);
        return new NormalizedSoraSubmit(p, m, sec, sz);
    }

    private static MediaType resolveSoraReferenceMediaType(String contentType) {
        if (contentType == null || contentType.isBlank()) {
            return MediaType.IMAGE_PNG;
        }
        try {
            MediaType mt = MediaType.parseMediaType(contentType.strip());
            if (MediaType.IMAGE_JPEG.equals(mt)
                    || MediaType.IMAGE_PNG.equals(mt)
                    || new MediaType("image", "webp").equals(mt)) {
                return mt;
            }
        } catch (Exception ignored) {
        }
        return MediaType.IMAGE_PNG;
    }

    /** 若 ImageIO 能解码则校验宽高与 {@code size} 一致（WEBP 等可能跳过）。 */
    private void assertInputReferenceMatchesSize(byte[] refBytes, String size) {
        try {
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(refBytes));
            if (img == null) {
                log.debug("Sora2 input_reference：ImageIO 无法解码，跳过服务端像素校验（依赖上游）");
                return;
            }
            int[] wh = parseSoraSizeDimensions(size);
            if (img.getWidth() != wh[0] || img.getHeight() != wh[1]) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "参考图尺寸必须为 "
                                + wh[0]
                                + "×"
                                + wh[1]
                                + " 像素，当前为 "
                                + img.getWidth()
                                + "×"
                                + img.getHeight());
            }
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "无法读取参考图");
        }
    }

    private static int[] parseSoraSizeDimensions(String size) {
        String s = size.strip().toLowerCase(Locale.ROOT).replace('×', 'x');
        int x = s.indexOf('x');
        if (x <= 0 || x >= s.length() - 1) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "size 格式无效");
        }
        try {
            int w = Integer.parseInt(s.substring(0, x).strip());
            int h = Integer.parseInt(s.substring(x + 1).strip());
            if (w <= 0 || h <= 0) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "size 宽高无效");
            }
            return new int[] {w, h};
        } catch (NumberFormatException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "size 宽高无效");
        }
    }

    /** 查询任务状态（{@code GET /v1/videos/{id}}）。 */
    public JsonNode sora2RetrieveVideo(String videoId) {
        requireReady();
        if (videoId == null || videoId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "video_id 无效");
        }
        String enc = URLEncoder.encode(videoId.strip(), StandardCharsets.UTF_8);
        String apiUrl = baseUrl() + "/v1/videos/" + enc;
        try {
            return apiYiRestClient
                    .get()
                    .uri(apiUrl)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey())
                    .retrieve()
                    .body(JsonNode.class);
        } catch (RestClientResponseException e) {
            log.warn(
                    "APIYi Sora2 GET /v1/videos/{} status={} body={}",
                    videoId,
                    e.getStatusCode(),
                    e.getResponseBodyAsString());
            throw upstreamToResponseStatus(e);
        }
    }

    /** 下载已生成 MP4（{@code GET /v1/videos/{id}/content}}）。 */
    public byte[] sora2DownloadVideoContent(String videoId) {
        requireReady();
        if (videoId == null || videoId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "video_id 无效");
        }
        String enc = URLEncoder.encode(videoId.strip(), StandardCharsets.UTF_8);
        String apiUrl = baseUrl() + "/v1/videos/" + enc + "/content";
        try {
            HttpResponse<byte[]> resp =
                    downloadHttpClient.send(
                            HttpRequest.newBuilder()
                                    .uri(URI.create(apiUrl))
                                    .timeout(Duration.ofMinutes(15))
                                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey())
                                    .GET()
                                    .build(),
                            HttpResponse.BodyHandlers.ofByteArray());
            if (resp.statusCode() / 100 != 2 || resp.body() == null || resp.body().length == 0) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "下载 Sora 视频失败");
            }
            return resp.body();
        } catch (IOException e) {
            log.warn("APIYi Sora2 下载视频 IO 失败", e);
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "下载 Sora 视频失败");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "下载 Sora 视频中断");
        }
    }

    private static void validateSoraSizeForModel(String model, String size) {
        Set<String> p720 = Set.of("720x1280", "1280x720");
        Set<String> p1024 = Set.of("1024x1792", "1792x1024");
        Set<String> p1080 = Set.of("1080x1920", "1920x1080");
        if ("sora-2".equals(model)) {
            if (!p720.contains(size)) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST, "sora-2 仅支持 720x1280 或 1280x720");
            }
            return;
        }
        if (!p720.contains(size) && !p1024.contains(size) && !p1080.contains(size)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "sora-2-pro 分辨率不在允许列表（720p / 1024p / 1080p 横竖组合）");
        }
    }

    private static ResponseStatusException upstreamToResponseStatus(RestClientResponseException e) {
        int code = e.getStatusCode().value();
        HttpStatus st = HttpStatus.resolve(code);
        if (st == HttpStatus.BAD_REQUEST || st == HttpStatus.UNAUTHORIZED || st == HttpStatus.FORBIDDEN) {
            String body = e.getResponseBodyAsString();
            String hint =
                    body != null && body.length() > 280 ? body.substring(0, 280) + "…" : body;
            return new ResponseStatusException(st, hint != null && !hint.isBlank() ? hint : st.getReasonPhrase());
        }
        return new ResponseStatusException(
                HttpStatus.BAD_GATEWAY, UserFacingMessages.NETWORK_TRY_LATER);
    }

    private void requireReady() {
        if (!isReady()) {
            throw new ResponseStatusException(
                    org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
                    "高速线路未配置 APIYi：请设置 APIYI_API_KEY 或 UIGPT_APIYI_API_KEY；或 UIGPT_DOTENV_FILE 指向 .env");
        }
    }

    /**
     * API易 {@code gpt-image-2-vip} 文档「10 比例 × 1K/2K/4K」30 档；{@code tierToken} 为 {@code 1k}、{@code 2k}、{@code
     * 4k}；未知比例用 {@code auto}。
     */
    static String mapAspectToGptImage2VipSize(String aspectKey, String tierToken) {
        String k = aspectKey == null || aspectKey.isBlank() ? "1:1" : aspectKey.strip();
        String tier =
                tierToken == null || tierToken.isBlank()
                        ? "2k"
                        : tierToken.strip().toLowerCase(Locale.ROOT);
        if ("1k".equals(tier) || "fast".equals(tier)) {
            return switch (k) {
                case "1:1" -> "1280x1280";
                case "2:3" -> "848x1280";
                case "3:2" -> "1280x848";
                case "3:4" -> "960x1280";
                case "4:3" -> "1280x960";
                case "4:5" -> "1024x1280";
                case "5:4" -> "1280x1024";
                case "9:16" -> "720x1280";
                case "16:9" -> "1280x720";
                case "21:9" -> "1280x544";
                default -> "auto";
            };
        }
        if ("4k".equals(tier) || "detail".equals(tier)) {
            return switch (k) {
                case "1:1" -> "2880x2880";
                case "2:3" -> "2336x3520";
                case "3:2" -> "3520x2336";
                case "3:4" -> "2480x3312";
                case "4:3" -> "3312x2480";
                case "4:5" -> "2560x3216";
                case "5:4" -> "3216x2560";
                case "9:16" -> "2160x3840";
                case "16:9" -> "3840x2160";
                case "21:9" -> "3840x1632";
                default -> "auto";
            };
        }
        /* 2K Recommended */
        return switch (k) {
            case "1:1" -> "2048x2048";
            case "2:3" -> "1360x2048";
            case "3:2" -> "2048x1360";
            case "3:4" -> "1536x2048";
            case "4:3" -> "2048x1536";
            case "4:5" -> "1632x2048";
            case "5:4" -> "2048x1632";
            case "9:16" -> "1152x2048";
            case "16:9" -> "2048x1152";
            case "21:9" -> "2048x864";
            default -> "auto";
        };
    }

    /** {@code gpt-image-2-all} 等 OpenAI 兼容尺寸（非 VIP 30 档）。 */
    static String mapAspectToOpenAiImageSize(String aspectKey) {
        if (aspectKey == null || aspectKey.isBlank()) {
            return "1024x1024";
        }
        return switch (aspectKey.strip()) {
            case "9:16", "3:4" -> "1024x1792";
            case "16:9", "4:3", "21:9" -> "1792x1024";
            case "3:2" -> "1536x1024";
            case "1:1" -> "1024x1024";
            default -> "1024x1024";
        };
    }

    /**
     * 解析 <code>{"data":[{"url":"..."}]}</code> 或 <code>b64_json</code>（可为完整 data URL）。
     */
    byte[] bytesFromGenerationsLikeResponse(String rawJson) {
        try {
            JsonNode root = objectMapper.readTree(rawJson);
            JsonNode data = root.path("data");
            if (data.isArray() && !data.isEmpty()) {
                return imageEntryToBytes(data.get(0));
            }
            log.warn("APIYi 响应缺少 data[]: {}", truncate(rawJson));
            throw new ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_GATEWAY, UserFacingMessages.NETWORK_TRY_LATER);
        } catch (IOException e) {
            log.warn("解析 APIYi JSON 失败", e);
            throw new ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_GATEWAY, UserFacingMessages.NETWORK_TRY_LATER);
        }
    }

    private static String truncate(String s) {
        if (s == null) return "";
        return s.length() > 800 ? s.substring(0, 800) + "…" : s;
    }

    private byte[] imageEntryToBytes(JsonNode entry) {
        if (entry == null || entry.isMissingNode()) {
            throw new ResponseStatusException(org.springframework.http.HttpStatus.BAD_GATEWAY, "生成结果为空");
        }
        if (entry.hasNonNull("url")) {
            String u = entry.get("url").asText("");
            if (u.isBlank()) {
                throw new ResponseStatusException(org.springframework.http.HttpStatus.BAD_GATEWAY, "生成图 URL 为空");
            }
            return downloadUrl(u);
        }
        if (entry.hasNonNull("b64_json")) {
            return decodeDataUrlOrBase64(entry.get("b64_json").asText(""));
        }
        throw new ResponseStatusException(org.springframework.http.HttpStatus.BAD_GATEWAY, "响应中无 url 或 b64_json");
    }

    static byte[] decodeDataUrlOrBase64(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new ResponseStatusException(org.springframework.http.HttpStatus.BAD_GATEWAY, "图片 Base64 为空");
        }
        String s = raw.strip();
        int comma = s.indexOf(',');
        if (s.startsWith("data:") && comma > 0) {
            s = s.substring(comma + 1);
        }
        try {
            return Base64.getDecoder().decode(s.getBytes(StandardCharsets.US_ASCII));
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(org.springframework.http.HttpStatus.BAD_GATEWAY, "图片 Base64 解码失败");
        }
    }

    private byte[] downloadUrl(String url) {
        try {
            HttpResponse<byte[]> resp =
                    downloadHttpClient.send(
                            HttpRequest.newBuilder()
                                    .uri(URI.create(url))
                                    .timeout(Duration.ofMinutes(5))
                                    .GET()
                                    .build(),
                            HttpResponse.BodyHandlers.ofByteArray());
            if (resp.statusCode() / 100 != 2 || resp.body() == null || resp.body().length == 0) {
                throw new ResponseStatusException(org.springframework.http.HttpStatus.BAD_GATEWAY, "下载生成图失败");
            }
            return resp.body();
        } catch (IOException e) {
            throw new ResponseStatusException(org.springframework.http.HttpStatus.BAD_GATEWAY, "下载生成图失败");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(org.springframework.http.HttpStatus.BAD_GATEWAY, "下载生成图中断");
        }
    }

}
