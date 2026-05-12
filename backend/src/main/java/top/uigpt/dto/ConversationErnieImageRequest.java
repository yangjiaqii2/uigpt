package top.uigpt.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class ConversationErnieImageRequest {

    private int messageSortOrder;

    /** 已废弃：会话文生图固定走 APIYi（须配置密钥）。 */
    private String tierMode;

    @NotBlank(message = "skillId 不能为空")
    private String skillId;

    /** 用户本轮输入（含参考图说明亦可） */
    @NotBlank(message = "userMessage 不能为空")
    private String userMessage;

    /** 助手文本回复，用于提炼画面需求 */
    private String assistantReply;

    /**
     * 可选：当前助手气泡之前的会话摘录（由前端从 messages 组装），用于连续出图、再编辑时保持剧情/设定一致。
     */
    @Size(max = 14000, message = "imageConversationContext 过长")
    private String imageConversationContext;

    /** 前端比例：1:1 | 9:16 | 16:9 */
    private String aspectKey = "1:1";

    /** 前端风格标签，如「写实」 */
    private String styleLabel = "";

    /**
     * 可选画质档（与前端通用参数「标准 / 高清 / 超清」对应）：{@code standard} | {@code hd} |
     * {@code ultra}。用于覆盖 VIP 出图尺寸档位；缺省沿用服务端配置。
     */
    private String qualityTier;
}
