package top.uigpt.imagestudio.orchestration;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import top.uigpt.service.ApiYiImageService;
import top.uigpt.service.ApiYiImageService.NanoBananaInlineImage;

import java.util.List;

@Service
@RequiredArgsConstructor
public class DefaultImageToolExecutor implements ImageToolExecutor {

    private final ApiYiImageService apiYiImageService;

    @Override
    public byte[] nanoBananaTextToImage(String promptForApi, String aspectRatio, String imageSize) {
        return apiYiImageService.nanoBananaTextToImage(promptForApi, aspectRatio, imageSize);
    }

    @Override
    public byte[] nanoBananaEditImages(
            String promptForApi,
            List<NanoBananaInlineImage> images,
            String aspectRatio,
            String imageSize) {
        return apiYiImageService.nanoBananaEditImages(promptForApi, images, aspectRatio, imageSize);
    }
}
