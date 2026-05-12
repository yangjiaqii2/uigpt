package top.uigpt.dto.site;

import lombok.Builder;
import lombok.Value;

import java.time.LocalDateTime;
import java.util.List;

@Value
@Builder
public class SiteMailMessageResponse {
    long id;
    long threadId;
    long senderUserId;
    String senderUsername;
    String body;
    List<String> imageUrls;
    LocalDateTime createdAt;
    boolean fromSelf;
}
