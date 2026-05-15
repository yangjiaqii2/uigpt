package top.uigpt.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import top.uigpt.dto.ConversationImageResponse;
import top.uigpt.dto.ImageFavoriteRequest;
import top.uigpt.dto.RecentImageResponse;
import top.uigpt.dto.UserStatsResponse;
import top.uigpt.security.SecurityUtils;
import top.uigpt.service.ConversationImageService;
import top.uigpt.service.MeProfileService;

import java.util.List;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class MeController {

    private final MeProfileService meProfileService;
    private final ConversationImageService conversationImageService;

    @GetMapping("/me/stats")
    public UserStatsResponse meStats() {
        return meProfileService.stats(requireUser());
    }

    @GetMapping("/me/recent-images")
    public List<RecentImageResponse> meRecentImages(@RequestParam(defaultValue = "8") int limit) {
        return meProfileService.recentImages(requireUser(), limit);
    }

    /**
     * 分页浏览当前用户全部生成图（按创建时间倒序）。page 从 0 开始。
     */
    @GetMapping("/me/images")
    public List<RecentImageResponse> meImages(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "48") int size,
            @RequestParam(required = false) String skill) {
        return meProfileService.myImagesPage(requireUser(), page, size, skill);
    }

    /**
     * 取消收藏 / 收藏（含会话已删除后仍挂在「我的作品」下的图片）。
     */
    @PatchMapping("/me/images/{imageId}/favorite")
    public ResponseEntity<ConversationImageResponse> patchMyImageFavorite(
            @PathVariable("imageId") Long imageId, @RequestBody ImageFavoriteRequest body) {
        String username = requireUser();
        boolean fav = body != null && body.isFavorite();
        ConversationImageResponse res =
                conversationImageService.setFavoriteForOwner(username, imageId, fav);
        if (res == null) {
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.ok(res);
    }

    /** 删除当前用户名下的一张生成图（COS 与库记录）。 */
    @DeleteMapping("/me/images/{imageId}")
    public ResponseEntity<Void> deleteMyImage(@PathVariable("imageId") Long imageId) {
        conversationImageService.deleteImageForOwner(requireUser(), imageId);
        return ResponseEntity.noContent().build();
    }

    private String requireUser() {
        String username = SecurityUtils.currentUsernameOrNull();
        if (username == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "未登录或令牌无效");
        }
        return username;
    }
}
