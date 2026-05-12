package top.uigpt.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ConversationSummaryResponse {

    private Long id;
    private String title;
    private LocalDateTime updatedAt;
    /** 本会话消息条数（用于侧栏展示） */
    private int messageCount;
    /** 是否置顶 */
    private boolean pinned;
}
