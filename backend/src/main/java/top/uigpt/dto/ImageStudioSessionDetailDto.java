package top.uigpt.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ImageStudioSessionDetailDto {

    private Long id;

    private String title;

    private String contextText;

    /** 该会话当前/最近使用的作图技能 */
    private String studioSkillId;

    private List<ImageStudioSessionImageDto> images = new ArrayList<>();
}
