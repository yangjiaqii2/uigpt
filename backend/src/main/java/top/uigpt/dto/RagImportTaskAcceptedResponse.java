package top.uigpt.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class RagImportTaskAcceptedResponse {
    private String taskId;
    /** 初始为 RUNNING；客户端可轮询 {@code GET .../import-tasks/{taskId}} */
    private String status;
}
