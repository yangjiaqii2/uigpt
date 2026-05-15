package top.uigpt.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
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
import top.uigpt.config.UpstreamWebClientConfig;
import top.uigpt.chat.SseClientJsonEscapes;
import top.uigpt.chat.SseOpenAiDeltaParser;
import top.uigpt.chat.Utf8LineAssembler;
import top.uigpt.dto.ChatMessageDto;
import top.uigpt.dto.ChatRequest;
import top.uigpt.dto.ChatResponse;
import top.uigpt.dto.PreparedChatStreamContext;
import top.uigpt.entity.ChatModel;
import top.uigpt.repository.ChatModelRepository;

import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import reactor.core.Exceptions;
import reactor.core.publisher.Flux;
import reactor.core.publisher.SignalType;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
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
 *（密钥用 API易 Key）。本类用 {@link org.springframework.web.reactive.function.client.WebClient}（流式 SSE）与
 * {@link RestClient} 直接组 JSON，便于统一追加 GPT‑5、Claude
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
                    + "输出更新后的会话记忆，供本会话后续多轮对话引用。\n"
                    + "要求：\n"
                    + "1）只保留对后续对话有用的实体、偏好、约束、待办、结论、数字与专有名词；去掉寒暄与废话。\n"
                    + "2）用短句或条目，分号或换行分隔；总长度不超过800字。\n"
                    + "3）若本轮几乎没有可记住的新信息，只输出一个字：无\n"
                    + "4）不要复述整段对话，不要加引号或 Markdown。\n"
                    + "5）旧记忆与本轮内容均只来自同一聊天会话；不得引入其它会话、其它用户或站外臆测信息。";

    private final AppProperties appProperties;
    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final ChatModelRepository chatModelRepository;
    private final ModelApiKeyCipherService modelApiKeyCipherService;
    private final ApiYiImageService apiYiImageService;
    private final WebClient upstreamChatWebClient;

    public ChatService(
            AppProperties appProperties,
            RestClient restClient,
            ObjectMapper objectMapper,
            ChatModelRepository chatModelRepository,
            ModelApiKeyCipherService modelApiKeyCipherService,
            ApiYiImageService apiYiImageService,
            @Qualifier(UpstreamWebClientConfig.UPSTREAM_CHAT_WEB_CLIENT) WebClient upstreamChatWebClient) {
        this.appProperties = appProperties;
        this.restClient = restClient;
        this.objectMapper = objectMapper;
        this.chatModelRepository = chatModelRepository;
        this.modelApiKeyCipherService = modelApiKeyCipherService;
        this.apiYiImageService = apiYiImageService;
        this.upstreamChatWebClient = upstreamChatWebClient;
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
        if (username == null || username.isBlank()) {
            var g = appProperties.getGuestChat();
            String gk = g.getApiKey();
            if (gk != null && !gk.isBlank()) {
                String model =
                        g.getModel() != null && !g.getModel().isBlank()
                                ? g.getModel().strip()
                                : appProperties.getAi().getModel().strip();
                String base =
                        g.getBaseUrl() != null && !g.getBaseUrl().isBlank()
                                ? g.getBaseUrl().strip().replaceAll("/+$", "")
                                : appProperties.getAi().getBaseUrl().strip().replaceAll("/+$", "");
                return new ResolvedModelCall(model, gk.strip(), base);
            }
        }
        return tryResolveFastFreeform(req, username).orElseGet(this::resolveUpstreamModel);
    }

    /** 与 {@link #prepareStreamChat}、{@link #callOpenAiCompatible} 一致：空则用全局 {@code uigpt.ai.base-url} */
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
     * 流式对话：在发起上游 HTTP 前完成鉴权侧所需的一切解析（由 Controller 先扣积分 / 注入记忆后调用）。
     *
     * @param allowVision 已登录用户为 {@code true} 且末条用户消息含参考图时：内联多模态并入主模型请求。
     */
    public PreparedChatStreamContext prepareStreamChat(
            ChatRequest request, boolean allowVision, String username) {
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
        String url = chatCompletionsEndpoint(rm.baseUrl());
        List<String> bodies = new ArrayList<>(candidates.size());
        for (String apiModelCode : candidates) {
            bodies.add(
                    buildStreamRequestBodyJson(
                            apiModelCode, messagesForUpstream, embedVision, passthrough));
        }
        return new PreparedChatStreamContext(url, apiKey, List.copyOf(bodies));
    }

    private String buildStreamRequestBodyJson(
            String apiModelCode,
            List<ChatMessageDto> messagesForUpstream,
            boolean embedVisionImages,
            boolean passthroughUpstream) {
        ObjectNode body = objectMapper.createObjectNode();
        int maxOut = Math.max(1, appProperties.getAi().getMaxOutputTokens());
        putModelAndTokenLimits(body, apiModelCode, maxOut, true);
        if (!passthroughUpstream) {
            putClaudeOutputConfigIfApplicable(body, apiModelCode);
            putGeminiReasoningEffortIfApplicable(body, apiModelCode);
        }
        putMessagesArray(body, messagesForUpstream, embedVisionImages, passthroughUpstream);
        return body.toString();
    }

    /**
     * WebClient 订阅上游 SSE 并立即 {@link ResponseBodyEmitter#send(Object)} 下行；在专用线程中阻塞
     * {@code blockLast}（Tomcat 线程已返回）。
     *
     * @return 助手全文，供落库与会话记忆
     */
    public String forwardStreamToEmitter(
            PreparedChatStreamContext ctx,
            ResponseBodyEmitter emitter,
            AtomicBoolean anyDeltaSentRef)
            throws IOException {
        emitter.send(": uigpt-handshake\n\n");
        StringBuilder fullReply = new StringBuilder();
        AtomicBoolean anyDelta = new AtomicBoolean(false);
        ResponseStatusException lastRecoverable = null;
        for (int ci = 0; ci < ctx.streamRequestJsonBodies().size(); ci++) {
            String bodyJson = ctx.streamRequestJsonBodies().get(ci);
            try {
                consumeOneCandidateStream(
                        ctx,
                        bodyJson,
                        emitter,
                        piece -> {
                            anyDelta.set(true);
                            anyDeltaSentRef.set(true);
                            fullReply.append(piece);
                        });
                return fullReply.toString();
            } catch (ResponseStatusException ex) {
                if (anyDelta.get()) {
                    throw ex;
                }
                if (!isRecoverableModelFailure(ex)) {
                    throw ex;
                }
                boolean more = ci < ctx.streamRequestJsonBodies().size() - 1;
                if (more) {
                    log.warn("流式模型不可用（{}），降级尝试下一候选", ex.getReason());
                } else {
                    log.warn("流式模型不可用（{}），候选已用尽", ex.getReason());
                }
                lastRecoverable = ex;
            }
        }
        if (lastRecoverable != null) {
            throw lastRecoverable;
        }
        throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, UserFacingMessages.NETWORK_TRY_LATER);
    }

    private void consumeOneCandidateStream(
            PreparedChatStreamContext ctx,
            String bodyJson,
            ResponseBodyEmitter emitter,
            Consumer<String> onDeltaPiece)
            throws IOException {
        Utf8LineAssembler lineAsm = new Utf8LineAssembler();
        Flux<DataBuffer> flux =
                upstreamChatWebClient
                        .post()
                        .uri(URI.create(ctx.completionsUrl()))
                        .headers(
                                h -> {
                                    h.setBearerAuth(ctx.bearerApiKey());
                                    h.set(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE);
                                    h.set(HttpHeaders.ACCEPT, MediaType.TEXT_EVENT_STREAM_VALUE);
                                })
                        .bodyValue(bodyJson)
                        .exchangeToFlux(
                                clientResponse -> {
                                    int sc = clientResponse.statusCode().value();
                                    if (sc != 200) {
                                        return clientResponse
                                                .bodyToMono(String.class)
                                                .defaultIfEmpty("")
                                                .flatMapMany(
                                                        errBody -> {
                                                            log.warn(
                                                                    "模型流式请求失败 status={} body={}",
                                                                    sc,
                                                                    truncateForLog(errBody, 800));
                                                            return Flux.error(upstreamChatFailure(sc));
                                                        });
                                    }
                                    return clientResponse.bodyToFlux(DataBuffer.class);
                                });
        try {
            flux.doOnNext(
                            db -> {
                                int n = db.readableByteCount();
                                byte[] arr = new byte[n];
                                db.read(arr);
                                DataBufferUtils.release(db);
                                try {
                                    for (String line : lineAsm.feed(arr)) {
                                        processUpstreamSseLine(emitter, line, onDeltaPiece);
                                    }
                                } catch (IOException e) {
                                    throw new UncheckedIOException(e);
                                }
                            })
                    .doFinally(
                            sig -> {
                                if (sig != SignalType.ON_COMPLETE) {
                                    return;
                                }
                                String tail = lineAsm.flushRemainder();
                                if (tail != null && !tail.isEmpty()) {
                                    try {
                                        processUpstreamSseLine(emitter, tail, onDeltaPiece);
                                    } catch (IOException e) {
                                        throw new UncheckedIOException(e);
                                    }
                                }
                            })
                    .blockLast(Duration.ofMinutes(10));
        } catch (UncheckedIOException e) {
            Throwable c = e.getCause();
            if (c instanceof IOException io) {
                throw io;
            }
            throw e;
        } catch (RuntimeException e) {
            Throwable u = Exceptions.unwrap(e);
            if (u instanceof ResponseStatusException rse) {
                throw rse;
            }
            if (e instanceof ResponseStatusException rse) {
                throw rse;
            }
            log.warn("流式订阅异常", e);
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, UserFacingMessages.NETWORK_TRY_LATER);
        }
    }

    private static void processUpstreamSseLine(
            ResponseBodyEmitter emitter, String line, Consumer<String> onDeltaPiece) throws IOException {
        String t = line.stripLeading();
        if (t.isEmpty()) {
            return;
        }
        if (!t.startsWith("data:")) {
            return;
        }
        String data = t.substring(5).strip();
        if ("[DONE]".equals(data)) {
            return;
        }
        String piece = SseOpenAiDeltaParser.extractStreamText(data);
        if (!piece.isEmpty()) {
            emitter.send(SseClientJsonEscapes.sseDeltaEvent(piece));
            onDeltaPiece.accept(piece);
        }
    }

    private static String truncateForLog(String s, int max) {
        if (s == null || s.isEmpty()) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "…";
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
     * @param allowVision 已登录用户传 {@code true} 时与 {@link #prepareStreamChat} 一致：含图则内联多模态，不再先识图摘要
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
