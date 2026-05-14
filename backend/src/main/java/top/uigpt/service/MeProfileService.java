package top.uigpt.service;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import top.uigpt.dto.RecentImageResponse;
import top.uigpt.dto.UserStatsResponse;
import top.uigpt.entity.ChatConversation;
import top.uigpt.entity.ChatConversationImage;
import top.uigpt.entity.User;
import top.uigpt.repository.ChatConversationImageRepository;
import top.uigpt.repository.ChatConversationRepository;
import top.uigpt.repository.UserRepository;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class MeProfileService {

    private static final int MAX_PAGE_SIZE = 120;

    private final UserRepository userRepository;
    private final ChatConversationRepository conversationRepository;
    private final ChatConversationImageRepository imageRepository;
    private final ObjectStorageService objectStorageService;

    public UserStatsResponse stats(String username) {
        User user = requireUser(username);
        long conv = conversationRepository.countByUserId(user.getId());
        long gen = imageRepository.countImagesForUser(user.getId());
        long fav = imageRepository.countFavoriteImagesForUser(user.getId());
        return new UserStatsResponse(gen, conv, fav);
    }

    public List<RecentImageResponse> recentImages(String username, int limit) {
        User user = requireUser(username);
        int lim = Math.min(Math.max(limit, 1), 24);
        var page =
                imageRepository.findImagesForUserPage(
                        user.getId(), PageRequest.of(0, lim));
        return mapRows(page.getContent());
    }

    public List<RecentImageResponse> myImagesPage(String username, int page, int size) {
        return myImagesPage(username, page, size, null);
    }

    /**
     * @param skillIdFilter 非空时仅返回该技能下图，如 {@link ConversationImageService#IMAGE_STUDIO_SKILL_ID}
     */
    public List<RecentImageResponse> myImagesPage(
            String username, int page, int size, String skillIdFilter) {
        User user = requireUser(username);
        int sz = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        int p = Math.max(0, page);
        String skill =
                skillIdFilter == null || skillIdFilter.isBlank()
                        ? null
                        : skillIdFilter.strip().toLowerCase(Locale.ROOT);
        var pg =
                "studio".equals(skill)
                        ? imageRepository.findImageStudioWorksForUser(
                                user.getId(), PageRequest.of(p, sz))
                        : skill != null
                                ? imageRepository.findByUserIdAndSkillIdOrderByCreatedAtDesc(
                                        user.getId(), skill, PageRequest.of(p, sz))
                                : imageRepository.findImagesForUserPage(
                                        user.getId(), PageRequest.of(p, sz));
        return mapRows(pg.getContent());
    }

    private List<RecentImageResponse> mapRows(List<ChatConversationImage> rows) {
        if (rows.isEmpty()) {
            return List.of();
        }
        Set<Long> convIds =
                rows.stream()
                        .map(ChatConversationImage::getConversationId)
                        .filter(Objects::nonNull)
                        .collect(Collectors.toSet());
        Map<Long, ChatConversation> convMap =
                conversationRepository.findAllById(convIds).stream()
                        .collect(Collectors.toMap(ChatConversation::getId, Function.identity()));
        return rows.stream()
                .map(
                        i -> {
                            ChatConversation c =
                                    i.getConversationId() != null
                                            ? convMap.get(i.getConversationId())
                                            : null;
                            String title;
                            if (c != null && c.getTitle() != null && !c.getTitle().isBlank()) {
                                title = c.getTitle();
                            } else if (ConversationImageService.isImageStudioWorkbenchGallerySkill(
                                    i.getSkillId())) {
                                title =
                                        ConversationImageService.imageStudioSkillLabelZh(i.getSkillId())
                                                + " · 作品库";
                            } else if (ConversationImageService.VIDEO_STUDIO_SKILL_ID.equals(
                                    i.getSkillId())) {
                                title = "视频创作";
                            } else if (i.getConversationId() == null && i.isFavorite()) {
                                title = "收藏";
                            } else {
                                title = "未命名会话";
                            }
                            return new RecentImageResponse(
                                    i.getId(),
                                    i.getConversationId(),
                                    objectStorageService.browserReadableUrl(i.getObjectKey()),
                                    title,
                                    i.getCreatedAt(),
                                    i.isFavorite(),
                                    i.getSkillId());
                        })
                .toList();
    }

    private User requireUser(String username) {
        if (username == null || username.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "未登录或令牌无效");
        }
        return userRepository
                .findByUsername(username)
                .orElseThrow(
                        () -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "未登录或令牌无效"));
    }
}
