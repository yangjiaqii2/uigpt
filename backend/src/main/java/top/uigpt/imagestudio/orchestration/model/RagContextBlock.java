package top.uigpt.imagestudio.orchestration.model;

/** 第二阶段向量检索得到的知识块（含固定头），可能为空串。 */
public record RagContextBlock(String blockText) {}
