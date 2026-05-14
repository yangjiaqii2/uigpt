package top.uigpt.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 图片工作台「双候选」中的单路结果：成功时带落库 id 与预览；失败时仅 {@link #ok}=false。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ImageStudioSlotResult {

    private boolean ok;

    /** 失败时简短说明（成功时为 null） */
    private String errorMessage;

    private String mimeType;

    /** 不含 data: 前缀的 Base64；失败时为 null */
    private String imageBase64;

    private Long imageId;

    private String imageUrl;

    private boolean favorite;

    /** 为 true 时 {@link #imageId} 为图片会话内图片 id */
    private boolean sessionImage;
}
