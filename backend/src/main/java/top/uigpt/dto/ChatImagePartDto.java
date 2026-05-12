package top.uigpt.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** OpenAI 兼容多模态消息中的 <code>image_url.url</code>（data URL 或 https）。 */
@Data
public class ChatImagePartDto {

    @NotBlank(message = "参考图地址不能为空")
    @Size(max = 12_000_000, message = "单张参考图编码过长")
    private String url;
}
