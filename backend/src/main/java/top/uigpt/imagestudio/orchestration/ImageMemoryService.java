package top.uigpt.imagestudio.orchestration;

/** 将当前图片会话内的多轮摘要拼入模型 prompt；落库仍只用用户原文（由调用方保证）。 */
public interface ImageMemoryService {

    String mergeForApi(String userPrompt, String sessionContext);
}
