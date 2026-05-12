package top.uigpt.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import top.uigpt.dto.RagDocumentDetailResponse;
import top.uigpt.dto.RagDocumentListItemResponse;
import top.uigpt.dto.RagDocumentPageResponse;
import top.uigpt.entity.KnowledgeDocument;
import top.uigpt.repository.KnowledgeDocumentRepository;

import java.nio.charset.StandardCharsets;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeDocumentService {

    private static final int PREVIEW_MAX = 200;
    private static final int MAX_CHUNKS_PER_FILE = 50;
    private static final int MAX_IMPORT_TOTAL = 200;
    private static final int MAX_FILES = 10;
    private static final DateTimeFormatter ISO_LDT = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    private final KnowledgeDocumentRepository repository;
    private final RagService ragService;

    @Transactional(readOnly = true)
    public RagDocumentPageResponse list(int page, int size) {
        int p = Math.max(0, page);
        int s = Math.min(100, Math.max(1, size));
        Page<KnowledgeDocument> pg =
                repository.findAllByOrderByCreatedAtDesc(PageRequest.of(p, s));
        List<RagDocumentListItemResponse> content =
                pg.getContent().stream().map(this::toListItem).toList();
        return RagDocumentPageResponse.builder()
                .content(content)
                .totalElements(pg.getTotalElements())
                .totalPages(pg.getTotalPages())
                .number(pg.getNumber())
                .size(pg.getSize())
                .build();
    }

    @Transactional(readOnly = true)
    public RagDocumentDetailResponse getByPointId(String pointId) {
        String pid = requirePointId(pointId);
        KnowledgeDocument doc =
                repository
                        .findByPointId(pid)
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "条目不存在"));
        return toDetail(doc);
    }

    public String create(String title, String text) {
        if (text == null || text.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "正文不能为空");
        }
        String id = UUID.randomUUID().toString();
        try {
            ragService.upsertKnowledgePoint(id, title, text);
        } catch (IllegalArgumentException | IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
        try {
            KnowledgeDocument d = new KnowledgeDocument();
            d.setPointId(id);
            d.setTitle(blankToNull(title));
            d.setContent(text.strip());
            repository.save(d);
            return id;
        } catch (Exception e) {
            log.warn("知识库 MySQL 保存失败，尝试删除 Qdrant pointId={}", id, e);
            try {
                ragService.deleteKnowledgePoints(List.of(id));
            } catch (Exception ex) {
                log.warn("回滚 Qdrant 失败 pointId={}", id, ex);
            }
            throw new ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR, "保存知识库条目失败，请稍后重试");
        }
    }

    @Transactional
    public void deleteByPointId(String pointId) {
        String pid = requirePointId(pointId);
        if (!repository.existsByPointId(pid)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "条目不存在");
        }
        try {
            ragService.deleteKnowledgePoints(List.of(pid));
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
        repository.deleteByPointId(pid);
    }

    /** 兼容旧接口：每条创建 MySQL + Qdrant。 */
    public int batchFromPlainTexts(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            return 0;
        }
        int n = 0;
        for (String raw : texts) {
            if (raw == null) {
                continue;
            }
            String t = raw.strip();
            if (t.isEmpty()) {
                continue;
            }
            try {
                create(null, t);
                n++;
            } catch (ResponseStatusException e) {
                log.warn("批量写入跳过一条: {}", e.getReason());
            }
        }
        return n;
    }

    public int importFiles(MultipartFile[] files) {
        if (files == null || files.length == 0) {
            return 0;
        }
        int fileCount = Math.min(files.length, MAX_FILES);
        int total = 0;
        for (int fi = 0; fi < fileCount && total < MAX_IMPORT_TOTAL; fi++) {
            MultipartFile f = files[fi];
            if (f == null || f.isEmpty()) {
                continue;
            }
            String name = f.getOriginalFilename() != null ? f.getOriginalFilename() : "upload";
            String ext = extension(name).toLowerCase(Locale.ROOT);
            String raw;
            try {
                raw = new String(f.getBytes(), StandardCharsets.UTF_8);
            } catch (Exception e) {
                log.warn("读取上传文件失败 {}", name, e);
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "无法读取文件: " + name);
            }
            int budget = MAX_IMPORT_TOTAL - total;
            int added;
            if (".csv".equals(ext)) {
                added = importCsvContent(raw, budget);
            } else {
                added = importTxtMdContent(raw, basename(name), budget);
            }
            total += added;
        }
        return total;
    }

    private int importTxtMdContent(String text, String filenameStem, int budget) {
        if (budget <= 0) {
            return 0;
        }
        String s = text.strip();
        if (s.isEmpty()) {
            return 0;
        }
        List<String> chunks = new ArrayList<>();
        for (String part : s.split("\n\\s*\n+")) {
            String p = part.strip();
            if (!p.isEmpty()) {
                chunks.add(p);
            }
        }
        if (chunks.isEmpty()) {
            return 0;
        }
        if (chunks.size() > MAX_CHUNKS_PER_FILE) {
            chunks = new ArrayList<>(chunks.subList(0, MAX_CHUNKS_PER_FILE));
        }
        if (chunks.size() > budget) {
            chunks = new ArrayList<>(chunks.subList(0, budget));
        }
        int n = 0;
        if (chunks.size() == 1) {
            String stem = filenameStem != null ? filenameStem.strip() : "";
            create(stem.isEmpty() ? null : stem, chunks.get(0));
            return 1;
        }
        for (String c : chunks) {
            if (n >= budget) {
                break;
            }
            create(null, c);
            n++;
        }
        return n;
    }

    private int importCsvContent(String text, int budget) {
        if (budget <= 0) {
            return 0;
        }
        String[] lines = text.split("\r?\n");
        if (lines.length == 0) {
            return 0;
        }
        String[] headerCells = splitCsvLine(lines[0]);
        int titleIdx = -1;
        int contentIdx = -1;
        for (int i = 0; i < headerCells.length; i++) {
            String h = headerCells[i].strip().toLowerCase(Locale.ROOT);
            if (h.equals("title") || h.equals("标题")) {
                titleIdx = i;
            }
            if (h.equals("content") || h.equals("text") || h.equals("正文")) {
                contentIdx = i;
            }
        }
        int n = 0;
        if (contentIdx >= 0) {
            for (int r = 1; r < lines.length && n < budget; r++) {
                String line = lines[r].strip();
                if (line.isEmpty()) {
                    continue;
                }
                String[] cells = splitCsvLine(line);
                String content =
                        contentIdx < cells.length ? cells[contentIdx].strip() : "";
                if (content.isEmpty()) {
                    continue;
                }
                String title =
                        titleIdx >= 0 && titleIdx < cells.length
                                ? blankToNull(cells[titleIdx].strip())
                                : null;
                create(title, content);
                n++;
            }
            return n;
        }
        for (int r = 0; r < lines.length && n < budget; r++) {
            String line = lines[r].strip();
            if (line.isEmpty()) {
                continue;
            }
            String[] cells = splitCsvLine(line);
            String content = cells.length > 0 ? cells[0].strip() : "";
            if (content.isEmpty()) {
                continue;
            }
            create(null, content);
            n++;
        }
        return n;
    }

    /** 简单逗号切分（不支持字段内引号逗号）。 */
    private static String[] splitCsvLine(String line) {
        if (line == null) {
            return new String[0];
        }
        String[] parts = line.split(",", -1);
        for (int i = 0; i < parts.length; i++) {
            parts[i] = parts[i].strip();
        }
        return parts;
    }

    private static String basename(String filename) {
        if (filename == null || filename.isBlank()) {
            return "";
        }
        int slash = Math.max(filename.lastIndexOf('/'), filename.lastIndexOf('\\'));
        String base = slash >= 0 ? filename.substring(slash + 1) : filename;
        int dot = base.lastIndexOf('.');
        return dot > 0 ? base.substring(0, dot) : base;
    }

    private static String extension(String filename) {
        if (filename == null) {
            return "";
        }
        int dot = filename.lastIndexOf('.');
        return dot >= 0 ? filename.substring(dot) : "";
    }

    private static String blankToNull(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        return s.strip();
    }

    private static String requirePointId(String pointId) {
        if (pointId == null || pointId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "pointId 无效");
        }
        return pointId.strip();
    }

    private RagDocumentListItemResponse toListItem(KnowledgeDocument d) {
        return RagDocumentListItemResponse.builder()
                .id(d.getPointId())
                .title(d.getTitle())
                .preview(preview(d.getContent()))
                .createdAt(d.getCreatedAt() != null ? ISO_LDT.format(d.getCreatedAt()) : "")
                .build();
    }

    private RagDocumentDetailResponse toDetail(KnowledgeDocument d) {
        return RagDocumentDetailResponse.builder()
                .id(d.getPointId())
                .title(d.getTitle())
                .text(d.getContent())
                .createdAt(d.getCreatedAt() != null ? ISO_LDT.format(d.getCreatedAt()) : "")
                .build();
    }

    private static String preview(String content) {
        if (content == null || content.isEmpty()) {
            return "";
        }
        String oneLine = content.replace('\n', ' ').replace('\r', ' ').strip();
        if (oneLine.length() <= PREVIEW_MAX) {
            return oneLine;
        }
        return oneLine.substring(0, PREVIEW_MAX) + "…";
    }
}
