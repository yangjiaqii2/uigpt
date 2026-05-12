package top.uigpt.dto;

import lombok.Data;

/** 视频工作台 · Sora 成片 finalize 时可选带回提示词，写入对话记录 */
@Data
public class VideoStudioFinalizeRequest {

    /** 与提交任务时一致的文案，便于在历史会话中还原 */
    private String prompt;
}
