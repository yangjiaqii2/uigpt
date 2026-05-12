package top.uigpt.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class RagUpsertResponse {
    /** 成功写入 Qdrant 的条数 */
    private int upserted;
}
