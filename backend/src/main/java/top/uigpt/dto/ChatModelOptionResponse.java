package top.uigpt.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ChatModelOptionResponse {

    private Long id;
    private String displayName;
    private String provider;
}
