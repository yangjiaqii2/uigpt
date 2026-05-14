package top.uigpt.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** 图片工作台 · Nano Banana Pro 文生图 */
@Data
public class ImageStudioTextRequest {

    @NotBlank private String prompt;

    /**
     * 可选：用户在输入框中的原文（用于会话记录/作品库展示）。不传则落库仍用 {@link #prompt}。
     */
    @Size(max = 4000, message = "userDisplayPrompt 过长")
    private String userDisplayPrompt;

    /**
     * 可选：本页此前多轮出图/编辑的提示摘要，服务端拼入模型 prompt；落库优先 {@link #userDisplayPrompt}，否则
     * {@link #prompt}。
     */
    @Size(max = 12000, message = "imageSessionContext 过长")
    private String imageSessionContext;

    /** 文档枚举：1:1、16:9 等 */
    private String aspectRatio = "1:1";

    /** 1K / 2K / 4K */
    private String imageSize = "2K";

    /**
     * 兼容字段：服务端图片工作台已固定对作图请求启用 RAG；传 false 亦会被忽略。
     */
    private Boolean useRag = Boolean.TRUE;

    /** 可选：覆盖默认 Qdrant 集合名；仅允许字母、数字、下划线与短横线，最长 128。 */
    @Size(max = 128, message = "ragCollection 过长")
    private String ragCollection;

    /**
     * 仅多路文生图接口使用：并行候选路数，服务端 clamp 至 {@code uigpt.image-studio.pair-max-candidates}（至少为 2）。未传默认
     * 2。
     */
    @Min(value = 2, message = "candidateCount 至少为 2")
    private Integer candidateCount;

    /** 非空时生成结果写入该「图片会话」，不写 chat 归档会话 */
    private Long imageStudioSessionId;

    /**
     * 工作台当前工具：txt2img / img2img / inpaint / outpaint / enhance / style；用于作品库按类型归档（skill
     * studio_*）。
     */
    @Size(max = 32, message = "studioToolId 过长")
    private String studioToolId;

    /**
     * 作图技能：如 {@code interior_designer}（家装设计师，走家装三阶段 prompt）；未传由服务端按家装默认。
     */
    @Size(max = 64, message = "studioSkillId 过长")
    private String studioSkillId;
}
