package top.uigpt.dto;

import java.util.List;

/** 上游 chat completions 流式调用前已解析完毕的上下文（不含会话落库）。 */
public record PreparedChatStreamContext(String completionsUrl, String bearerApiKey, List<String> streamRequestJsonBodies) {}
