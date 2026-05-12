package top.uigpt.service;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import top.uigpt.dto.PromptTemplateResponse;
import top.uigpt.dto.PromptTemplateWriteRequest;
import top.uigpt.entity.PromptTemplate;
import top.uigpt.repository.PromptTemplateRepository;

import java.util.List;

@Service
@RequiredArgsConstructor
public class PromptTemplateService {

    private static final int MAX_PAGE_SIZE = 500;

    private final PromptTemplateRepository promptTemplateRepository;

    public List<PromptTemplateResponse> listAll(int page, int size) {
        int sz = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        int p = Math.max(0, page);
        return promptTemplateRepository
                .findAllByOrderByUpdatedAtDesc(PageRequest.of(p, sz))
                .map(this::toResponse)
                .getContent();
    }

    @Transactional
    public PromptTemplateResponse create(PromptTemplateWriteRequest req) {
        PromptTemplate row = new PromptTemplate();
        row.setTitle(req.getTitle().strip());
        row.setBody(req.getBody() != null ? req.getBody() : "");
        promptTemplateRepository.save(row);
        return toResponse(row);
    }

    @Transactional
    public PromptTemplateResponse update(long id, PromptTemplateWriteRequest req) {
        PromptTemplate row =
                promptTemplateRepository
                        .findById(id)
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "提示词不存在"));
        row.setTitle(req.getTitle().strip());
        row.setBody(req.getBody() != null ? req.getBody() : "");
        promptTemplateRepository.save(row);
        return toResponse(row);
    }

    @Transactional
    public void delete(long id) {
        PromptTemplate row =
                promptTemplateRepository
                        .findById(id)
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "提示词不存在"));
        promptTemplateRepository.delete(row);
    }

    private PromptTemplateResponse toResponse(PromptTemplate row) {
        return new PromptTemplateResponse(
                row.getId(), row.getTitle(), row.getBody(), row.getCreatedAt(), row.getUpdatedAt());
    }
}
