package top.uigpt.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RagImportTaskStatusResponse {
    private String taskId;
    /** RUNNING | SUCCEEDED | FAILED */
    private String status;
    /** 仅成功后有值 */
    private Integer imported;
    /** 失败时的人类可读原因 */
    private String error;
    private String submittedAt;
    private String finishedAt;
}
