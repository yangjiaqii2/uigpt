package top.uigpt.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RagDocumentBatchDeleteResponse {
    /** 已从 MySQL 与向量库移除的条数 */
    private int deleted;
    /** 请求中存在但库中不存在的 pointId（不影响其余删除） */
    @Builder.Default
    private List<String> notFound = new ArrayList<>();
}
