package top.uigpt.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.async.AsyncRequestTimeoutException;
import org.springframework.web.server.ResponseStatusException;
import top.uigpt.UserFacingMessages;
import top.uigpt.dto.ApiError;

import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.stream.Collectors;

@Slf4j
@RestControllerAdvice
@RequiredArgsConstructor
public class GlobalExceptionHandler {

    private final ObjectMapper objectMapper;

    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<ApiError> handleDataAccess(DataAccessException ex) {
        log.warn("数据库访问失败", ex);
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(new ApiError(UserFacingMessages.NETWORK_TRY_LATER));
    }

    @ExceptionHandler(SQLException.class)
    public ResponseEntity<ApiError> handleSql(SQLException ex) {
        log.warn("SQL 异常", ex);
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(new ApiError(UserFacingMessages.NETWORK_TRY_LATER));
    }

    /**
     * 客户端对 {@code /chat/stream} 常带 {@code Accept: text/event-stream}，若此处仍返回 JSON 体会触发
     * HttpMediaTypeNotAcceptableException；改为单行 SSE error 事件与流内失败格式一致。
     */
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<?> handleStatus(ResponseStatusException ex, HttpServletRequest req) {
        HttpStatus status = HttpStatus.valueOf(ex.getStatusCode().value());
        String reason = ex.getReason();
        if (reason == null || reason.isBlank()) {
            reason = zhReasonForStatus(ex.getStatusCode().value());
        }
        if (prefersStreamChatError(req) && status != HttpStatus.PAYMENT_REQUIRED) {
            return ResponseEntity.ok()
                    .contentType(new MediaType("text", "event-stream", StandardCharsets.UTF_8))
                    .body(sseErrorEvent(reason));
        }
        return ResponseEntity.status(status).body(new ApiError(reason));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<?> handleValidation(MethodArgumentNotValidException ex, HttpServletRequest req) {
        String msg =
                ex.getBindingResult().getFieldErrors().stream()
                        .map(e -> e.getDefaultMessage() != null ? e.getDefaultMessage() : e.getField())
                        .collect(Collectors.joining("; "));
        if (msg.chars().noneMatch(ch -> ch > 127)) {
            msg = "输入不符合要求，请检查后重试";
        }
        if (prefersStreamChatError(req)) {
            return ResponseEntity.ok()
                    .contentType(new MediaType("text", "event-stream", StandardCharsets.UTF_8))
                    .body(sseErrorEvent(msg));
        }
        return ResponseEntity.badRequest().body(new ApiError(msg));
    }

    @ExceptionHandler(AsyncRequestTimeoutException.class)
    public ResponseEntity<?> handleAsyncTimeout(AsyncRequestTimeoutException ex, HttpServletRequest req) {
        log.warn("异步请求超时（流式对话）；已延长 spring.mvc.async.request-timeout 时可减少此类中断", ex);
        if (prefersStreamChatError(req)) {
            return ResponseEntity.ok()
                    .contentType(new MediaType("text", "event-stream", StandardCharsets.UTF_8))
                    .body(sseErrorEvent(UserFacingMessages.NETWORK_TRY_LATER));
        }
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(new ApiError(UserFacingMessages.NETWORK_TRY_LATER));
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<?> handleAuthentication(AuthenticationException ex, HttpServletRequest req) {
        String msg = "未登录或令牌无效";
        if (prefersStreamChatError(req)) {
            return ResponseEntity.ok()
                    .contentType(new MediaType("text", "event-stream", StandardCharsets.UTF_8))
                    .body(sseErrorEvent(msg));
        }
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(new ApiError(msg));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<?> handleAccessDenied(AccessDeniedException ex, HttpServletRequest req) {
        String msg = "禁止访问";
        if (prefersStreamChatError(req)) {
            return ResponseEntity.ok()
                    .contentType(new MediaType("text", "event-stream", StandardCharsets.UTF_8))
                    .body(sseErrorEvent(msg));
        }
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(new ApiError(msg));
    }

    private boolean prefersStreamChatError(HttpServletRequest req) {
        if (req == null) {
            return false;
        }
        String uri = req.getRequestURI();
        if (uri == null || !uri.endsWith("/chat/stream")) {
            return false;
        }
        String accept = req.getHeader("Accept");
        return accept == null
                || accept.contains("text/event-stream")
                || accept.contains("*/*");
    }

    private String sseErrorEvent(String message) {
        try {
            ObjectNode node = objectMapper.createObjectNode();
            node.put("error", message != null ? message : UserFacingMessages.NETWORK_TRY_LATER);
            return "data: " + objectMapper.writeValueAsString(node) + "\n\n";
        } catch (JsonProcessingException e) {
            return "data: {\"error\":\"" + UserFacingMessages.NETWORK_TRY_LATER + "\"}\n\n";
        }
    }

    private static String zhReasonForStatus(int code) {
        return switch (code) {
            case 400 -> "请求无效";
            case 401 -> "未授权";
            case 403 -> "禁止访问";
            case 404 -> "未找到";
            case 402 -> "积分不足";
            case 429 -> "请求过于频繁，请稍后再试";
            case 502, 503, 504 -> UserFacingMessages.NETWORK_TRY_LATER;
            default -> "请求失败";
        };
    }
}
