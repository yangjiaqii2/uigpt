package top.uigpt.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import top.uigpt.dto.site.AdminSiteMailThreadPageResponse;
import top.uigpt.dto.site.AdminSiteMailThreadRowResponse;
import top.uigpt.dto.site.SiteMailMessageResponse;
import top.uigpt.dto.site.SiteMailSummaryResponse;
import top.uigpt.dto.site.SiteMailThreadDetailResponse;
import top.uigpt.entity.SiteMailMessage;
import top.uigpt.entity.SiteMailThread;
import top.uigpt.entity.User;
import top.uigpt.repository.SiteMailMessageRepository;
import top.uigpt.repository.SiteMailThreadRepository;
import top.uigpt.repository.UserRepository;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class SiteMailService {

    private static final int MAX_BODY_CHARS = 8000;
    private static final int MAX_IMAGES = 6;
    private static final long MAX_IMAGE_BYTES = 5L * 1024 * 1024;

    private final UserRepository userRepository;
    private final SiteMailThreadRepository threadRepository;
    private final SiteMailMessageRepository messageRepository;
    private final ObjectStorageService objectStorageService;
    private final ObjectMapper objectMapper;
    private final AdminAuthorizationService adminAuthorizationService;

    public SiteMailSummaryResponse summaryForUser(String username) {
        User u = requireActiveUser(username);
        return threadRepository
                .findByUserId(u.getId())
                .map(
                        t -> SiteMailSummaryResponse.builder()
                                .threadId(t.getId())
                                .unreadCount(messageRepository.countUnreadForUser(t.getId(), u.getId()))
                                .build())
                .orElseGet(() -> SiteMailSummaryResponse.builder().threadId(null).unreadCount(0).build());
    }

    public long adminUnreadTotal() {
        return messageRepository.countAllUnreadUserMessagesForAdmin();
    }

    @Transactional
    public SiteMailThreadDetailResponse threadForUser(String username) {
        User u = requireActiveUser(username);
        return threadRepository
                .findByUserId(u.getId())
                .map(
                        t -> {
                            messageRepository.markReadByUser(t.getId(), u.getId());
                            return SiteMailThreadDetailResponse.builder()
                                    .threadId(t.getId())
                                    .messages(
                                            toMessageResponses(
                                                    u.getId(), messageRepository.findByThreadIdOrderByCreatedAtAsc(t.getId())))
                                    .build();
                        })
                .orElseGet(() -> SiteMailThreadDetailResponse.builder().threadId(null).messages(List.of()).build());
    }

    @Transactional
    public SiteMailMessageResponse sendUserMessage(String username, String body, List<MultipartFile> images) {
        User u = requireActiveUser(username);
        if (adminAuthorizationService.isAdmin(username)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "管理员请使用后台站内信回复用户");
        }
        String text = normalizeBody(body);
        List<MultipartFile> files = normalizeImages(images);
        if (text.isEmpty() && files.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请输入内容或上传至少一张图片");
        }
        SiteMailThread thread =
                threadRepository
                        .findByUserId(u.getId())
                        .orElseGet(
                                () -> {
                                    SiteMailThread nt = new SiteMailThread();
                                    nt.setUserId(u.getId());
                                    return threadRepository.save(nt);
                                });
        List<String> urls = uploadImages(thread.getId(), files);
        SiteMailMessage m = new SiteMailMessage();
        m.setThreadId(thread.getId());
        m.setSenderUserId(u.getId());
        m.setBody(text.isEmpty() ? " " : text);
        m.setImageUrlsJson(writeJson(urls));
        m.setReadByUser(true);
        m.setReadByAdmin(false);
        m = messageRepository.save(m);
        bumpThreadUpdated(thread.getId());
        return toMessageResponse(m, u.getId());
    }

    @Transactional(readOnly = true)
    public AdminSiteMailThreadPageResponse adminListThreads(String operator, int page, int size) {
        adminAuthorizationService.requireAdmin(operator);
        int p = Math.max(0, page);
        int s = Math.min(Math.max(size, 1), 100);
        Page<SiteMailThread> pg = threadRepository.findAllByOrderByUpdatedAtDesc(PageRequest.of(p, s));
        List<AdminSiteMailThreadRowResponse> rows = new ArrayList<>();
        for (SiteMailThread t : pg.getContent()) {
            User owner = userRepository.findById(t.getUserId()).orElse(null);
            String un = owner != null ? owner.getUsername() : "?";
            String rn = owner != null && owner.getRealName() != null ? owner.getRealName() : "";
            final String[] previewHolder = {""};
            messageRepository
                    .findFirstByThreadIdOrderByCreatedAtDesc(t.getId())
                    .ifPresent(last -> previewHolder[0] = previewOf(last.getBody()));
            long unread = messageRepository.countUnreadForAdmin(t.getId(), t.getUserId());
            rows.add(
                    AdminSiteMailThreadRowResponse.builder()
                            .threadId(t.getId())
                            .userId(t.getUserId())
                            .username(un)
                            .realName(rn)
                            .updatedAt(t.getUpdatedAt())
                            .lastPreview(previewHolder[0])
                            .unreadForAdmin(unread)
                            .build());
        }
        return AdminSiteMailThreadPageResponse.builder()
                .content(rows)
                .totalPages(pg.getTotalPages())
                .totalElements(pg.getTotalElements())
                .build();
    }

    @Transactional
    public SiteMailThreadDetailResponse adminThreadDetail(String operator, long threadId) {
        adminAuthorizationService.requireAdmin(operator);
        User admin = requireActiveUser(operator);
        SiteMailThread t =
                threadRepository
                        .findById(threadId)
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "会话不存在"));
        messageRepository.markReadByAdmin(t.getId(), t.getUserId());
        List<SiteMailMessage> list = messageRepository.findByThreadIdOrderByCreatedAtAsc(t.getId());
        return SiteMailThreadDetailResponse.builder()
                .threadId(t.getId())
                .messages(toMessageResponses(admin.getId(), list))
                .build();
    }

    @Transactional
    public SiteMailMessageResponse sendAdminReply(String operator, long threadId, String body, List<MultipartFile> images) {
        adminAuthorizationService.requireAdmin(operator);
        User admin = requireActiveUser(operator);
        SiteMailThread t =
                threadRepository
                        .findById(threadId)
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "会话不存在"));
        String text = normalizeBody(body);
        List<MultipartFile> files = normalizeImages(images);
        if (text.isEmpty() && files.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请输入内容或上传至少一张图片");
        }
        List<String> urls = uploadImages(t.getId(), files);
        SiteMailMessage m = new SiteMailMessage();
        m.setThreadId(t.getId());
        m.setSenderUserId(admin.getId());
        m.setBody(text.isEmpty() ? " " : text);
        m.setImageUrlsJson(writeJson(urls));
        m.setReadByAdmin(true);
        m.setReadByUser(false);
        m = messageRepository.save(m);
        bumpThreadUpdated(t.getId());
        return toMessageResponse(m, admin.getId());
    }

    private void bumpThreadUpdated(long threadId) {
        SiteMailThread t =
                threadRepository
                        .findById(threadId)
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "线程不存在"));
        t.setUpdatedAt(LocalDateTime.now());
        threadRepository.save(t);
    }

    private User requireActiveUser(String username) {
        User u =
                userRepository
                        .findByUsername(username.strip())
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "用户不存在"));
        if (u.getStatus() != null && u.getStatus() != 1) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "账号不可用");
        }
        return u;
    }

    private String normalizeBody(String body) {
        if (body == null) {
            return "";
        }
        String t = body.strip();
        if (t.length() > MAX_BODY_CHARS) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "正文过长");
        }
        return t;
    }

    private List<MultipartFile> normalizeImages(List<MultipartFile> images) {
        if (images == null) {
            return List.of();
        }
        List<MultipartFile> out = images.stream().filter(Objects::nonNull).filter(f -> !f.isEmpty()).toList();
        if (out.size() > MAX_IMAGES) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "最多上传 " + MAX_IMAGES + " 张图片");
        }
        return out;
    }

    private List<String> uploadImages(long threadId, List<MultipartFile> files) {
        if (files.isEmpty()) {
            return List.of();
        }
        if (!objectStorageService.isReady()) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    objectStorageService.getUnavailableReason());
        }
        List<String> urls = new ArrayList<>();
        for (MultipartFile f : files) {
            if (f.getSize() > MAX_IMAGE_BYTES) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "单张图片不超过 5MB");
            }
            String ct = f.getContentType() != null ? f.getContentType().toLowerCase(Locale.ROOT) : "";
            if (!ct.startsWith("image/")) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "仅支持图片文件");
            }
            String orig = f.getOriginalFilename() != null ? f.getOriginalFilename() : "img";
            String ext = extensionFromName(orig);
            String key;
            try {
                key = objectStorageService.putSiteMailObject(threadId, ext, f.getInputStream(), f.getSize(), ct);
            } catch (IOException e) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "读取上传文件失败");
            }
            urls.add(objectStorageService.browserReadableUrl(key));
        }
        return urls;
    }

    private String extensionFromName(String name) {
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot >= name.length() - 1) {
            return ".png";
        }
        return name.substring(dot).toLowerCase(Locale.ROOT);
    }

    private String writeJson(List<String> urls) {
        if (urls == null || urls.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(urls);
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "序列化附件失败");
        }
    }

    private List<String> readJson(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            return List.of();
        }
    }

    private List<SiteMailMessageResponse> toMessageResponses(long viewerUserId, List<SiteMailMessage> rows) {
        List<SiteMailMessageResponse> out = new ArrayList<>();
        for (SiteMailMessage m : rows) {
            out.add(toMessageResponse(m, viewerUserId));
        }
        return out;
    }

    private SiteMailMessageResponse toMessageResponse(SiteMailMessage m, long viewerUserId) {
        User sender =
                userRepository
                        .findById(m.getSenderUserId())
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "数据不一致"));
        return SiteMailMessageResponse.builder()
                .id(m.getId())
                .threadId(m.getThreadId())
                .senderUserId(m.getSenderUserId())
                .senderUsername(sender.getUsername())
                .body(m.getBody().strip())
                .imageUrls(readJson(m.getImageUrlsJson()))
                .createdAt(m.getCreatedAt())
                .fromSelf(m.getSenderUserId() == viewerUserId)
                .build();
    }

    private String previewOf(String body) {
        if (body == null) {
            return "";
        }
        String t = body.strip().replace('\n', ' ');
        return t.length() > 120 ? t.substring(0, 120) + "…" : t;
    }
}
