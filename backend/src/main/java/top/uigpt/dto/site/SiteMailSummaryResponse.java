package top.uigpt.dto.site;

import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class SiteMailSummaryResponse {
    long unreadCount;
    Long threadId;
}
