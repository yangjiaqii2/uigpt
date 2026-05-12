package top.uigpt.cli;

import top.uigpt.crypto.ModelKeyCryptoUtil;

/**
 * 生成写入 {@code chat_models.api_key_cipher} 的密文。
 *
 * <pre>
 *   export UIGPT_MODEL_KEY_MASTER='足够长的随机串'
 *   cd backend && mvn -q exec:java -Dexec.mainClass=top.uigpt.cli.EncryptModelApiKeyCli -Dexec.args='sk-xxxx'
 * </pre>
 *
 * 将输出的单行 Base64 写入数据库 {@code UPDATE chat_models SET api_key_cipher='...' WHERE id=1;}
 */
public final class EncryptModelApiKeyCli {

    private EncryptModelApiKeyCli() {}

    public static void main(String[] args) {
        if (args.length != 1) {
            System.err.println(
                    "用法: UIGPT_MODEL_KEY_MASTER='<主密钥>' mvn -q exec:java \\");
            System.err.println(
                    "        -Dexec.mainClass=top.uigpt.cli.EncryptModelApiKeyCli -Dexec.args='<明文API Key>'");
            System.exit(1);
        }
        String master = System.getenv("UIGPT_MODEL_KEY_MASTER");
        if (master == null || master.isBlank()) {
            System.err.println("请先设置环境变量 UIGPT_MODEL_KEY_MASTER（与运行后端解密时使用同一值）");
            System.exit(2);
        }
        try {
            System.out.println(ModelKeyCryptoUtil.encrypt(master, args[0]));
        } catch (Exception e) {
            System.err.println(e.getMessage());
            System.exit(3);
        }
    }
}
