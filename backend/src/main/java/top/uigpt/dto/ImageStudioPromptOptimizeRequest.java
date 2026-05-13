package top.uigpt.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** 图片工作台 · 提示词优化（LLM 改写为更适合 Gemini/Banana 的描述） */
@Data
public class ImageStudioPromptOptimizeRequest {

    @NotBlank private String prompt;

    /** 当前工具：txt2img / img2img 等 */
    private String tool = "";

    /** 风格卡片文案，如「写实」 */
    private String styleLabel = "";

    /** 比例，如 9:16 */
    private String aspectLabel = "";

    /** 画质档位说明，如「高清」 */
    private String qualityLabel = "";

    /** 创作介质：{@code video} 时使用视频提示词优化系统提示；省略或非 video 为图片工作台 */
    private String medium = "";

    /** 默认 true：优化前按 {@link #prompt} 注入知识库片段；传 false 可关闭。 */
    private Boolean useRag = Boolean.TRUE;

    @Size(max = 128, message = "ragCollection 过长")
    private String ragCollection;
}
