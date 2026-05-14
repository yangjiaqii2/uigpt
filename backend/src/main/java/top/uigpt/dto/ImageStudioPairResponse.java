package top.uigpt.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Collections;
import java.util.List;

/** 一次用户动作返回多路 Nano Banana 文生图/编辑候选（积分仍按单次档位扣，见控制器注释）。 */
@Data
@NoArgsConstructor
public class ImageStudioPairResponse {

    private ImageStudioSlotResult first;

    private ImageStudioSlotResult second;

    /**
     * 例如仅部分路 API 成功时的提示；多路皆成功或（在抛出前）皆失败则为 null。
     */
    private String partialHint;

    /**
     * 推荐槽的全局下标 {@code 0..n-1}（与 {@link #first}/{@link #second}/{@link #extraSlots} 顺序一致）；Judge 关闭、无法区分或平分时为
     * null。
     */
    private Integer recommendedSlot;

    /** {@code candidateCount>2} 时第 3 路及以后的结果；否则为空列表。 */
    private List<ImageStudioSlotResult> extraSlots;

    public ImageStudioPairResponse(ImageStudioSlotResult first, ImageStudioSlotResult second, String partialHint) {
        this(first, second, partialHint, null, null);
    }

    public ImageStudioPairResponse(
            ImageStudioSlotResult first,
            ImageStudioSlotResult second,
            String partialHint,
            Integer recommendedSlot,
            List<ImageStudioSlotResult> extraSlots) {
        this.first = first;
        this.second = second;
        this.partialHint = partialHint;
        this.recommendedSlot = recommendedSlot;
        this.extraSlots = extraSlots == null ? Collections.emptyList() : extraSlots;
    }
}
