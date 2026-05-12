package top.uigpt.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import top.uigpt.dto.ChatModelOptionResponse;
import top.uigpt.repository.ChatModelRepository;

import java.util.List;

@RestController
@RequestMapping("/api/chat")
@RequiredArgsConstructor
public class ChatModelsController {

    private final ChatModelRepository chatModelRepository;

    /** 对话页可选模型列表（仅启用项）；内容由表 chat_models 维护 */
    @GetMapping("/models")
    public List<ChatModelOptionResponse> listEnabledModels() {
        return chatModelRepository.findByEnabledTrueOrderBySortOrderAscIdAsc().stream()
                .map(
                        m ->
                                new ChatModelOptionResponse(
                                        m.getId(), m.getDisplayName(), m.getProvider()))
                .toList();
    }
}
