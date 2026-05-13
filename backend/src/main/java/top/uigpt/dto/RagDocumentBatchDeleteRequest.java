package top.uigpt.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

@Data
public class RagDocumentBatchDeleteRequest {

    @NotNull(message = "pointIds 不能为空")
    @Size(min = 1, max = 200, message = "一次最多删除 200 条")
    private List<String> pointIds;
}
