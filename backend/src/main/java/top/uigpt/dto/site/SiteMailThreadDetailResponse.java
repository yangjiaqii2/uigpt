package top.uigpt.dto.site;

import lombok.Builder;
import lombok.Value;

import java.util.List;

@Value
@Builder
public class SiteMailThreadDetailResponse {
    Long threadId;
    List<SiteMailMessageResponse> messages;
}
