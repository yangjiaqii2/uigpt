package top.uigpt.imagestudio.orchestration;

import top.uigpt.service.ApiYiImageService.NanoBananaInlineImage;

import java.util.List;

/** 图像工具执行：封装 Nano Banana 文生图 / 编辑网关调用。 */
public interface ImageToolExecutor {

    byte[] nanoBananaTextToImage(String promptForApi, String aspectRatio, String imageSize);

    byte[] nanoBananaEditImages(
            String promptForApi,
            List<NanoBananaInlineImage> images,
            String aspectRatio,
            String imageSize);
}
