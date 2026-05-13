package top.uigpt.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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
import top.uigpt.dto.RagDocumentBatchDeleteRequest;
import top.uigpt.dto.RagDocumentBatchDeleteResponse;
import top.uigpt.dto.RagDocumentCreateRequest;
import top.uigpt.dto.RagDocumentDetailResponse;
import top.uigpt.dto.RagDocumentPageResponse;
import top.uigpt.dto.RagImportTaskAcceptedResponse;
import top.uigpt.dto.RagImportTaskStatusResponse;
import top.uigpt.dto.RagUpsertRequest;
import top.uigpt.dto.RagUpsertResponse;
import top.uigpt.multipart.ByteArrayMultipartFile;
import top.uigpt.service.AdminAuthorizationService;
import top.uigpt.service.JwtService;
import top.uigpt.service.KnowledgeDocumentService;
import top.uigpt.service.KnowledgeImportJobService;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

@RestController
@RequestMapping("/api/admin/rag")
@RequiredArgsConstructor
public class RagAdminController {

    private final JwtService jwtService;
    private final AdminAuthorizationService adminAuthorizationService;
    private final KnowledgeDocumentService knowledgeDocumentService;
    private final KnowledgeImportJobService knowledgeImportJobService;

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

    @PostMapping("/documents/batch-delete")
    public RagDocumentBatchDeleteResponse batchDeleteDocuments(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @Valid @RequestBody RagDocumentBatchDeleteRequest body) {
        String username = requireUser(authorization);
        adminAuthorizationService.requireSuperAdmin(username);
        return knowledgeDocumentService.deleteByPointIds(body.getPointIds());
    }

    @PostMapping(value = "/documents/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<RagImportTaskAcceptedResponse> importDocuments(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestParam(value = "files", required = false) MultipartFile[] files) {
        String username = requireUser(authorization);
        adminAuthorizationService.requireSuperAdmin(username);
        MultipartFile[] arr = files == null ? new MultipartFile[0] : files;
        MultipartFile[] materialized;
        try {
            materialized = materializeUploads(arr);
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "读取上传文件失败: " + e.getMessage());
        }
        RagImportTaskAcceptedResponse body = knowledgeImportJobService.submit(materialized);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(body);
    }

    @GetMapping("/documents/import-tasks/{taskId}")
    public RagImportTaskStatusResponse getImportTask(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable("taskId") String taskId) {
        String username = requireUser(authorization);
        adminAuthorizationService.requireSuperAdmin(username);
        return knowledgeImportJobService.getStatus(taskId);
    }

    /**
     * 在请求线程内读完字节并剥离路径，避免异步执行时原始 {@link MultipartFile} 已失效或路径穿越。
     */
    private static MultipartFile[] materializeUploads(MultipartFile[] files) throws IOException {
        List<MultipartFile> out = new ArrayList<>();
        for (MultipartFile f : files) {
            if (f == null || f.isEmpty()) {
                continue;
            }
            String original = safeBasename(f.getOriginalFilename());
            byte[] bytes = f.getBytes();
            out.add(new ByteArrayMultipartFile("files", original, f.getContentType(), bytes));
        }
        return out.toArray(MultipartFile[]::new);
    }

    private static String safeBasename(String originalFilename) {
        if (originalFilename == null || originalFilename.isBlank()) {
            return "upload";
        }
        String s = originalFilename.strip();
        int i = Math.max(s.lastIndexOf('/'), s.lastIndexOf('\\'));
        String base = i >= 0 ? s.substring(i + 1) : s;
        if (base.isBlank() || ".".equals(base) || "..".equals(base)) {
            return "upload";
        }
        return base;
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
