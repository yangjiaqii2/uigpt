package top.uigpt.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import top.uigpt.config.AppProperties;
import top.uigpt.crypto.ModelKeyCryptoUtil;

@Service
@RequiredArgsConstructor
public class ModelApiKeyCipherService {

    private final AppProperties appProperties;

    public boolean isMasterConfigured() {
        String s = appProperties.getModelKeyMasterSecret();
        return s != null && !s.isBlank();
    }

    /** 解密数据库中的 api_key_cipher；调用方需保证密文非空且主密钥已配置 */
    public String decryptStored(String ciphertextBase64) {
        String master = appProperties.getModelKeyMasterSecret();
        if (master == null || master.isBlank()) {
            throw new IllegalStateException("未配置 UIGPT_MODEL_KEY_MASTER");
        }
        return ModelKeyCryptoUtil.decrypt(master, ciphertextBase64);
    }

    /** 运维可在本地启动应用后通过调试或单独 CLI 加密；此处供扩展管理接口使用 */
    public String encryptForStorage(String plaintextApiKey) {
        String master = appProperties.getModelKeyMasterSecret();
        if (master == null || master.isBlank()) {
            throw new IllegalStateException("未配置 UIGPT_MODEL_KEY_MASTER");
        }
        return ModelKeyCryptoUtil.encrypt(master, plaintextApiKey);
    }
}
