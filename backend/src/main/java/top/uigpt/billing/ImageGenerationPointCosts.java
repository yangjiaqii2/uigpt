package top.uigpt.billing;

import java.util.Locale;

/**
 * 文生图类能力按「档位」扣积分（与前端/请求 DTO 字段对应）。
 *
 * <h3>1) 图片工作台 Nano Banana（{@code ImageStudioTextRequest#imageSize} / {@code ImageStudioEditRequest#imageSize}）</h3>
 *
 * <table border="1" summary="工作台档位与积分">
 *   <tr><th>imageSize（API）</th><th>含义</th><th>积分/次</th></tr>
 *   <tr><td>1K</td><td>相对低分辨率，偏快</td><td>18</td></tr>
 *   <tr><td>2K（默认）</td><td>中档平衡</td><td>32</td></tr>
 *   <tr><td>4K</td><td>高细节</td><td>52</td></tr>
 * </table>
 *
 * <p>工作台「双候选」接口（一次动作并行两路 Nano Banana）仍按上表<strong>扣一档一次</strong>，不按张翻倍。
 *
 * <h3>2) 会话技能文生图（{@code ConversationErnieImageRequest#qualityTier}）</h3>
 *
 * <p>与前端「标准 / 高清 / 超清」对应：{@code standard}、{@code hd}、{@code ultra}；空或未识别按 {@code hd}。
 *
 * <table border="1" summary="会话档位与积分">
 *   <tr><th>qualityTier</th><th>含义</th><th>积分/次</th></tr>
 *   <tr><td>standard</td><td>标准画质</td><td>20</td></tr>
 *   <tr><td>hd（默认）</td><td>高清</td><td>35</td></tr>
 *   <tr><td>ultra</td><td>超清</td><td>55</td></tr>
 * </table>
 */
public final class ImageGenerationPointCosts {

    private ImageGenerationPointCosts() {}

    /** 工作台：{@code 1K} / {@code 2K} / {@code 4K}（大小写不敏感）。 */
    public static int forNanoBananaImageSize(String imageSize) {
        String s = imageSize == null ? "" : imageSize.strip().toUpperCase(Locale.ROOT);
        if ("1K".equals(s)) {
            return 18;
        }
        if ("4K".equals(s)) {
            return 52;
        }
        return 32;
    }

    /** 会话内技能出图：{@code standard} / {@code hd} / {@code ultra}（大小写不敏感）。 */
    public static int forConversationImageQualityTier(String qualityTier) {
        String s = qualityTier == null ? "" : qualityTier.strip().toLowerCase(Locale.ROOT);
        if ("standard".equals(s)) {
            return 20;
        }
        if ("ultra".equals(s)) {
            return 55;
        }
        return 35;
    }
}
