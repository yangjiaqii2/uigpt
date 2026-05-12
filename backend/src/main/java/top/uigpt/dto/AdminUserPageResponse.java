package top.uigpt.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class AdminUserPageResponse {
    private List<AdminUserResponse> content;
    private long totalElements;
    private int totalPages;
    private int number;
    private int size;
}
