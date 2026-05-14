package top.uigpt.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import top.uigpt.UserFacingMessages;
import top.uigpt.chat.SseClientJsonEscapes;
import top.uigpt.config.AsyncConfig;
import top.uigpt.dto.ChatMessageDto;
import top.uigpt.dto.ChatRequest;
import top.uigpt.dto.ChatResponse;
import top.uigpt.dto.PreparedChatStreamContext;
import top.uigpt.config.AppProperties;
import top.uigpt.entity.User;
import top.uigpt.repository.UserRepository;
import top.uigpt.service.ChatService;
import top.uigpt.service.ConversationService;
import top.uigpt.service.JwtService;
import top.uigpt.service.PointsService;

import org.springframework.dao.DataAccessException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class ChatController {

    private final ChatService chatService;
    private final JwtService jwtService;
    private final ConversationService conversationService;
    private final AppProperties appProperties;
    private final UserRepository userRepository;
    private final PointsService pointsService;

    @Qualifier(AsyncConfig.SSE_CHAT_FORWARD_EXECUTOR)
    private final Executor sseChatForwardExecutor;

    @PostMapping("/chat")
    public ChatResponse chat(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @Valid @RequestBody ChatRequest request) {
        String username = jwtService.parseUsername(authorization);
        validateGuestAndConversation(username, request);

        ChatRequest forModel =
                chatPassthrough() ? request : conversationService.injectSessionMemory(username, request);
        // 对话接口不注入向量知识库；RAG 仅用于文生图等模块（如 augmentPromptForImage）

        if (username == null) {
            ChatResponse ai = chatService.chat(forModel, false, null);
            return new ChatResponse(ai.getReply(), null);
        }

        long uid = userIdOrThrow(username);
        int cost = chatTurnCost(request);
        pointsService.assertAndDeduct(uid, cost, "chat_turn");
        boolean refundOnFail = true;
        try {
            ChatResponse ai = chatService.chat(forModel, true, username);
            refundOnFail = false;
            Long convId =
                    conversationService.syncAfterChat(
                            username, request.getConversationId(), request.getMessages(), ai.getReply());
            if (!chatPassthrough()) {
                conversationService.refreshSessionMemoryAfterTurn(
                        username, convId, lastUserMessage(request.getMessages()), ai.getReply());
            }
            return new ChatResponse(ai.getReply(), convId);
        } catch (Throwable t) {
            if (refundOnFail) {
                pointsService.refund(uid, cost, "chat_turn_refund");
            }
            throw t;
        }
    }

    /**
     * SSE：每条事件为 JSON，字段含义：<br>
     * {@code {"delta":"片段"}} 正文增量；{@code {"done":true,"conversationId":n|null}} 结束；{@code {"error":"…"}} 失败。
     */
    @PostMapping("/chat/stream")
    public ResponseEntity<ResponseBodyEmitter> streamChat(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @Valid @RequestBody ChatRequest request) {
        String username = jwtService.parseUsername(authorization);
        validateGuestAndConversation(username, request);

        final Long streamUserId = username != null ? userIdOrThrow(username) : null;
        final int streamCost = username != null ? chatTurnCost(request) : 0;
        if (streamUserId != null) {
            pointsService.assertAndDeduct(streamUserId, streamCost, "chat_stream_turn");
        }
        final AtomicBoolean streamFinishedOk = new AtomicBoolean(false);
        final AtomicBoolean anyDeltaSent = new AtomicBoolean(false);

        ChatRequest forModel =
                chatPassthrough() ? request : conversationService.injectSessionMemory(username, request);
        boolean allowVision = username != null;
        PreparedChatStreamContext prepared =
                chatService.prepareStreamChat(forModel, allowVision, username);

        ResponseBodyEmitter emitter = new ResponseBodyEmitter(0L);
        Runnable refundIfIncomplete =
                () -> {
                    if (streamUserId != null && !streamFinishedOk.get()) {
                        pointsService.refund(streamUserId, streamCost, "chat_stream_refund");
                    }
                };
        emitter.onCompletion(refundIfIncomplete);
        emitter.onTimeout(refundIfIncomplete);

        sseChatForwardExecutor.execute(
                () -> {
                    try {
                        String fullReply =
                                chatService.forwardStreamToEmitter(prepared, emitter, anyDeltaSent);
                        streamFinishedOk.set(true);
                        Long convId = null;
                        try {
                            if (username != null) {
                                convId =
                                        conversationService.syncAfterChat(
                                                username,
                                                request.getConversationId(),
                                                request.getMessages(),
                                                fullReply);
                                if (!chatPassthrough()) {
                                    conversationService.refreshSessionMemoryAfterTurn(
                                            username,
                                            convId,
                                            lastUserMessage(request.getMessages()),
                                            fullReply);
                                }
                            }
                        } catch (Exception db) {
                            log.warn("流式对话落库或记忆刷新失败（上游已完成）", db);
                        }
                        try {
                            emitter.send(SseClientJsonEscapes.sseDoneEvent(convId));
                        } catch (IOException e) {
                            log.warn("SSE done 写入失败", e);
                        }
                        emitter.complete();
                    } catch (VirtualMachineError e) {
                        throw e;
                    } catch (Throwable e) {
                        log.warn("流式对话失败", e);
                        try {
                            emitter.send(
                                    SseClientJsonEscapes.sseErrorEvent(resolveStreamErrorMessage(e)));
                        } catch (IOException ignored) {
                        }
                        emitter.completeWithError(
                                e instanceof Exception ? (Exception) e : new RuntimeException(e));
                    }
                });

        return ResponseEntity.ok()
                .contentType(new MediaType("text", "event-stream", StandardCharsets.UTF_8))
                .header("Cache-Control", "no-cache, no-transform")
                .header("Connection", "keep-alive")
                .header("X-Accel-Buffering", "no")
                .body(emitter);
    }

    private static String resolveStreamErrorMessage(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof DataAccessException || t instanceof SQLException) {
                return UserFacingMessages.NETWORK_TRY_LATER;
            }
        }
        if (e instanceof IllegalStateException ise) {
            String m = ise.getMessage();
            if (m != null && !m.isBlank()) {
                return m;
            }
        }
        if (e instanceof ResponseStatusException rse) {
            String reason = rse.getReason();
            if (reason != null && !reason.isBlank()) {
                return reason;
            }
        }
        return UserFacingMessages.NETWORK_TRY_LATER;
    }

    private boolean chatPassthrough() {
        return appProperties.getChat().isPassthrough();
    }

    private int chatTurnCost(ChatRequest request) {
        var p = appProperties.getPoints();
        if (!p.isEnabled()) {
            return 0;
        }
        return Boolean.TRUE.equals(request.getDeepReasoning())
                ? p.getChatTurnDeepCost()
                : p.getChatTurnCost();
    }

    private long userIdOrThrow(String username) {
        return userRepository
                .findByUsername(username)
                .map(User::getId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "用户不存在"));
    }

    /** 访客限额与会话编号校验 */
    private void validateGuestAndConversation(String username, ChatRequest request) {
        if (username != null) {
            return;
        }
        if (request.getConversationId() != null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "未登录不能使用会话编号");
        }
    }

    /** 客户端请求中的最后一条用户消息（不含注入的 system） */
    private static String lastUserMessage(List<ChatMessageDto> messages) {
        if (messages == null) {
            return "";
        }
        for (int i = messages.size() - 1; i >= 0; i--) {
            ChatMessageDto m = messages.get(i);
            if (m.getRole() != null && "user".equalsIgnoreCase(m.getRole())) {
                return m.getContent() != null ? m.getContent() : "";
            }
        }
        return "";
    }
}
