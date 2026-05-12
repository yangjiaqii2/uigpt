package top.uigpt.controller;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.Valid;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import top.uigpt.dto.ImageStudioGenerateResponse;
import top.uigpt.dto.Sora2SubmitRequest;
import top.uigpt.dto.Sora2SubmitResponse;
import top.uigpt.dto.VideoStudioFinalizeRequest;
import top.uigpt.entity.ChatConversationImage;
import top.uigpt.service.ApiYiImageService;
import top.uigpt.service.ConversationImageService;
import top.uigpt.service.JwtService;
import top.uigpt.service.ObjectStorageService;

/**
 * 视频创作工作台：代理 API 易 Sora 2 官转（OpenAI 兼容 {@code /v1/videos}），异步任务 + 落库 COS。
 */
@RestController
@RequestMapping("/api/video-studio")
@RequiredArgsConstructor
public class VideoStudioController {

    private final JwtService jwtService;
    private final ApiYiImageService apiYiImageService;
    private final ConversationImageService conversationImageService;
    private final ObjectStorageService objectStorageService;

    @PostMapping("/sora2/submit")
    public Sora2SubmitResponse submitSora2(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @Valid @RequestBody Sora2SubmitRequest body) {
        requireUser(authorization);
        JsonNode created = apiYiImageService.sora2CreateVideoTask(body);
        return new Sora2SubmitResponse(
                created.path("id").asText(""),
                created.path("status").asText(""),
                created.path("progress").asInt(0),
                created.path("model").asText(body.getModel()),
                created.path("seconds").asText(body.getSeconds()),
                created.path("size").asText(body.getSize()));
    }

    /**
     * 图生视频：multipart 上传 {@code input_reference}，其余字段与 JSON 提交一致。
     */
    @PostMapping(value = "/sora2/submit-multipart", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Sora2SubmitResponse submitSora2Multipart(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestPart("prompt") String prompt,
            @RequestPart("model") String model,
            @RequestPart("seconds") String seconds,
            @RequestPart("size") String size,
            @RequestPart("input_reference") MultipartFile inputReference) {
        requireUser(authorization);
        if (inputReference == null || inputReference.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请上传参考图（字段名 input_reference）");
        }
        String ct = inputReference.getContentType();
        if (ct == null
                || !(ct.startsWith("image/jpeg")
                        || ct.startsWith("image/png")
                        || ct.startsWith("image/webp"))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "参考图仅支持 JPEG / PNG / WEBP");
        }
        final byte[] bytes;
        try {
            bytes = inputReference.getBytes();
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "读取上传文件失败");
        }
        JsonNode created =
                apiYiImageService.sora2CreateVideoTaskMultipart(
                        prompt,
                        model,
                        seconds,
                        size,
                        bytes,
                        inputReference.getOriginalFilename(),
                        ct);
        return new Sora2SubmitResponse(
                created.path("id").asText(""),
                created.path("status").asText(""),
                created.path("progress").asInt(0),
                created.path("model").asText(model),
                created.path("seconds").asText(seconds),
                created.path("size").asText(size));
    }

    @GetMapping("/sora2/tasks/{videoId}")
    public JsonNode sora2TaskStatus(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable("videoId") String videoId) {
        requireUser(authorization);
        return apiYiImageService.sora2RetrieveVideo(videoId);
    }

    @PostMapping("/sora2/tasks/{videoId}/finalize")
    public ImageStudioGenerateResponse sora2Finalize(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable("videoId") String videoId,
            @RequestBody(required = false) VideoStudioFinalizeRequest body) {
        String username = requireUser(authorization);
        JsonNode st = apiYiImageService.sora2RetrieveVideo(videoId);
        String status = st.path("status").asText("");
        if (!"completed".equals(status)) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "视频尚未完成，当前状态：" + (status.isEmpty() ? "unknown" : status));
        }
        byte[] mp4 = apiYiImageService.sora2DownloadVideoContent(videoId);
        String prompt = body != null ? body.getPrompt() : null;
        ChatConversationImage row =
                conversationImageService.persistVideoStudioGeneration(username, mp4, prompt);
        return new ImageStudioGenerateResponse(
                "video/mp4",
                null,
                row.getId(),
                objectStorageService.browserReadableUrl(row.getObjectKey()),
                row.isFavorite());
    }

    private String requireUser(String authorization) {
        String username = jwtService.parseUsername(authorization);
        if (username == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "请先登录");
        }
        return username;
    }
}
