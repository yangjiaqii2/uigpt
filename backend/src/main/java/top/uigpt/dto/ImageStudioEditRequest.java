package top.uigpt.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/** 图片工作台 · Nano Banana Pro 图片编辑（text + N×inlineData） */
@Data
public class ImageStudioEditRequest {

    @NotBlank private String prompt;

    /** 可选：用户输入框原文，仅用于落库展示；不传则落库用 {@link #prompt}。 */
    @Size(max = 4000, message = "userDisplayPrompt 过长")
    private String userDisplayPrompt;

    /** 可选：同 {@link ImageStudioTextRequest#getImageSessionContext()} */
    @Size(max = 12000, message = "imageSessionContext 过长")
    private String imageSessionContext;

    private String aspectRatio = "1:1";

    private String imageSize = "2K";

    @NotEmpty private List<InlineImagePart> images;

    /**
     * 兼容字段：服务端已固定启用 RAG；传 false 亦会被忽略。
     */
    private Boolean useRag = Boolean.TRUE;

    @Size(max = 128, message = "ragCollection 过长")
    private String ragCollection;

    /**
     * 仅多路编辑接口使用：并行候选路数，服务端 clamp 至 {@code uigpt.image-studio.pair-max-candidates}。未传默认 2。
     */
    @Min(value = 2, message = "candidateCount 至少为 2")
    private Integer candidateCount;

    /** 非空时生成结果写入该「图片会话」 */
    private Long imageStudioSessionId;

    /**
     * 工作台当前工具：txt2img / img2img / inpaint 等；用于作品库按类型归档。
     */
    @Size(max = 32, message = "studioToolId 过长")
    private String studioToolId;

    /** 作图技能，同 {@link ImageStudioTextRequest#getStudioSkillId()} */
    @Size(max = 64, message = "studioSkillId 过长")
    private String studioSkillId;

    @Data
    public static class InlineImagePart {
        /** image/jpeg 或 image/png */
        private String mimeType = "image/jpeg";

        @NotBlank private String dataBase64;
    }
}
