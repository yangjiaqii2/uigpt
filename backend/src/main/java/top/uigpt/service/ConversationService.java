package top.uigpt.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import top.uigpt.dto.ChatMessageDto;
import top.uigpt.dto.ChatRequest;
import top.uigpt.dto.ConversationSummaryResponse;
import top.uigpt.entity.ChatConversation;
import top.uigpt.entity.ChatMessageRow;
import top.uigpt.entity.User;
import top.uigpt.repository.ChatConversationRepository;
import top.uigpt.repository.ChatMessageRowRepository;
import top.uigpt.repository.UserRepository;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class ConversationService {

    /** 自动会话标题：取首条用户消息首行，不超过该字数（码位） */
    private static final int AUTO_TITLE_MAX_CODE_POINTS = 10;

    private final UserRepository userRepository;
    private final ChatConversationRepository conversationRepository;
    private final ChatMessageRowRepository messageRowRepository;
    private final ChatService chatService;
    private final ConversationImageService conversationImageService;

    public List<ConversationSummaryResponse> listForUser(String username) {
        User user =
                userRepository
                        .findByUsername(username)
                        .orElseThrow(
                                () ->
                                        new ResponseStatusException(
                                                HttpStatus.UNAUTHORIZED, "未登录或令牌无效"));
        List<ChatConversation> list =
                conversationRepository.findByUserIdOrderPinnedFirst(user.getId());
        if (list.isEmpty()) {
            return List.of();
        }
        List<Long> ids = list.stream().map(ChatConversation::getId).toList();
        Map<Long, Long> counts = new HashMap<>();
        for (Object[] row : messageRowRepository.countGroupedByConversationId(ids)) {
            counts.put((Long) row[0], (Long) row[1]);
        }
        return list.stream()
                .map(
                        c ->
                                new ConversationSummaryResponse(
                                        c.getId(),
                                        c.getTitle(),
                                        c.getUpdatedAt(),
                                        counts.getOrDefault(c.getId(), 0L).intValue(),
                                        c.getPinnedAt() != null))
                .toList();
    }

    @Transactional
    public void renameConversation(String username, Long conversationId, String title) {
        User user = requireUser(username);
        ChatConversation conv =
                conversationRepository
                        .findByIdAndUserId(conversationId, user.getId())
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "会话不存在"));
        String t = title == null ? "" : title.strip();
        if (t.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "标题不能为空");
        }
        if (t.length() > 255) {
            t = t.substring(0, 255);
        }
        conv.setTitle(t);
        conversationRepository.save(conv);
    }

    @Transactional
    public void setConversationPinned(String username, Long conversationId, boolean pinned) {
        User user = requireUser(username);
        ChatConversation conv =
                conversationRepository
                        .findByIdAndUserId(conversationId, user.getId())
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "会话不存在"));
        conv.setPinnedAt(pinned ? LocalDateTime.now() : null);
        conversationRepository.save(conv);
    }

    @Transactional
    public void deleteConversation(String username, Long conversationId) {
        User user = requireUser(username);
        ChatConversation conv =
                conversationRepository
                        .findByIdAndUserId(conversationId, user.getId())
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "会话不存在"));
        conversationImageService.detachOrRemoveImagesBeforeConversationDelete(conv);
        conversationRepository.delete(conv);
    }

    private User requireUser(String username) {
        return userRepository
                .findByUsername(username)
                .orElseThrow(
                        () -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "未登录或令牌无效"));
    }

    public List<ChatMessageDto> getMessages(String username, Long conversationId) {
        User user =
                userRepository
                        .findByUsername(username)
                        .orElseThrow(
                                () ->
                                        new ResponseStatusException(
                                                HttpStatus.UNAUTHORIZED, "未登录或令牌无效"));
        ChatConversation conv =
                conversationRepository
                        .findByIdAndUserId(conversationId, user.getId())
                        .orElseThrow(
                                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "会话不存在"));
        return messageRowRepository.findByConversationIdOrderBySortOrderAsc(conv.getId()).stream()
                .map(this::toDto)
                .toList();
    }

    /**
     * 将本会话已保存的记忆注入为首条 system（仅用于调用模型，不落库到消息表）。
     */
    public ChatRequest injectSessionMemory(String username, ChatRequest original) {
        if (username == null || original.getConversationId() == null) {
            return original;
        }
        User user =
                userRepository
                        .findByUsername(username)
                        .orElseThrow(
                                () ->
                                        new ResponseStatusException(
                                                HttpStatus.UNAUTHORIZED, "未登录或令牌无效"));
        ChatConversation conv =
                conversationRepository
                        .findByIdAndUserId(original.getConversationId(), user.getId())
                        .orElse(null);
        if (conv == null
                || isWorkbenchArchiveConversation(conv)
                || conv.getSessionMemory() == null
                || conv.getSessionMemory().isBlank()) {
            return original;
        }
        ChatRequest copy = new ChatRequest();
        copy.setConversationId(original.getConversationId());
        copy.setSkillContext(original.getSkillContext());
        copy.setSkillId(original.getSkillId());
        copy.setTierMode(original.getTierMode());
        copy.setFastFreeformModel(original.getFastFreeformModel());
        copy.setDeepReasoning(original.getDeepReasoning());
        copy.setUseRag(original.getUseRag());
        copy.setRagCollection(original.getRagCollection());
        List<ChatMessageDto> list = new ArrayList<>();
        ChatMessageDto sys = new ChatMessageDto();
        sys.setRole("system");
        sys.setContent(
                "【本会话记忆】下列内容由系统仅根据当前会话内的历史整理，与其它会话无关；请在回复时保持一致；若与下文用户最新说法冲突，以用户最新说法为准。\n\n"
                        + conv.getSessionMemory());
        list.add(sys);
        list.addAll(original.getMessages());
        copy.setMessages(list);
        return copy;
    }

    /**
     * 在一轮对话落库后，用模型合并更新 session_memory（失败仅打日志）。
     */
    public void refreshSessionMemoryAfterTurn(
            String username, Long conversationId, String lastUserMessage, String assistantReply) {
        try {
            User user = userRepository.findByUsername(username).orElseThrow();
            ChatConversation conv =
                    conversationRepository
                            .findByIdAndUserId(conversationId, user.getId())
                            .orElseThrow();
            if (isWorkbenchArchiveConversation(conv)) {
                return;
            }
            String merged =
                    chatService.mergeSessionMemorySummary(
                            conv.getSessionMemory(), lastUserMessage, assistantReply);
            conv.setSessionMemory(merged);
            conversationRepository.save(conv);
        } catch (Exception e) {
            log.warn("刷新会话记忆失败 conversationId={}", conversationId, e);
        }
    }

    /** 图片/视频工作台归档会话：不参与聊天侧 session_memory 的注入与刷新，避免与「每会话独立」的图片会话上下文混淆。 */
    private static boolean isWorkbenchArchiveConversation(ChatConversation conv) {
        return conv.getStudioChannel() != null && !conv.getStudioChannel().isBlank();
    }

    private ChatMessageDto toDto(ChatMessageRow row) {
        ChatMessageDto d = new ChatMessageDto();
        d.setRole(row.getRole());
        d.setContent(row.getContent());
        d.setSortOrder(row.getSortOrder());
        return d;
    }

    @Transactional
    public Long syncAfterChat(
            String username,
            Long conversationIdOrNull,
            List<ChatMessageDto> threadMessages,
            String assistantReply) {
        User user =
                userRepository
                        .findByUsername(username)
                        .orElseThrow(
                                () ->
                                        new ResponseStatusException(
                                                HttpStatus.UNAUTHORIZED, "未登录或令牌无效"));
        ChatConversation conv;
        if (conversationIdOrNull == null) {
            conv = new ChatConversation();
            conv.setUserId(user.getId());
            conv.setTitle(titleFromThread(threadMessages));
            conv.setSessionMemory(null);
            conv = conversationRepository.save(conv);
        } else {
            conv =
                    conversationRepository
                            .findByIdAndUserId(conversationIdOrNull, user.getId())
                            .orElseThrow(
                                    () ->
                                            new ResponseStatusException(
                                                    HttpStatus.NOT_FOUND, "会话不存在"));
            if (conv.getTitle() == null
                    || conv.getTitle().isBlank()
                    || "新对话".equals(conv.getTitle())) {
                conv.setTitle(titleFromThread(threadMessages));
            }
        }

        messageRowRepository.deleteByConversationId(conv.getId());

        int order = 0;
        for (ChatMessageDto m : threadMessages) {
            ChatMessageRow row = new ChatMessageRow();
            row.setConversationId(conv.getId());
            row.setRole(m.getRole().toLowerCase());
            row.setContent(m.getContent());
            row.setSortOrder(order++);
            messageRowRepository.save(row);
        }
        ChatMessageRow replyRow = new ChatMessageRow();
        replyRow.setConversationId(conv.getId());
        replyRow.setRole("assistant");
        replyRow.setContent(assistantReply);
        replyRow.setSortOrder(order);
        messageRowRepository.save(replyRow);

        return conversationRepository.save(conv).getId();
    }

    private static String titleFromThread(List<ChatMessageDto> threadMessages) {
        for (ChatMessageDto m : threadMessages) {
            if (m.getRole() != null && "user".equalsIgnoreCase(m.getRole())) {
                String c = m.getContent();
                if (c != null && !c.isBlank()) {
                    String line = firstContentLine(c).strip();
                    if (!line.isEmpty()) {
                        return truncateByCodePoints(line, AUTO_TITLE_MAX_CODE_POINTS);
                    }
                }
            }
        }
        return "新对话";
    }

    private static String firstContentLine(String c) {
        int n = c.indexOf('\n');
        int r = c.indexOf('\r');
        int end = c.length();
        if (n >= 0) {
            end = Math.min(end, n);
        }
        if (r >= 0) {
            end = Math.min(end, r);
        }
        return c.substring(0, end);
    }

    private static String truncateByCodePoints(String s, int maxCp) {
        if (s == null || s.isBlank()) {
            return "";
        }
        int count = s.codePointCount(0, s.length());
        if (count <= maxCp) {
            return s;
        }
        int end = s.offsetByCodePoints(0, maxCp);
        return s.substring(0, end);
    }
}
