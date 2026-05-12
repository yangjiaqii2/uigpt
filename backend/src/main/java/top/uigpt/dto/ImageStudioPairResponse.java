package top.uigpt.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 一次用户动作返回两路 Nano Banana 文生图/编辑候选（积分仍按单次档位扣，见控制器注释）。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ImageStudioPairResponse {

    private ImageStudioSlotResult first;

    private ImageStudioSlotResult second;

    /**
     * 例如仅一路 API 成功时的提示；两路皆成功或（在抛出前）皆失败则为 null。
     */
    private String partialHint;
}
