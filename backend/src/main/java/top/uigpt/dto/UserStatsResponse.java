package top.uigpt.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class UserStatsResponse {

    /** 当前用户全部会话内上传的生成图数量 */
    private long generatedImageCount;

    /** 会话数 */
    private long conversationCount;

    /** 收藏的图片数 */
    private long favoriteImageCount;
}
