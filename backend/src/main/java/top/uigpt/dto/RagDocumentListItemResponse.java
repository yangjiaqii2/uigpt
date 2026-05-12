package top.uigpt.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RagDocumentListItemResponse {
    /** 与 Qdrant 点 id 一致 */
    private String id;

    private String title;
    private String preview;
    private String createdAt;
}
