package top.uigpt.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "uigpt")
public class AppProperties {

    private Jwt jwt = new Jwt();
    private Recaptcha recaptcha = new Recaptcha();
    private RegisterRateLimit registerRateLimit = new RegisterRateLimit();
    private Ai ai = new Ai();
    /** 对话是否原样转发上游（不注入记忆/系统提示/技能上下文，不做识图摘要） */
    private Chat chat = new Chat();
    /**
     * APIYi OpenAI 兼容网关（对话转发 / 文生图 / 识图等）。密钥勿提交仓库。
     */
    private ApiYiImage apiYiImage = new ApiYiImage();
    /** 腾讯云 COS：会话生成图持久化 */
    private Cos cos = new Cos();
    /** 后台管理：可访问 {@code /api/admin/**} 的用户名（逗号分隔，大小写不敏感） */
    private Admin admin = new Admin();

    /** 登录用户积分：日重置（上海时区）与对话等扣费；关闭则不扣、不重置 */
    private Points points = new Points();

    /**
     * 向量检索增强（Qdrant + OpenAI 兼容 embeddings）。关闭或未配全时对话行为不变。
     *
     * <p>环境变量前缀 {@code UIGPT_RAG_*}，与 {@code application.yml} 的 {@code uigpt.rag} 一一对应（如 {@code
     * UIGPT_RAG_ENABLED}、{@code UIGPT_RAG_QDRANT_URL}、{@code UIGPT_RAG_EMBEDDING_BASE_URL} 等）。知识库表结构见资源
     * {@code db/knowledge_documents.mysql.sql}。
     */
    private Rag rag = new Rag();

    /**
     * 解密 {@code chat_models.api_key_cipher} 的主密钥（SHA-256 后作 AES-256 密钥）。
     * 环境变量：{@code UIGPT_MODEL_KEY_MASTER}；须与加密 CLI 使用同一值。
     */
    private String modelKeyMasterSecret = "";

    @Data
    public static class Jwt {
        private String secret = "";
        private long expirationMs = 86_400_000L;
    }

    @Data
    public static class Recaptcha {
        /** 启用 Google reCAPTCHA v3（注册接口） */
        private boolean enabled = false;
        /** 前端站点密钥（可公开） */
        private String siteKey = "";
        /** 服务端密钥（勿泄露） */
        private String secretKey = "";
        /** v3 分数阈值，0～1，默认 0.5 */
        private double minScore = 0.5;
    }

    @Data
    public static class RegisterRateLimit {
        /** 同一 IP 滚动 1 小时内最多成功注册次数 */
        private int maxPerHour = 2;
        /** 同一 IP 滚动 24 小时内最多成功注册次数 */
        private int maxPer24Hours = 3;
    }

    @Data
    public static class Chat {
        /**
         * {@code true}：客户端 {@code messages} 不经服务端改写即调用上游；回复仅解析上游 SSE/JSON 中的正文增量。
         * {@code false}：恢复注入简体中文系统提示、技能上下文、会话记忆与识图预处理等历史逻辑。
         */
        private boolean passthrough = true;
    }

    @Data
    public static class Ai {
        private String apiKey = "";
        private String baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1";
        private String model = "qwen-turbo";
        /** 单次模型回复上限（传给上游 max_tokens） */
        private int maxOutputTokens = 2048;
    }

    @Data
    public static class ApiYiImage {
        /** 为 false 时关闭 APIYi 文生图等可选能力（对话转发仍可由单独开关或密钥空白控制） */
        private boolean enabled = false;
        /** 如 https://api.apiyi.com */
        private String baseUrl = "https://api.apiyi.com";
        /** Bearer Token，如 sk-... */
        private String apiKey = "";
        /** OpenAI 兼容文生图模型：<code>/v1/images/generations</code> */
        private String model = "gpt-image-2-all";
        /**
         * 高速纯文生图是否在「OpenAI generations」与「Gemini generateContent」之间加权轮流调用。
         */
        private boolean rotateTtiProviders = true;
        /**
         * 加权轮流时 OpenAI（GPT 图）权重，默认 3；与 {@link #ttiGeminiWeight} 合计为周期。
         * 例如 3:1 表示约 75% 走 GPT、25% 走 Gemini。
         */
        private int ttiOpenAiWeight = 3;
        /** 加权轮流时 Gemini 权重，默认 1 */
        private int ttiGeminiWeight = 1;
        /** Gemini 图模 id（不含路径），用于 <code>/v1beta/models/{id}:generateContent</code> */
        private String geminiImageModel = "gemini-3.1-flash-image-preview";
        /** Gemini <code>imageConfig.imageSize</code>，如 2K */
        private String geminiImageSize = "2K";

        /**
         * Nano Banana Pro（文档）：{@code gemini-3-pro-image-preview}，{@code POST
         * /v1beta/models/{id}:generateContent} 文生图 / 图片编辑。
         */
        private String nanoBananaImageModel = "gemini-3-pro-image-preview";
        /**
         * {@code /v1/images/generations} 与 {@code /v1/images/edits} 的 {@code response_format}：{@code url}（R2，概览默认）
         * 或 {@code b64_json}（完整 data URL）。服务端落 COS 时推荐 {@code url} 下载字节，避免巨型 JSON。
         */
        private String generationsResponseFormat = "url";
        /**
         * {@code gpt-image-2-vip} 的 {@code size} 档位（文档 10 比例 × 三档）：{@code 1k}（Fast）、{@code 2k}
         *（Recommended，默认）、{@code 4k}（Detail）。亦可写 {@code fast} / {@code detail}。
         */
        private String vipSizeTier = "2k";

        /**
         * 是否启用 API易「识图」预分析（{@code /v1/chat/completions} + 视觉模型）。需 {@link #enabled} 且配置
         * {@link #apiKey}。
         */
        private boolean visionEnabled = true;

        /** 识图模型 id，如 {@code gpt-4.1-mini}、{@code gpt-4o}（见 API易文档） */
        private String visionModel = "gpt-4.1-mini";

        /** 识图单次输出上限 */
        private int visionMaxTokens = 1200;

        /**
         * 图片工作台「提示词优化」chat 模型（OpenAI 兼容）；留空则使用 {@link #visionModel}。
         */
        private String promptOptimizeModel = "";

        /** 提示词优化单次输出 token 上限 */
        private int promptOptimizeMaxTokens = 640;

        /**
         * 参考图是否以 OpenAI 多模态格式直接并入主对话（省去单独的识图摘要请求，显著降低带图场景的首包延迟）。
         * 关闭则恢复「先 vision 模型摘要 → 再主模型」。
         */
        private boolean visionInlineMultimodal = true;
    }

    @Data
    public static class Admin {
        /**
         * 逗号分隔用户名，如 {@code admin,root}。留空则无人可访问用户管理接口（须配置环境变量 {@code
         * UIGPT_ADMIN_USERNAMES}）。
         */
        private String usernamesCsv = "";
    }

    @Data
    public static class Points {
        /** 为 false 时不扣费、不执行上海日历日重置；GET /api/me 仍返回库内 points */
        private boolean enabled = true;
        /** 普通对话单次扣费（深度推理见 {@link #chatTurnDeepCost}） */
        private int chatTurnCost = 5;
        private int chatTurnDeepCost = 10;
    }

    @Data
    public static class Cos {
        /** 关闭时不写对象存储（上传接口返回 503） */
        private boolean enabled = true;
        /** 腾讯云 API 密钥 SecretId（环境变量 COS_SECRET_ID） */
        private String secretId = "";
        /** 腾讯云 API 密钥 SecretKey（环境变量 COS_SECRET_KEY） */
        private String secretKey = "";
        /** 地域，如 ap-guangzhou、ap-beijing */
        private String region = "ap-guangzhou";
        /** 存储桶名称（控制台完整名，通常含 APPID 后缀） */
        private String bucket = "";
        /**
         * 浏览器访问对象的 URL 前缀，末尾不要斜杠；留空则用默认
         * {@code https://{bucket}.cos.{region}.myqcloud.com}（须控制台配置公有读或 CDN）
         */
        private String publicUrlPrefix = "";
        /**
         * 启动时调用 HeadBucket；子账号 CAM 若未包含 HeadBucket 会 403，可关闭此项仅在上传时校验。
         */
        private boolean verifyBucketOnStartup = false;
        /**
         * 私有桶场景：返回给前端的预签名 GET 有效期（秒）。桶为公有读时亦可用，每次列表/详情会刷新签名链接。
         */
        private int presignedUrlExpirySeconds = 86400;
    }

    @Data
    public static class Rag {
        /**
         * 总开关；为 true 且 Qdrant/向量模型 URL 与密钥、集合名均有效时才在对话中注入检索上下文。知识库 CRUD 写入亦依赖此配置以完成
         * embedding 与 Qdrant upsert。
         */
        private boolean enabled = false;
        /** Qdrant HTTP 根地址，如 {@code http://localhost:6333}，勿带尾斜杠 */
        private String qdrantUrl = "http://localhost:6333";
        /** Qdrant Cloud 等场景的 API Key；本地可留空 */
        private String qdrantApiKey = "";
        /**
         * 默认集合名（须已存在且向量维度与 {@link #embeddingModel} 一致）。仅允许字母数字下划线与短横线。
         */
        private String collection = "uigpt_kb";
        /** OpenAI 兼容 {@code POST {base}/embeddings} 的根 URL，须含 {@code /v1}，如 API易 {@code https://api.apiyi.com/v1} */
        private String embeddingBaseUrl = "";
        /** 调用 embedding 接口的 Bearer */
        private String embeddingApiKey = "";
        /** 如 {@code text-embedding-3-small}、{@code text-embedding-v4}（以网关为准） */
        private String embeddingModel = "text-embedding-3-small";
        /** 单次对话检索条数 */
        private int topK = 5;
        /**
         * 最低 cosine 相似度阈值，0～1；0 表示不过滤。若网关返回的是距离而非相似度，请保持 0。
         */
        private double minScore = 0.0;
        /** 用于 embedding 的用户问题最大字符数（防止超长） */
        private int maxQueryChars = 8000;
        /** 访问 Qdrant 与 embedding 的 HTTP 读超时（秒） */
        private int readTimeoutSeconds = 45;
    }
}
