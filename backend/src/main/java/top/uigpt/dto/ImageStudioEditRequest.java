package top.uigpt.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/** 图片工作台 · Nano Banana Pro 图片编辑（text + N×inlineData） */
@Data
public class ImageStudioEditRequest {

    @NotBlank private String prompt;

    /** 可选：同 {@link ImageStudioTextRequest#getImageSessionContext()} */
    @Size(max = 12000, message = "imageSessionContext 过长")
    private String imageSessionContext;

    private String aspectRatio = "1:1";

    private String imageSize = "2K";

    @NotEmpty private List<InlineImagePart> images;

    @Data
    public static class InlineImagePart {
        /** image/jpeg 或 image/png */
        private String mimeType = "image/jpeg";

        @NotBlank private String dataBase64;
    }
}
