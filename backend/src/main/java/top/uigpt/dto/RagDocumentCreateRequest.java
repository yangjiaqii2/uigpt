package top.uigpt.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class RagDocumentCreateRequest {

    @Size(max = 512, message = "标题过长")
    private String title;

    @NotBlank(message = "正文不能为空")
    @Size(max = 32000, message = "正文过长")
    private String text;
}
