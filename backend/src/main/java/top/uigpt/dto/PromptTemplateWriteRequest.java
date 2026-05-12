package top.uigpt.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class PromptTemplateWriteRequest {

    @NotBlank(message = "标题不能为空")
    @Size(max = 512, message = "标题最多 512 个字符")
    private String title;

    @NotNull(message = "正文不能为空")
    @Size(max = 16_777_215, message = "正文过长")
    private String body;
}
