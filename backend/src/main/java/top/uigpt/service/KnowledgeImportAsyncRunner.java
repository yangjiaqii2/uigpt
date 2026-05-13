package top.uigpt.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import top.uigpt.config.AsyncConfig;

import java.util.concurrent.CompletableFuture;

/**
 * 知识库文件导入在专用线程池执行；{@link CompletableFuture} 便于任务登记处 {@code whenComplete} 更新状态。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeImportAsyncRunner {

    private final KnowledgeDocumentService knowledgeDocumentService;

    @Async(AsyncConfig.KNOWLEDGE_IMPORT_EXECUTOR)
    public CompletableFuture<Integer> importFilesAsync(MultipartFile[] files) {
        try {
            int n = knowledgeDocumentService.importFiles(files);
            return CompletableFuture.completedFuture(n);
        } catch (RuntimeException e) {
            log.warn("知识库异步导入失败: {}", e.getMessage());
            return CompletableFuture.failedFuture(e);
        }
    }
}
