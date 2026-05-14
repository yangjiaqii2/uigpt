package top.uigpt.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ImageStudioSessionSummaryDto {

    private Long id;

    private String title;

    private LocalDateTime updatedAt;

    private int imageCount;

    /** 会话内最后一张图，供侧栏缩略图 */
    private String thumbUrl;

    /** 作图技能标识，供侧栏标签展示 */
    private String studioSkillId;
}
