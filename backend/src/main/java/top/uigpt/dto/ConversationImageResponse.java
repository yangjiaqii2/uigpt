package top.uigpt.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** 会话图片；高速出图（tierMode=fast）时在单次生成响应中带 {@code b64_json}。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ConversationImageResponse {

    private Long id;
    private Integer messageSortOrder;
    private String skillId;
    private String imageUrl;
    private LocalDateTime createdAt;
    private boolean favorite;

    /** OpenAI Images 风格：原始 Base64（不含 data URL 前缀）；仅高速线路生成接口返回。 */
    @JsonProperty("b64_json")
    private String b64Json;

    /** 与 {@link #b64Json} 对应的 MIME，便于前端拼 data URL；仅高速线路生成接口返回。 */
    @JsonProperty("b64_mime_type")
    private String b64MimeType;
}
