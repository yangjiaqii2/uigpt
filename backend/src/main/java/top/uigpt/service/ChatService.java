package top.uigpt.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;
import top.uigpt.chat.FastFreeformModelFallback;
import top.uigpt.chat.FastFreeformModelIds;
import top.uigpt.UserFacingMessages;
import top.uigpt.config.AppProperties;
import top.uigpt.dto.ChatMessageDto;
import top.uigpt.dto.ChatRequest;
import top.uigpt.dto.ChatResponse;
import top.uigpt.entity.ChatModel;
import top.uigpt.repository.ChatModelRepository;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * 上游对话走 OpenAI 兼容协议：{@code POST {base}/chat/completions}（流式 SSE 或非流式 JSON）。
 *
 * <p>与 API易「OpenAI 官方库使用」一致：等价于将官方 SDK 的 {@code base_url} 设为 {@code https://api.apiyi.com/v1}
 *（密钥用 API易 Key）。本类用 JDK {@link HttpClient} 与 {@link RestClient} 直接组 JSON，便于统一追加 GPT‑5、Claude
 * {@code output_config}、Gemini {@code reasoning_effort} 等与网关约定字段；无需再引入第三方 Java OpenAI 封装即可对齐文档。
 */
@Slf4j
@Service
public class ChatService {

    /** 与模型对话时置于首条，约束助手以简体中文输出 */
    private static final String ZH_REPLY_SYSTEM_PROMPT =
            "请使用简体中文作答。除非用户明确要求使用其他语言，否则请全程以简体中文回复；代码、专有名词或必要引用可保留外文。";

    /** 末条用户含有效参考图时不再走识图预检；本集合仅影响无内联图时 {@link #buildVisionAppendix} 是否尝试摘要（当前仅 freeform）。 */
    private static final Set<String> VISION_PREFLIGHT_SKILL_IDS = Set.of("freeform");

    private static final int MAX_VISION_URL_CHARS_TOTAL = 7_000_000;

    private static final String FREEFORM_SKILL_ID = "freeform";

    /** 自由对话技能系统提示 */
    private static final String FREEFORM_SKILL_SYSTEM_PROMPT = FreeformPromptLoader.loadText();

    private static final int SESSION_MEMORY_MAX_OUT = 2000;

    private static final String MEMORY_MERGE_SYSTEM =
            "你是会话记忆整理助手。根据「旧记忆」和「本轮完整对话」（用户最后一轮提问 + 助手完整回复），"
                    + "输出更新后的会话记忆，供后续多轮对话引用。\n"
                    + "要求：\n"
                    + "1）只保留对后续对话有用的实体、偏好、约束、待办、结论、数字与专有名词；去掉寒暄与废话。\n"
                    + "2）用短句或条目，分号或换行分隔；总长度不超过800字。\n"
                    + "3）若本轮几乎没有可记住的新信息，只输出一个字：无\n"
                    + "4）不要复述整段对话，不要加引号或 Markdown。";

    private final AppProperties appProperties;
    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final ChatModelRepository chatModelRepository;
    private final ModelApiKeyCipherService modelApiKeyCipherService;
    private final ApiYiImageService apiYiImageService;
    private final HttpClient upstreamChatHttpClient;

    public ChatService(
            AppProperties appProperties,
            RestClient restClient,
            ObjectMapper objectMapper,
            ChatModelRepository chatModelRepository,
            ModelApiKeyCipherService modelApiKeyCipherService,
            ApiYiImageService apiYiImageService,
            @Qualifier("upstreamChatHttpClient") HttpClient upstreamChatHttpClient) {
        this.appProperties = appProperties;
        this.restClient = restClient;
        this.objectMapper = objectMapper;
        this.chatModelRepository = chatModelRepository;
        this.modelApiKeyCipherService = modelApiKeyCipherService;
        this.apiYiImageService = apiYiImageService;
        this.upstreamChatHttpClient = upstreamChatHttpClient;
    }

    private record ResolvedModelCall(String apiModelCode, String bearerApiKey, String baseUrl) {}

    /**
     * 每次请求从已启用 {@code chat_models} 中随机选一条；若无记录则用全局配置的 model 与 Key。
     */
    private ResolvedModelCall resolveUpstreamModel() {
        String fallbackKey = appProperties.getAi().getApiKey();
        String fallbackModel = appProperties.getAi().getModel();
        List<ChatModel> enabled = chatModelRepository.findByEnabledTrueOrderBySortOrderAscIdAsc();
        if (enabled.isEmpty()) {
            return new ResolvedModelCall(fallbackModel, fallbackKey, null);
        }
        ChatModel row = enabled.get(ThreadLocalRandom.current().nextInt(enabled.size()));
        return resolveFromChatModelRow(row, fallbackKey);
    }

    private ResolvedModelCall resolveFromChatModelRow(ChatModel row, String fallbackKey) {
        String code = row.getApiModelCode();
        String key = fallbackKey;
        String cipher = row.getApiKeyCipher();
        if (cipher != null && !cipher.isBlank()) {
            if (!modelApiKeyCipherService.isMasterConfigured()) {
                log.warn("模型 id={} 配置了加密 Key 但未配置 UIGPT_MODEL_KEY_MASTER", row.getId());
                throw new ResponseStatusException(
                        HttpStatus.SERVICE_UNAVAILABLE, UserFacingMessages.NETWORK_TRY_LATER);
            }
            try {
                key = modelApiKeyCipherService.decryptStored(cipher.trim());
            } catch (RuntimeException e) {
                log.warn("模型 API Key 解密失败 modelId={}", row.getId(), e);
                throw new ResponseStatusException(
                        HttpStatus.SERVICE_UNAVAILABLE, UserFacingMessages.NETWORK_TRY_LATER);
            }
        }
        String bu = row.getBaseUrl();
        if (bu != null && !bu.isBlank()) {
            bu = bu.strip().replaceAll("/+$", "");
        } else {
            bu = null;
        }
        return new ResolvedModelCall(code, key, bu);
    }

    /**
     * 自由对话：优先使用 API易密钥与允许列表内的 model（{@link FastFreeformModelIds}）；访客固定默认模型。
     */
    private Optional<ResolvedModelCall> tryResolveFastFreeform(ChatRequest req, String username) {
        String skill = req.getSkillId();
        if (skill != null
                && !skill.isBlank()
                && !FREEFORM_SKILL_ID.equalsIgnoreCase(skill.strip())) {
            return Optional.empty();
        }
        var cfg = appProperties.getApiYiImage();
        String key = cfg.getApiKey();
        if (key == null || key.isBlank()) {
            return Optional.empty();
        }
        boolean deep = Boolean.TRUE.equals(req.getDeepReasoning());
        String mid = deep ? "gpt-5.4-pro" : "gpt-5";
        if (username != null && !username.isBlank()) {
            String reqMid = req.getFastFreeformModel();
            if (reqMid != null && !reqMid.isBlank()) {
                String stripped = reqMid.strip();
                if (FastFreeformModelIds.isAllowed(stripped)) {
                    mid = stripped;
                }
            }
        }
        String root = cfg.getBaseUrl().strip().replaceAll("/+$", "");
        if (!root.endsWith("/v1")) {
            root = root + "/v1";
        }
        return Optional.of(new ResolvedModelCall(mid, key, root));
    }

    private ResolvedModelCall resolveModelForChat(ChatRequest req, String username) {
        return tryResolveFastFreeform(req, username).orElseGet(this::resolveUpstreamModel);
    }

    /** 与 {@link #streamChat}、{@link #callOpenAiCompatible} 一致：空则用全局 {@code uigpt.ai.base-url} */
    private String chatCompletionsEndpoint(String resolvedBaseUrlOverride) {
        String root =
                resolvedBaseUrlOverride != null && !resolvedBaseUrlOverride.isBlank()
                        ? resolvedBaseUrlOverride.strip().replaceAll("/+$", "")
                        : appProperties.getAi().getBaseUrl().strip().replaceAll("/+$", "");
        return root + "/chat/completions";
    }

    /**
     * GPT-5 系列（API易）：{@code temperature} 须为 1；用 {@code max_completion_tokens} 替代 {@code max_tokens}；勿传
     * {@code top_p}。其它模型沿用 {@code max_tokens}。
     */
    private void putModelAndTokenLimits(
            ObjectNode body, String apiModelCode, int maxOut, Boolean stream) {
        String model =
                apiModelCode != null && !apiModelCode.isBlank()
                        ? apiModelCode.strip()
                        : appProperties.getAi().getModel().strip();
        if (model.isEmpty()) {
            throw new IllegalArgumentException("model");
        }
        body.put("model", model);
        if (stream != null) {
            body.put("stream", stream);
        }
        if (isGpt5SeriesModel(model)) {
            body.put("max_completion_tokens", maxOut);
            body.put("temperature", 1.0);
        } else {
            body.put("max_tokens", maxOut);
        }
    }

    private static boolean isGpt5SeriesModel(String model) {
        return model.toLowerCase(java.util.Locale.ROOT).startsWith("gpt-5");
    }

    /**
     * API易 OpenAI 兼容 {@code /v1/chat/completions}：Claude 可传 {@code output_config.effort}
     *（low/medium/high），与原生 {@code /v1/messages} 行为对齐；非 Claude 模型忽略。
     */
    private static void putClaudeOutputConfigIfApplicable(ObjectNode body, String apiModelCode) {
        if (apiModelCode == null || apiModelCode.isBlank()) {
            return;
        }
        String m = apiModelCode.strip();
        if (!m.toLowerCase(java.util.Locale.ROOT).startsWith("claude-")) {
            return;
        }
        String effort = resolveClaudeEffortForApiYi(m);
        body.putObject("output_config").put("effort", effort);
    }

    /** thinking / opus → high；haiku → low；其余 medium */
    private static String resolveClaudeEffortForApiYi(String modelId) {
        String lower = modelId.toLowerCase(java.util.Locale.ROOT);
        if (lower.contains("thinking")) {
            return "high";
        }
        if (lower.contains("opus")) {
            return "high";
        }
        if (lower.contains("haiku")) {
            return "low";
        }
        return "medium";
    }

    /**
     * API易 OpenAI 兼容 {@code /v1/chat/completions}：Gemini 可用 {@code reasoning_effort}
     *（{@code low}/{@code medium}/{@code high}），与原生格式 {@code thinking_budget} 档位相对应；非 Gemini 忽略。
     *
     * @see <a href="https://docs.apiyi.com">API易文档 · Gemini 与 OpenAI 兼容对比</a>
     */
    private static void putGeminiReasoningEffortIfApplicable(ObjectNode body, String apiModelCode) {
        if (apiModelCode == null || apiModelCode.isBlank()) {
            return;
        }
        String lower = apiModelCode.strip().toLowerCase(java.util.Locale.ROOT);
        if (!lower.startsWith("gemini-")) {
            return;
        }
        body.put("reasoning_effort", resolveGeminiReasoningEffortForApiYi(lower));
    }

    /**
     * {@code nothinking} → low（快速）；显式 {@code thinking} → high；{@code pro} 系列 → high；{@code flash} →
     * medium；其余 medium。
     */
    private static String resolveGeminiReasoningEffortForApiYi(String lowerModelId) {
        if (lowerModelId.contains("nothinking")) {
            return "low";
        }
        if (lowerModelId.contains("thinking")) {
            return "high";
        }
        if (lowerModelId.contains("-pro") || lowerModelId.contains("pro-preview")) {
            return "high";
        }
        if (lowerModelId.contains("flash")) {
            return "medium";
        }
        return "medium";
    }

    /**
     * @param embedVisionImages {@code true} 时，用户消息中带参考图的条目按 OpenAI 多模态 {@code content[]} 序列化；否则仅文本（历史行为）。
     */
    /**
     * @param passthroughPlainMessages 透传模式下不对纯图占位文案做服务端改写（仅用空格满足部分网关非空要求）
     */
    private void putMessagesArray(
            ObjectNode body,
            List<ChatMessageDto> messages,
            boolean embedVisionImages,
            boolean passthroughPlainMessages) {
        var arr = body.putArray("messages");
        for (ChatMessageDto m : messages) {
            ObjectNode o = arr.addObject();
            o.put("role", m.getRole().toLowerCase());
            if (embedVisionImages
                    && m.getRole() != null
                    && "user".equalsIgnoreCase(m.getRole())) {
                List<String> urls = collectVisionImageUrlsFromMessage(m);
                if (!urls.isEmpty()) {
                    ArrayNode content = o.putArray("content");
                    ObjectNode textPart = content.addObject();
                    textPart.put("type", "text");
                    String txt = m.getContent() == null ? "" : m.getContent();
                    textPart.put(
                            "text",
                            txt.isBlank()
                                    ? (passthroughPlainMessages
                                            ? " "
                                            : "（用户上传了参考图，请结合图片与上下文作答。）")
                                    : txt);
                    for (String u : urls) {
                        ObjectNode imgPart = content.addObject();
                        imgPart.put("type", "image_url");
                        ObjectNode imageUrlObj = objectMapper.createObjectNode();
                        imageUrlObj.put("url", u);
                        imgPart.set("image_url", imageUrlObj);
                    }
                    continue;
                }
            }
            o.put("content", m.getContent());
        }
    }

    /**
     * 在发往上游的消息列表首部注入「简体中文回复」系统提示；可选并入前端技能卡片上下文（不落库到用户消息）。
     */
    private List<ChatMessageDto> withZhReplySystemPrompt(
            List<ChatMessageDto> original, String skillContext) {
        if (original == null || original.isEmpty()) {
            return original;
        }
        List<ChatMessageDto> out = new ArrayList<>(original.size() + 1);
        ChatMessageDto sys = new ChatMessageDto();
        sys.setRole("system");
        String content = ZH_REPLY_SYSTEM_PROMPT + "\n\n" + FREEFORM_SKILL_SYSTEM_PROMPT;
        if (skillContext != null && !skillContext.isBlank()) {
            content =
                    content
                            + "\n\n【本轮界面技能上下文】"
                            + skillContext.trim()
                            + "\n请在本轮回复中遵循上述语境；若与用户最新消息冲突，以用户消息为准。";
        }
        sys.setContent(content);
        out.add(sys);
        out.addAll(original);
        return out;
    }

    /**
     * 解析 OpenAI 兼容 SSE 片段中的正文增量：{@code delta.content} 可能为字符串或（多模态）数组；少数网关误把增量放在
     * {@code message.content}。
     */
    private static String streamingDeltaText(JsonNode container) {
        if (container == null || container.isMissingNode()) {
            return "";
        }
        String fromContent = extractStreamingContentPiece(container.path("content"));
        if (!fromContent.isEmpty()) {
            return fromContent;
        }
        return "";
    }

    private static String extractStreamingContentPiece(JsonNode contentNode) {
        if (contentNode == null || contentNode.isMissingNode()) {
            return "";
        }
        if (contentNode.isTextual()) {
            return contentNode.asText("");
        }
        if (contentNode.isArray()) {
            StringBuilder sb = new StringBuilder();
            for (JsonNode part : contentNode) {
                if (part == null || part.isNull()) {
                    continue;
                }
                if (part.has("text")) {
                    sb.append(part.path("text").asText(""));
                } else if (part.isTextual()) {
                    sb.append(part.asText(""));
                }
            }
            return sb.toString();
        }
        return "";
    }

    /**
     * OpenAI 兼容流式接口；按增量调用 {@code onDelta}，返回完整助手文本。
     *
     * <p>API易自由对话：按模型族优先最强档位依次尝试；连接失败或网关类错误时自动降级下一候选，已向前端输出过正文则不再降级。
     *
     * @param allowVision 已登录用户为 {@code true} 且末条用户消息含参考图时：始终将图片以内联多模态并入主模型请求，不再先调用识图
     *     摘要接口（避免两次上游调用拖慢首包）。
     */
    public String streamChat(
            ChatRequest request, Consumer<String> onDelta, boolean allowVision, String username) {
        boolean passthrough = appProperties.getChat().isPassthrough();
        boolean inlineVision = !passthrough && useVisionInlineMultimodal(request, allowVision);
        ChatRequest req =
                passthrough
                        ? request
                        : (inlineVision ? request : withVisionPreflight(request, allowVision));
        ResolvedModelCall rm = resolveModelForChat(req, username);
        String apiKey = rm.bearerApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            log.warn("流式对话被拒绝：未配置模型接口密钥");
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE, UserFacingMessages.NETWORK_TRY_LATER);
        }
        List<ChatMessageDto> messagesForUpstream =
                passthrough
                        ? req.getMessages()
                        : withZhReplySystemPrompt(req.getMessages(), req.getSkillContext());
        boolean embedVision =
                passthrough
                        ? messageThreadHasVisionImages(req.getMessages())
                        : inlineVision;
        boolean deepReasoning = Boolean.TRUE.equals(req.getDeepReasoning());
        List<String> candidates = chatModelCandidates(rm.apiModelCode(), deepReasoning, passthrough);
        AtomicBoolean anyDeltaSent = new AtomicBoolean(false);
        ResponseStatusException lastRecoverable = null;
        for (int ci = 0; ci < candidates.size(); ci++) {
            String apiModelCode = candidates.get(ci);
            try {
                return streamChatSingleModel(
                        rm,
                        apiModelCode,
                        messagesForUpstream,
                        anyDeltaSent,
                        onDelta,
                        embedVision,
                        passthrough);
            } catch (StreamSendInterrupted e) {
                Thread.currentThread().interrupt();
                log.warn(
                        "流式连接阶段线程被中断（常见于异步请求超时或客户端中止），不再转为 ResponseStatusException 以免 SSE 全局协商失败");
                throw new IllegalStateException(UserFacingMessages.NETWORK_TRY_LATER, e);
            } catch (ResponseStatusException ex) {
                if (anyDeltaSent.get()) {
                    throw ex;
                }
                if (!isRecoverableModelFailure(ex)) {
                    throw ex;
                }
                boolean more = ci < candidates.size() - 1;
                if (more) {
                    log.warn(
                            "流式模型 {} 不可用（{}），降级尝试下一候选",
                            apiModelCode,
                            ex.getReason());
                } else {
                    log.warn("流式模型 {} 不可用（{}），候选已用尽", apiModelCode, ex.getReason());
                }
                lastRecoverable = ex;
            }
        }
        if (lastRecoverable != null) {
            throw lastRecoverable;
        }
        throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, UserFacingMessages.NETWORK_TRY_LATER);
    }

    /** HTTP 发送阶段被中断，不参与降级重试 */
    private static final class StreamSendInterrupted extends RuntimeException {
        StreamSendInterrupted(InterruptedException cause) {
            super(cause);
        }
    }

    private String streamChatSingleModel(
            ResolvedModelCall rm,
            String apiModelCode,
            List<ChatMessageDto> messagesForUpstream,
            AtomicBoolean anyDeltaSent,
            Consumer<String> onDelta,
            boolean embedVisionImages,
            boolean passthroughUpstream) {
        String url = chatCompletionsEndpoint(rm.baseUrl());
        ObjectNode body = objectMapper.createObjectNode();
        int maxOut = Math.max(1, appProperties.getAi().getMaxOutputTokens());
        putModelAndTokenLimits(body, apiModelCode, maxOut, true);
        if (!passthroughUpstream) {
            putClaudeOutputConfigIfApplicable(body, apiModelCode);
            putGeminiReasoningEffortIfApplicable(body, apiModelCode);
        }
        putMessagesArray(body, messagesForUpstream, embedVisionImages, passthroughUpstream);
        String apiKey = rm.bearerApiKey();

        HttpRequest httpReq =
                HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .timeout(Duration.ofMinutes(10))
                        .header("Authorization", "Bearer " + apiKey)
                        .header("Content-Type", "application/json")
                        .header("Accept", "text/event-stream")
                        .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                        .build();

        HttpResponse<InputStream> resp;
        try {
            resp = upstreamChatHttpClient.send(httpReq, HttpResponse.BodyHandlers.ofInputStream());
        } catch (IOException e) {
            log.warn("模型流式连接失败 model={}", apiModelCode, e);
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, UserFacingMessages.NETWORK_TRY_LATER);
        } catch (InterruptedException e) {
            throw new StreamSendInterrupted(e);
        }

        if (resp.statusCode() != 200) {
            try (InputStream errStream = resp.body()) {
                String errBody = new String(errStream.readAllBytes(), StandardCharsets.UTF_8);
                log.warn("模型流式请求失败 model={} status={} body={}", apiModelCode, resp.statusCode(), errBody);
            } catch (IOException e) {
                log.warn("读取模型错误响应失败", e);
            }
            throw upstreamChatFailure(resp.statusCode());
        }

        StringBuilder full = new StringBuilder();
        try (BufferedReader reader =
                new BufferedReader(
                        new InputStreamReader(resp.body(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty()) {
                    continue;
                }
                if (!line.startsWith("data:")) {
                    continue;
                }
                String data = line.substring(5).trim();
                if ("[DONE]".equals(data)) {
                    break;
                }
                JsonNode root;
                try {
                    root = objectMapper.readTree(data);
                } catch (Exception e) {
                    log.trace("跳过无法解析的 SSE 段: {}", line);
                    continue;
                }
                JsonNode choices = root.path("choices");
                if (!choices.isArray() || choices.isEmpty()) {
                    continue;
                }
                JsonNode firstChoice = choices.get(0);
                String piece = streamingDeltaText(firstChoice.path("delta"));
                if (piece.isEmpty()) {
                    piece = streamingDeltaText(firstChoice.path("message"));
                }
                if (!piece.isEmpty()) {
                    full.append(piece);
                    anyDeltaSent.set(true);
                    onDelta.accept(piece);
                }
            }
        } catch (IOException e) {
            if (isInterruptedOrCancelDuringStreamRead(e)) {
                log.debug(
                        "流式读取因中断结束（多为客户端取消或连接断开），已输出字符数={}",
                        full.length());
                return full.toString();
            }
            log.warn("读取模型流中断 model={}", apiModelCode, e);
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, UserFacingMessages.NETWORK_TRY_LATER);
        }
        return full.toString();
    }

    /** 429/502/503/504 等可尝试换模型降级；其它状态立即返回给调用方 */
    private static boolean isRecoverableModelFailure(ResponseStatusException ex) {
        int v = ex.getStatusCode().value();
        return v == HttpStatus.BAD_GATEWAY.value()
                || v == HttpStatus.SERVICE_UNAVAILABLE.value()
                || v == HttpStatus.GATEWAY_TIMEOUT.value()
                || v == HttpStatus.TOO_MANY_REQUESTS.value();
    }

    /**
     * 透传模式下仍希望对「API易白名单型号」保留族内降级；自定义 chat_models 路由则保持单模型以免偏离运维配置。
     */
    private static List<String> chatModelCandidates(
            String resolvedApiModelCode, boolean deepReasoning, boolean passthrough) {
        String code = resolvedApiModelCode != null ? resolvedApiModelCode.strip() : "";
        if (passthrough && !FastFreeformModelIds.isAllowed(code)) {
            return List.of(code);
        }
        return FastFreeformModelFallback.orderedCandidates(code, deepReasoning);
    }

    private static ResponseStatusException upstreamChatFailure(int httpStatus) {
        if (httpStatus == HttpStatus.SERVICE_UNAVAILABLE.value()) {
            return new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE, UserFacingMessages.UPSTREAM_MODEL_UNAVAILABLE);
        }
        if (httpStatus == HttpStatus.TOO_MANY_REQUESTS.value()) {
            return new ResponseStatusException(
                    HttpStatus.TOO_MANY_REQUESTS, UserFacingMessages.UPSTREAM_MODEL_UNAVAILABLE);
        }
        return new ResponseStatusException(HttpStatus.BAD_GATEWAY, UserFacingMessages.NETWORK_TRY_LATER);
    }

    /**
     * 判断读取 SSE 时的 IOException 是否由线程中断引起（通常表示用户中止或浏览器断开，而非上游质量问题）。
     */
    private static boolean isInterruptedOrCancelDuringStreamRead(IOException e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof InterruptedException) {
                return true;
            }
        }
        return false;
    }

    /**
     * 登录用户上传参考图时：调用 API易视觉模型生成摘要，追加到 skillContext（不改变消息落库内容）。
     *
     * @param allowVision 访客应为 {@code false}，避免未经鉴权消耗识图额度。
     */
    public ChatRequest withVisionPreflight(ChatRequest request, boolean allowVision) {
        if (!allowVision || request == null) {
            return request;
        }
        try {
            String appendix = buildVisionAppendix(request);
            if (appendix == null || appendix.isBlank()) {
                return request;
            }
            ChatRequest out = copyChatRequestShallow(request);
            String sc = out.getSkillContext();
            String block = "【参考图·API易视觉模型摘要】\n" + appendix;
            out.setSkillContext(sc == null || sc.isBlank() ? block : sc + "\n\n" + block);
            return out;
        } catch (Exception e) {
            log.warn("识图预分析跳过: {}", e.getMessage());
            return request;
        }
    }

    private static ChatRequest copyChatRequestShallow(ChatRequest in) {
        ChatRequest out = new ChatRequest();
        out.setConversationId(in.getConversationId());
        out.setSkillContext(in.getSkillContext());
        out.setSkillId(in.getSkillId());
        out.setTierMode(in.getTierMode());
        out.setFastFreeformModel(in.getFastFreeformModel());
        out.setDeepReasoning(in.getDeepReasoning());
        out.setUseRag(in.getUseRag());
        out.setRagCollection(in.getRagCollection());
        out.setMessages(in.getMessages());
        return out;
    }

    /**
     * 末条用户消息含有效参考图 URL 时，将图片直接并入主模型多模态请求，不再先走 {@link #withVisionPreflight}（识图摘要 +
     * 主对话两次调用）。
     */
    private boolean useVisionInlineMultimodal(ChatRequest request, boolean allowVision) {
        if (!allowVision || request == null) {
            return false;
        }
        ChatMessageDto lastUser = findLastUserMessage(request.getMessages());
        return !collectVisionImageUrlsFromMessage(lastUser).isEmpty();
    }

    /** 从单条用户消息提取用于视觉的 URL（与识图预检规则一致）。 */
    private boolean messageThreadHasVisionImages(List<ChatMessageDto> messages) {
        if (messages == null) {
            return false;
        }
        for (ChatMessageDto m : messages) {
            if (!collectVisionImageUrlsFromMessage(m).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private List<String> collectVisionImageUrlsFromMessage(ChatMessageDto message) {
        if (message == null || message.getImages() == null || message.getImages().isEmpty()) {
            return List.of();
        }
        List<String> urls = new ArrayList<>();
        int totalLen = 0;
        for (var part : message.getImages()) {
            if (part == null || part.getUrl() == null) {
                continue;
            }
            String u = part.getUrl().strip();
            if (!isAllowedVisionImageUrl(u)) {
                continue;
            }
            totalLen += u.length();
            if (totalLen > MAX_VISION_URL_CHARS_TOTAL) {
                log.warn("参考图 payload 过大，跳过视觉字段");
                return List.of();
            }
            urls.add(u);
            if (urls.size() >= 4) {
                break;
            }
        }
        return urls.isEmpty() ? List.of() : urls;
    }

    private String buildVisionAppendix(ChatRequest request) {
        String skillId = request.getSkillId();
        if (skillId == null || skillId.isBlank()) {
            return null;
        }
        String sid = skillId.strip();
        if (!VISION_PREFLIGHT_SKILL_IDS.contains(sid)) {
            return null;
        }
        if (!apiYiImageService.isVisionReady()) {
            return null;
        }
        ChatMessageDto lastUser = findLastUserMessage(request.getMessages());
        List<String> urls = collectVisionImageUrlsFromMessage(lastUser);
        if (urls.isEmpty()) {
            return null;
        }
        String instruction = visionInstructionForUserText(lastUser.getContent());
        String analyzed = apiYiImageService.visionChatAnalyze(instruction, urls);
        if (analyzed.length() > 8000) {
            return analyzed.substring(0, 8000) + "…";
        }
        return analyzed;
    }

    private static ChatMessageDto findLastUserMessage(List<ChatMessageDto> messages) {
        if (messages == null) {
            return null;
        }
        for (int i = messages.size() - 1; i >= 0; i--) {
            ChatMessageDto m = messages.get(i);
            if (m.getRole() != null && "user".equalsIgnoreCase(m.getRole())) {
                return m;
            }
        }
        return null;
    }

    private static boolean isAllowedVisionImageUrl(String u) {
        return u.startsWith("data:image/") || u.startsWith("https://");
    }

    private static String visionInstructionForUserText(String userText) {
        String ut = userText == null ? "" : userText.strip();
        if (ut.length() > 4000) {
            ut = ut.substring(0, 4000) + "…";
        }
        return "请用简体中文客观描述配图要点，并结合用户配文。\n\n用户配文：\n" + ut;
    }

    /**
     * 合并更新会话记忆文本（供持久化）。失败时由调用方捕获，不影响主对话。
     */
    public String mergeSessionMemorySummary(
            String previousMemory, String userMessage, String assistantReply) {
        String apiKey = appProperties.getAi().getApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            return previousMemory != null ? previousMemory : "";
        }
        ChatMessageDto sys = new ChatMessageDto();
        sys.setRole("system");
        sys.setContent(MEMORY_MERGE_SYSTEM);
        ChatMessageDto user = new ChatMessageDto();
        user.setRole("user");
        user.setContent(
                "旧记忆：\n"
                        + (previousMemory == null || previousMemory.isBlank() ? "（无）" : previousMemory)
                        + "\n\n本轮用户：\n"
                        + (userMessage == null ? "" : userMessage)
                        + "\n\n本轮助手：\n"
                        + (assistantReply == null ? "" : assistantReply));
        try {
            ChatResponse r =
                    callOpenAiCompatible(
                            List.of(sys, user),
                            apiKey,
                            512,
                            appProperties.getAi().getModel());
            String out = r.getReply() != null ? r.getReply().strip() : "";
            if (out.isEmpty() || "无".equals(out) || "none".equalsIgnoreCase(out)) {
                return previousMemory != null ? previousMemory : "";
            }
            if (out.length() > SESSION_MEMORY_MAX_OUT) {
                return out.substring(0, SESSION_MEMORY_MAX_OUT) + "…";
            }
            return out;
        } catch (Exception e) {
            log.warn("合并会话记忆调用模型失败，保留旧记忆", e);
            return previousMemory != null ? previousMemory : "";
        }
    }

    public ChatResponse chat(ChatRequest request) {
        return chat(request, false, null);
    }

    /**
     * @param allowVision 已登录用户传 {@code true} 时与 {@link #streamChat} 一致：含图则内联多模态，不再先识图摘要
     * @param username 登录用户名；访客传 {@code null}
     */
    public ChatResponse chat(ChatRequest request, boolean allowVision, String username) {
        boolean passthrough = appProperties.getChat().isPassthrough();
        boolean inlineVision = !passthrough && useVisionInlineMultimodal(request, allowVision);
        ChatRequest req =
                passthrough
                        ? request
                        : (inlineVision ? request : withVisionPreflight(request, allowVision));
        ResolvedModelCall rm = resolveModelForChat(req, username);
        String apiKey = rm.bearerApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            log.warn("对话请求被拒绝：未配置模型接口密钥");
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE, UserFacingMessages.NETWORK_TRY_LATER);
        }
        List<ChatMessageDto> messages =
                passthrough
                        ? req.getMessages()
                        : withZhReplySystemPrompt(req.getMessages(), req.getSkillContext());
        boolean embedVision =
                passthrough
                        ? messageThreadHasVisionImages(req.getMessages())
                        : inlineVision;
        boolean deepReasoning = Boolean.TRUE.equals(req.getDeepReasoning());
        List<String> candidates = chatModelCandidates(rm.apiModelCode(), deepReasoning, passthrough);
        ResponseStatusException lastRecoverable = null;
        for (int ci = 0; ci < candidates.size(); ci++) {
            String code = candidates.get(ci);
            try {
                return callOpenAiCompatible(
                        messages, apiKey, null, code, rm.baseUrl(), embedVision, passthrough);
            } catch (ResponseStatusException ex) {
                if (!isRecoverableModelFailure(ex)) {
                    throw ex;
                }
                boolean more = ci < candidates.size() - 1;
                if (more) {
                    log.warn(
                            "非流式模型 {} 不可用（{}），降级尝试下一候选",
                            code,
                            ex.getReason());
                } else {
                    log.warn("非流式模型 {} 不可用（{}），候选已用尽", code, ex.getReason());
                }
                lastRecoverable = ex;
            }
        }
        if (lastRecoverable != null) {
            throw lastRecoverable;
        }
        throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, UserFacingMessages.NETWORK_TRY_LATER);
    }

    private ChatResponse callOpenAiCompatible(
            List<ChatMessageDto> messages,
            String apiKey,
            Integer maxTokens,
            String apiModelCode) {
        return callOpenAiCompatible(messages, apiKey, maxTokens, apiModelCode, null, false, false);
    }

    private ChatResponse callOpenAiCompatible(
            List<ChatMessageDto> messages,
            String apiKey,
            Integer maxTokens,
            String apiModelCode,
            String resolvedBaseUrlOverride,
            boolean embedVisionImages,
            boolean passthroughUpstream) {
        String url = chatCompletionsEndpoint(resolvedBaseUrlOverride);
        ObjectNode body = objectMapper.createObjectNode();
        int maxOut =
                maxTokens != null && maxTokens > 0
                        ? maxTokens
                        : Math.max(1, appProperties.getAi().getMaxOutputTokens());
        putModelAndTokenLimits(body, apiModelCode, maxOut, null);
        if (!passthroughUpstream) {
            putClaudeOutputConfigIfApplicable(body, apiModelCode);
            putGeminiReasoningEffortIfApplicable(body, apiModelCode);
        }
        putMessagesArray(body, messages, embedVisionImages, passthroughUpstream);
        try {
            JsonNode response = restClient.post()
                    .uri(url)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .header("Authorization", "Bearer " + apiKey)
                    .body(body.toString())
                    .retrieve()
                    .body(JsonNode.class);
            if (response == null) {
                log.warn("模型返回空响应");
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, UserFacingMessages.NETWORK_TRY_LATER);
            }
            JsonNode choices = response.path("choices");
            if (!choices.isArray() || choices.isEmpty()) {
                log.warn("模型响应缺少 choices: {}", response);
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, UserFacingMessages.NETWORK_TRY_LATER);
            }
            String content = choices.get(0).path("message").path("content").asText("");
            return new ChatResponse(content, null);
        } catch (RestClientResponseException e) {
            log.warn(
                    "模型请求失败 status={} body={}",
                    e.getStatusCode(),
                    e.getResponseBodyAsString());
            throw upstreamChatFailure(e.getStatusCode().value());
        }
    }
}
