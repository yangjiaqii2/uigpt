package top.uigpt.dto.site;

import lombok.Builder;
import lombok.Value;

import java.time.LocalDateTime;

@Value
@Builder
public class AdminSiteMailThreadRowResponse {
    long threadId;
    long userId;
    String username;
    String realName;
    LocalDateTime updatedAt;
    String lastPreview;
    long unreadForAdmin;
}
