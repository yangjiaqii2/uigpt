package top.uigpt.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ImageStudioSessionImageDto {

    private Long id;

    private String imageUrl;

    private String userPrompt;

    private LocalDateTime createdAt;
}
