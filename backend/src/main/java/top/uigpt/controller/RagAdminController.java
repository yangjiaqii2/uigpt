package top.uigpt.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import top.uigpt.dto.RagDocumentCreateRequest;
import top.uigpt.dto.RagDocumentDetailResponse;
import top.uigpt.dto.RagDocumentPageResponse;
import top.uigpt.dto.RagImportResponse;
import top.uigpt.dto.RagUpsertRequest;
import top.uigpt.dto.RagUpsertResponse;
import top.uigpt.service.AdminAuthorizationService;
import top.uigpt.service.JwtService;
import top.uigpt.service.KnowledgeDocumentService;

@RestController
@RequestMapping("/api/admin/rag")
@RequiredArgsConstructor
public class RagAdminController {

    private final JwtService jwtService;
    private final AdminAuthorizationService adminAuthorizationService;
    private final KnowledgeDocumentService knowledgeDocumentService;

    @GetMapping("/documents")
    public RagDocumentPageResponse listDocuments(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        String username = requireUser(authorization);
        adminAuthorizationService.requireSuperAdmin(username);
        return knowledgeDocumentService.list(page, size);
    }

    @GetMapping("/documents/{pointId}")
    public RagDocumentDetailResponse getDocument(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable("pointId") String pointId) {
        String username = requireUser(authorization);
        adminAuthorizationService.requireSuperAdmin(username);
        return knowledgeDocumentService.getByPointId(pointId);
    }

    @PostMapping("/documents")
    public RagUpsertResponse createDocument(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @Valid @RequestBody RagDocumentCreateRequest body) {
        String username = requireUser(authorization);
        adminAuthorizationService.requireSuperAdmin(username);
        knowledgeDocumentService.create(body.getTitle(), body.getText());
        return new RagUpsertResponse(1);
    }

    @DeleteMapping("/documents/{pointId}")
    public void deleteDocument(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable("pointId") String pointId) {
        String username = requireUser(authorization);
        adminAuthorizationService.requireSuperAdmin(username);
        knowledgeDocumentService.deleteByPointId(pointId);
    }

    @PostMapping(value = "/documents/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public RagImportResponse importDocuments(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestParam(value = "files", required = false) MultipartFile[] files) {
        String username = requireUser(authorization);
        adminAuthorizationService.requireSuperAdmin(username);
        MultipartFile[] arr = files == null ? new MultipartFile[0] : files;
        int n = knowledgeDocumentService.importFiles(arr);
        return new RagImportResponse(n);
    }

    /**
     * 兼容旧接口：多条纯文本各建一条记录并写入 Qdrant。
     */
    @PostMapping("/upsert")
    public RagUpsertResponse upsert(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @Valid @RequestBody RagUpsertRequest body) {
        String username = requireUser(authorization);
        adminAuthorizationService.requireSuperAdmin(username);
        try {
            int n = knowledgeDocumentService.batchFromPlainTexts(body.getTexts());
            return new RagUpsertResponse(n);
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    private String requireUser(String authorization) {
        String u = jwtService.parseUsername(authorization);
        if (u == null || u.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "未登录或令牌无效");
        }
        return u;
    }
}
