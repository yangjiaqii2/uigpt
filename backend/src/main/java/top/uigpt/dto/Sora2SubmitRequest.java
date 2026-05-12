package top.uigpt.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

/** 视频工作台 · 提交 Sora 2 文生视频（API 易官转 /v1/videos） */
@Data
public class Sora2SubmitRequest {

    @NotBlank private String prompt;

    /** {@code sora-2} 或 {@code sora-2-pro}（小写） */
    @Pattern(regexp = "^(sora-2|sora-2-pro)$", message = "model 仅支持 sora-2 或 sora-2-pro")
    private String model = "sora-2";

    /** 官转仅支持字符串 "4" / "8" / "12" */
    @Pattern(regexp = "^(4|8|12)$", message = "seconds 仅支持 4、8 或 12")
    private String seconds = "8";

    /**
     * 如 1280x720、720x1280；需与 model 支持的档位一致（sora-2 仅 720p 两档，pro 可 1024/1080）。
     */
    @NotBlank private String size = "1280x720";
}
