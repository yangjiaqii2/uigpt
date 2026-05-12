package top.uigpt.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RagDocumentDetailResponse {
    private String id;
    private String title;
    private String text;
    private String createdAt;
}
