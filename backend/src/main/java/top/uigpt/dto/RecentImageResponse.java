package top.uigpt.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class RecentImageResponse {

    private Long id;
    private Long conversationId;
    private String imageUrl;
    private String conversationTitle;
    private LocalDateTime createdAt;
    private boolean favorite;

    /** 如 studio = 图片创作工作台；便于前端区分跳转 */
    private String skillId;
}
