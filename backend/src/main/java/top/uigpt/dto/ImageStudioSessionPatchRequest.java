package top.uigpt.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class ImageStudioSessionPatchRequest {

    @Size(max = 16000, message = "contextText 过长")
    private String contextText;

    @Size(max = 255, message = "title 过长")
    private String title;

    /** 可选：更新会话绑定的作图技能 */
    @Size(max = 64, message = "studioSkillId 过长")
    private String studioSkillId;
}
