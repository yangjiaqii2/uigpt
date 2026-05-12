package top.uigpt.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** 图片工作台 · Nano Banana Pro 文生图 */
@Data
public class ImageStudioTextRequest {

    @NotBlank private String prompt;

    /**
     * 可选：本页此前多轮出图/编辑的提示摘要，服务端拼入模型 prompt；落库仍仅用 {@link #prompt}。
     */
    @Size(max = 12000, message = "imageSessionContext 过长")
    private String imageSessionContext;

    /** 文档枚举：1:1、16:9 等 */
    private String aspectRatio = "1:1";

    /** 1K / 2K / 4K */
    private String imageSize = "2K";
}
