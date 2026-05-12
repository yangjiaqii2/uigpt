package top.uigpt.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

@Data
public class RagUpsertRequest {

    @NotEmpty(message = "texts 不能为空")
    @Size(max = 50, message = "单次最多写入 50 条")
    private List<@NotBlank @Size(max = 32000) String> texts;
}
