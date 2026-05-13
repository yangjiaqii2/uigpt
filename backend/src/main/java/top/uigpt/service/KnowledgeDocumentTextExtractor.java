package top.uigpt.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.hwpf.extractor.WordExtractor;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * 将常见办公/数据格式统一抽取为纯文本，供知识库分块与向量化。
 *
 * <p>PDF 使用 PDFBox；Word/Excel 使用 Apache POI；JSON 使用 Jackson 递归收集字符串节点。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KnowledgeDocumentTextExtractor {

    private final ObjectMapper objectMapper;

    public String extractPlainText(MultipartFile file, String extLower) throws Exception {
        String ext = extLower == null ? "" : extLower.toLowerCase(Locale.ROOT);
        return switch (ext) {
            case ".pdf" -> extractPdf(file);
            case ".docx" -> extractDocx(file);
            case ".doc" -> extractDoc(file);
            case ".xlsx", ".xls" -> extractExcel(file);
            case ".json" -> extractJson(file);
            default -> extractUtf8Text(file);
        };
    }

    private static String extractUtf8Text(MultipartFile file) throws Exception {
        byte[] bytes = file.getBytes();
        Charset utf8 = StandardCharsets.UTF_8;
        return utf8.decode(java.nio.ByteBuffer.wrap(bytes)).toString();
    }

    private String extractJson(MultipartFile file) throws Exception {
        JsonNode root = objectMapper.readTree(file.getInputStream());
        StringBuilder sb = new StringBuilder();
        appendJsonText(root, sb);
        return sb.toString();
    }

    private static void appendJsonText(JsonNode n, StringBuilder sb) {
        if (n == null || n.isNull() || n.isMissingNode()) {
            return;
        }
        if (n.isTextual()) {
            String t = n.asText("");
            if (!t.isBlank()) {
                if (sb.length() > 0) {
                    sb.append('\n');
                }
                sb.append(t.strip());
            }
            return;
        }
        if (n.isObject()) {
            n.fields().forEachRemaining(e -> appendJsonText(e.getValue(), sb));
            return;
        }
        if (n.isArray()) {
            for (JsonNode c : n) {
                appendJsonText(c, sb);
            }
        }
    }

    private static String extractPdf(MultipartFile file) throws Exception {
        try (InputStream in = file.getInputStream();
                PDDocument doc = Loader.loadPDF(in.readAllBytes())) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            return stripper.getText(doc).strip();
        }
    }

    private static String extractDocx(MultipartFile file) throws Exception {
        try (InputStream in = file.getInputStream();
                XWPFDocument doc = new XWPFDocument(in)) {
            StringBuilder sb = new StringBuilder();
            for (XWPFParagraph p : doc.getParagraphs()) {
                String t = p.getText();
                if (t != null && !t.isBlank()) {
                    if (sb.length() > 0) {
                        sb.append('\n');
                    }
                    sb.append(t.strip());
                }
            }
            return sb.toString().strip();
        }
    }

    private static String extractDoc(MultipartFile file) throws Exception {
        try (InputStream in = file.getInputStream();
                HWPFDocument doc = new HWPFDocument(in);
                WordExtractor ex = new WordExtractor(doc)) {
            return ex.getText().strip();
        }
    }

    private static String extractExcel(MultipartFile file) throws Exception {
        try (InputStream in = file.getInputStream();
                Workbook wb = WorkbookFactory.create(in)) {
            DataFormatter fmt = new DataFormatter();
            StringBuilder sb = new StringBuilder();
            for (int si = 0; si < wb.getNumberOfSheets(); si++) {
                Sheet sheet = wb.getSheetAt(si);
                if (sheet == null) {
                    continue;
                }
                if (sb.length() > 0) {
                    sb.append("\n\n");
                }
                sb.append("【").append(sheet.getSheetName()).append("】\n");
                for (Row row : sheet) {
                    if (row == null) {
                        continue;
                    }
                    StringBuilder line = new StringBuilder();
                    for (Cell cell : row) {
                        if (cell == null) {
                            continue;
                        }
                        String v = fmt.formatCellValue(cell).strip();
                        if (!v.isEmpty()) {
                            if (line.length() > 0) {
                                line.append('\t');
                            }
                            line.append(v);
                        }
                    }
                    if (!line.isEmpty()) {
                        sb.append(line).append('\n');
                    }
                }
            }
            return sb.toString().strip();
        }
    }
}
