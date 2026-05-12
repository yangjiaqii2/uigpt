package top.uigpt.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class RagDocumentPageResponse {
    private List<RagDocumentListItemResponse> content;
    private long totalElements;
    private int totalPages;
    private int number;
    private int size;
}
