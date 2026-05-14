package top.uigpt.imagestudio.orchestration;

/** 对候选 PNG 字节打分；分数越高越优。 */
public interface ImageCandidateJudge {

    double score(byte[] pngBytes, String userGoalText);
}
