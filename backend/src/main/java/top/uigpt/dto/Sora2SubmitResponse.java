package top.uigpt.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 已创建的上游视频任务（仅入队，需轮询后 finalize 落库） */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Sora2SubmitResponse {

    private String videoId;
    private String status;
    private int progress;
    private String model;
    private String seconds;
    private String size;
}
