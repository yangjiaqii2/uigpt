package top.uigpt.service;

import java.io.InputStream;

/** 会话图片等对象的存储抽象（当前实现为腾讯云 COS）。 */
public interface ObjectStorageService {

    boolean isReady();

    String getUnavailableReason();

    /** 浏览器可访问的完整 URL（不含路径编码已由调用方保证 key 安全） */
    String publicUrl(String objectKey);

    /**
     * 供前端直接加载（如 &lt;img src&gt;）的 URL。私有桶应返回预签名 GET；默认实现等同 {@link #publicUrl}。
     */
    default String browserReadableUrl(String objectKey) {
        return publicUrl(objectKey);
    }

    /** @return 对象键，如 conv/{conversationId}/{uuid}.jpg */
    String putConversationObject(
            Long conversationId, String extension, InputStream in, long size, String contentType);

    /** 图片创作工作台生成图：{@code studio/{userId}/{uuid}.ext} */
    String putStudioObject(long userId, String extension, InputStream in, long size, String contentType);

    /** 站内信附件：{@code site-mail/{threadId}/{uuid}.ext} */
    String putSiteMailObject(long threadId, String extension, InputStream in, long size, String contentType);

    void remove(String objectKey);

    /** 读取已上传对象的字节（用于会话内局部重绘等需服务端二次处理的场景）。 */
    byte[] getObjectBytes(String objectKey);
}
