package top.uigpt.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

@Data
public class ChatRequest {

    /** 登录用户可选；用于续写同一会话 */
    private Long conversationId;

    /**
     * 可选：前端「技能卡片」附加上下文，仅并入发往模型的系统提示，不写入用户消息存档。
     */
    /** 配色面板摘要等可能较长；完整配色系统提示在后端资源文件中拼接 */
    @Size(max = 32768, message = "技能上下文过长")
    private String skillContext;

    /** 可选：当前为自由对话时传 {@code freeform} 或留空。 */
    @Size(max = 64, message = "skillId 过长")
    private String skillId;

    /**
     * 已废弃：保留字段仅为兼容旧客户端。对话线路由服务端固定为经 API易 转发（若已配置密钥）。
     */
    @Size(max = 16, message = "tierMode 过长")
    private String tierMode;

    /**
     * API易 OpenAI 兼容 model id（须在服务端允许列表内）；未传时访客/默认：未开深度推理为 {@code gpt-5}，开启为 {@code
     * gpt-5.4-pro}。仅登录用户自选时常由前端大类映射。
     */
    @Size(max = 128, message = "fastFreeformModel 过长")
    private String fastFreeformModel;

    /**
     * 自由对话是否启用「深度推理」：{@code true} 时降级链优先最强型号；{@code false} 或未传时优先最快型号。
     */
    private Boolean deepReasoning;

    /**
     * 为 {@code true} 时在 {@code uigpt.chat.passthrough=true} 下仍请求知识库检索（须服务端开启 {@code uigpt.rag.enabled} 且
     * 已配置 Qdrant 与 embedding）。非透传模式下无需传此字段也会自动检索。
     */
    private Boolean useRag;

    /**
     * 可选：覆盖默认 Qdrant 集合名；仅允许字母、数字、下划线与短横线，最长 128。
     */
    @Size(max = 128, message = "ragCollection 过长")
    private String ragCollection;

    @NotEmpty(message = "消息列表不能为空")
    @Size(max = 100, message = "单次对话消息条数请勿超过100条，请新建会话或删减历史")
    @Valid
    private List<ChatMessageDto> messages;
}
