package top.uigpt.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

@Data
public class ChatMessageDto {

    @NotBlank(message = "消息角色不能为空")
    private String role;

    @NotBlank(message = "消息内容不能为空")
    @Size(max = 6000, message = "单条消息请勿超过6000字")
    private String content;

    /**
     * 可选：本轮用户消息附带参考图（data:image/*;base64,... 或 https URL）。仅末条用户消息会被服务端用于识图；
     * 不落库。
     */
    @Valid
    @Size(max = 4, message = "单条消息最多附带4张参考图")
    private List<ChatImagePartDto> images;

    /** 仅服务端列出历史消息时填充，对应 chat_messages.sort_order */
    private Integer sortOrder;
}
