package top.uigpt.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ImageStudioGenerateResponse {

    private String mimeType;

    /** PNG/JPEG 原始字节的 Base64（不含 data: 前缀） */
    private String imageBase64;

    /** 落库后的 id，收藏接口使用 */
    private Long imageId;

    /** COS 可访问 URL（与 chat 图片一致，可能为预签名） */
    private String imageUrl;

    private boolean favorite;

    /**
     * 为 true 时 {@link #imageId} 指向 {@code image_studio_session_images}，不得调用「我的作品」收藏接口（仅 chat 图片表）。
     */
    private boolean sessionImage;
}
