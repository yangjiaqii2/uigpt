package top.uigpt.imagestudio.orchestration;

import top.uigpt.imagestudio.orchestration.model.IntentArtifact;

/** 意图识别：输出结构化意图 JSON（供 RAG 查询句与第三阶段策略）。 */
public interface IntentRouter {

    IntentArtifact route(String mergedForModel, String aspectRatio, String imageSize, String studioSkillId);
}
