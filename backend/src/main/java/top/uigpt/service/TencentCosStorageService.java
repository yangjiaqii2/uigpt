package top.uigpt.service;

import com.qcloud.cos.COSClient;
import com.qcloud.cos.ClientConfig;
import com.qcloud.cos.auth.BasicCOSCredentials;
import com.qcloud.cos.auth.COSCredentials;
import com.qcloud.cos.exception.CosClientException;
import com.qcloud.cos.exception.CosServiceException;
import com.qcloud.cos.http.HttpMethodName;
import com.qcloud.cos.http.HttpProtocol;
import com.qcloud.cos.model.COSObject;
import com.qcloud.cos.model.GeneratePresignedUrlRequest;
import com.qcloud.cos.model.GetObjectRequest;
import com.qcloud.cos.model.HeadBucketRequest;
import com.qcloud.cos.model.ObjectMetadata;
import com.qcloud.cos.model.PutObjectRequest;
import com.qcloud.cos.region.Region;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import top.uigpt.config.AppProperties;

import java.io.IOException;
import java.io.InputStream;
import java.util.Date;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class TencentCosStorageService implements ObjectStorageService {

    private final AppProperties appProperties;

    private COSClient cosClient;

    private volatile String initFailureDetail;

    @PostConstruct
    void init() {
        initFailureDetail = null;
        AppProperties.Cos cfg = appProperties.getCos();
        if (!cfg.isEnabled()) {
            log.info("腾讯云 COS 未启用（uigpt.cos.enabled=false），会话图片上传将返回 503");
            return;
        }
        String secretId = cfg.getSecretId() == null ? "" : cfg.getSecretId().trim();
        String secretKey = cfg.getSecretKey() == null ? "" : cfg.getSecretKey().trim();
        String region = cfg.getRegion() == null ? "" : cfg.getRegion().trim();
        String bucket = cfg.getBucket() == null ? "" : cfg.getBucket().trim();
        if (secretId.isEmpty() || secretKey.isEmpty() || region.isEmpty() || bucket.isEmpty()) {
            log.warn(
                    "COS 配置不完整：需要 COS_SECRET_ID、COS_SECRET_KEY、COS_REGION、COS_BUCKET（或非空 YAML）");
            return;
        }
        try {
            COSCredentials cred = new BasicCOSCredentials(secretId, secretKey);
            Region cosRegion = new Region(region);
            ClientConfig clientConfig = new ClientConfig(cosRegion);
            clientConfig.setHttpProtocol(HttpProtocol.https);
            this.cosClient = new COSClient(cred, clientConfig);
            if (cfg.isVerifyBucketOnStartup()) {
                cosClient.headBucket(new HeadBucketRequest(bucket));
                log.info("腾讯云 COS HeadBucket 校验通过，bucket={} region={}", bucket, region);
            } else {
                log.info(
                        "腾讯云 COS 客户端已就绪（未执行 HeadBucket，bucket={} region={}）。若上传仍报 403，请检查 CAM 是否包含 PutObject/GetObject/DeleteObject 及桶地域、桶名。",
                        bucket,
                        region);
            }
            initFailureDetail = null;
        } catch (Exception e) {
            shutdownQuietly();
            this.cosClient = null;
            initFailureDetail = summarizeInitFailure(e);
            log.error("腾讯云 COS 初始化失败：{}", initFailureDetail, e);
        }
    }

    @PreDestroy
    void destroy() {
        shutdownQuietly();
    }

    private void shutdownQuietly() {
        if (cosClient != null) {
            try {
                cosClient.shutdown();
            } catch (Exception e) {
                log.debug("COSClient shutdown: {}", e.toString());
            }
            cosClient = null;
        }
    }

    private static String summarizeInitFailure(Throwable e) {
        String raw = rootMessage(e);
        String lower = raw.toLowerCase();
        if (e instanceof CosServiceException cse) {
            int code = cse.getStatusCode();
            String hint403 =
                    code == 403
                            ? "常见原因：① 子账号 CAM 未授权（需 cos:PutObject/GetObject/DeleteObject，若开启启动校验还需 HeadBucket）；② COS_BUCKET 与控制台「完整名称」不一致或未含 APPID 后缀；③ COS_REGION 与桶所在地域不一致；④ 密钥不属于该存储桶所属主账号。可选：设置 COS_VERIFY_BUCKET_ON_START=false 跳过 HeadBucket。"
                            : "";
            return "腾讯云 COS 拒绝访问（HTTP "
                    + code
                    + "）：请核对 COS_SECRET_ID/COS_SECRET_KEY、桶名、地域与 CAM 策略。"
                    + hint403
                    + " 详情："
                    + truncate(raw, 320);
        }
        if (lower.contains("connection refused")
                || lower.contains("failed to connect")
                || lower.contains("timed out")
                || lower.contains("unreachable")) {
            return "无法连接腾讯云 COS：" + truncate(raw, 400);
        }
        if (e instanceof CosClientException) {
            return "COS 客户端异常：" + truncate(raw, 400);
        }
        return "COS 初始化异常：" + truncate(raw, 400);
    }

    private static String rootMessage(Throwable e) {
        Throwable c = e;
        while (c.getCause() != null && c.getCause() != c) {
            c = c.getCause();
        }
        String m = c.getMessage();
        return m != null && !m.isBlank() ? m : c.getClass().getSimpleName();
    }

    private static String truncate(String s, int max) {
        if (s == null || s.length() <= max) {
            return s == null ? "" : s;
        }
        return s.substring(0, max) + "…";
    }

    @Override
    public boolean isReady() {
        return appProperties.getCos().isEnabled() && cosClient != null;
    }

    @Override
    public String getUnavailableReason() {
        AppProperties.Cos cfg = appProperties.getCos();
        if (!cfg.isEnabled()) {
            return "对象存储未启用：已将 uigpt.cos.enabled 设为 false。文生图落库需启用 COS，请设为 true 并配置 COS_SECRET_ID、COS_SECRET_KEY 等。";
        }
        String secretId = cfg.getSecretId() == null ? "" : cfg.getSecretId().trim();
        String secretKey = cfg.getSecretKey() == null ? "" : cfg.getSecretKey().trim();
        String region = cfg.getRegion() == null ? "" : cfg.getRegion().trim();
        String bucket = cfg.getBucket() == null ? "" : cfg.getBucket().trim();
        if (secretId.isEmpty() || secretKey.isEmpty()) {
            return "对象存储未就绪：请配置环境变量 COS_SECRET_ID、COS_SECRET_KEY（腾讯云 API 密钥），然后重启后端。";
        }
        if (region.isEmpty()) {
            return "对象存储未就绪：请配置 COS_REGION（如 ap-guangzhou）。";
        }
        if (bucket.isEmpty()) {
            return "对象存储未就绪：请配置 COS_BUCKET（控制台完整存储桶名称，通常含 APPID 后缀）。";
        }
        if (initFailureDetail != null && !initFailureDetail.isBlank()) {
            return initFailureDetail;
        }
        return "对象存储未就绪：腾讯云 COS 初始化失败，请查看启动日志。";
    }

    /** 默认公有读域名：https://{bucket}.cos.{region}.myqcloud.com */
    private String effectivePublicPrefix() {
        AppProperties.Cos cfg = appProperties.getCos();
        String prefix = cfg.getPublicUrlPrefix() == null ? "" : cfg.getPublicUrlPrefix().trim();
        if (!prefix.isBlank()) {
            return prefix.replaceAll("/+$", "");
        }
        String bucket = cfg.getBucket().trim();
        String region = cfg.getRegion().trim();
        return "https://" + bucket + ".cos." + region + ".myqcloud.com";
    }

    @Override
    public String publicUrl(String objectKey) {
        return effectivePublicPrefix() + "/" + objectKey;
    }

    /**
     * 私有桶未开公有读时，默认域名会直接 403；预签名 GET 可在有效期内匿名访问。
     */
    @Override
    public String browserReadableUrl(String objectKey) {
        if (!isReady() || objectKey == null || objectKey.isBlank()) {
            return publicUrl(objectKey);
        }
        int sec = appProperties.getCos().getPresignedUrlExpirySeconds();
        if (sec < 60) {
            sec = 3600;
        }
        if (sec > 604800) {
            sec = 604800;
        }
        String bucket = appProperties.getCos().getBucket().trim();
        try {
            GeneratePresignedUrlRequest req =
                    new GeneratePresignedUrlRequest(bucket, objectKey.strip(), HttpMethodName.GET);
            req.setExpiration(new Date(System.currentTimeMillis() + sec * 1000L));
            return cosClient.generatePresignedUrl(req).toExternalForm();
        } catch (Exception e) {
            log.warn("生成 COS 预签名下载 URL 失败 bucket={} key={}，回退公有 URL", bucket, objectKey, e);
            return publicUrl(objectKey);
        }
    }

    @Override
    public String putConversationObject(
            Long conversationId, String extension, InputStream in, long size, String contentType) {
        if (!isReady()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, getUnavailableReason());
        }
        String ext = extension == null || extension.isBlank() ? ".bin" : extension;
        if (!ext.startsWith(".")) {
            ext = "." + ext;
        }
        String key = "conv/" + conversationId + "/" + UUID.randomUUID() + ext;
        String bucket = appProperties.getCos().getBucket().trim();
        ObjectMetadata meta = new ObjectMetadata();
        meta.setContentLength(size);
        meta.setContentType(
                contentType != null && !contentType.isBlank()
                        ? contentType
                        : "application/octet-stream");
        try {
            cosClient.putObject(new PutObjectRequest(bucket, key, in, meta));
            return key;
        } catch (Exception e) {
            log.warn("上传 COS 失败 bucket={} key={}", bucket, key, e);
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "图片上传失败，请检查腾讯云 COS 配置与权限");
        }
    }

    @Override
    public String putImageStudioSessionObject(
            long userId, long sessionId, String extension, InputStream in, long size, String contentType) {
        if (!isReady()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, getUnavailableReason());
        }
        String ext = extension == null || extension.isBlank() ? ".png" : extension;
        if (!ext.startsWith(".")) {
            ext = "." + ext;
        }
        String key = "image-studio-sessions/" + userId + "/" + sessionId + "/" + UUID.randomUUID() + ext;
        String bucket = appProperties.getCos().getBucket().trim();
        ObjectMetadata meta = new ObjectMetadata();
        meta.setContentLength(size);
        meta.setContentType(
                contentType != null && !contentType.isBlank()
                        ? contentType
                        : "application/octet-stream");
        try {
            cosClient.putObject(new PutObjectRequest(bucket, key, in, meta));
            return key;
        } catch (Exception e) {
            log.warn("上传 COS 失败 bucket={} key={}", bucket, key, e);
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "图片上传失败，请检查腾讯云 COS 配置与权限");
        }
    }

    @Override
    public String putStudioObject(
            long userId, String extension, InputStream in, long size, String contentType) {
        if (!isReady()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, getUnavailableReason());
        }
        String ext = extension == null || extension.isBlank() ? ".png" : extension;
        if (!ext.startsWith(".")) {
            ext = "." + ext;
        }
        String key = "studio/" + userId + "/" + UUID.randomUUID() + ext;
        String bucket = appProperties.getCos().getBucket().trim();
        ObjectMetadata meta = new ObjectMetadata();
        meta.setContentLength(size);
        meta.setContentType(
                contentType != null && !contentType.isBlank()
                        ? contentType
                        : "application/octet-stream");
        try {
            cosClient.putObject(new PutObjectRequest(bucket, key, in, meta));
            return key;
        } catch (Exception e) {
            log.warn("上传 COS 失败 bucket={} key={}", bucket, key, e);
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "图片上传失败，请检查腾讯云 COS 配置与权限");
        }
    }

    @Override
    public String putSiteMailObject(
            long threadId, String extension, InputStream in, long size, String contentType) {
        if (!isReady()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, getUnavailableReason());
        }
        String ext = extension == null || extension.isBlank() ? ".png" : extension;
        if (!ext.startsWith(".")) {
            ext = "." + ext;
        }
        String key = "site-mail/" + threadId + "/" + UUID.randomUUID() + ext;
        String bucket = appProperties.getCos().getBucket().trim();
        ObjectMetadata meta = new ObjectMetadata();
        meta.setContentLength(size);
        meta.setContentType(
                contentType != null && !contentType.isBlank()
                        ? contentType
                        : "application/octet-stream");
        try {
            cosClient.putObject(new PutObjectRequest(bucket, key, in, meta));
            return key;
        } catch (Exception e) {
            log.warn("上传 COS 失败 bucket={} key={}", bucket, key, e);
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "图片上传失败，请检查腾讯云 COS 配置与权限");
        }
    }

    @Override
    public void remove(String objectKey) {
        if (!isReady() || objectKey == null || objectKey.isBlank()) {
            return;
        }
        try {
            cosClient.deleteObject(appProperties.getCos().getBucket().trim(), objectKey);
        } catch (Exception e) {
            log.warn("删除 COS 对象失败 key={}", objectKey, e);
        }
    }

    @Override
    public byte[] getObjectBytes(String objectKey) {
        if (!isReady() || objectKey == null || objectKey.isBlank()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, getUnavailableReason());
        }
        String bucket = appProperties.getCos().getBucket().trim();
        String key = objectKey.strip();
        GetObjectRequest req = new GetObjectRequest(bucket, key);
        try (COSObject obj = cosClient.getObject(req);
                InputStream in = obj.getObjectContent()) {
            return in.readAllBytes();
        } catch (CosServiceException e) {
            log.warn("COS GetObject 失败 bucket={} key={} status={}", bucket, key, e.getStatusCode(), e);
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY, "读取对象存储图片失败，请稍后重试");
        } catch (IOException e) {
            log.warn("COS GetObject 读取流失败 key={}", key, e);
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY, "读取对象存储图片失败，请稍后重试");
        }
    }
}
