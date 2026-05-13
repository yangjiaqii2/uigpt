package top.uigpt.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import top.uigpt.dto.RagImportTaskAcceptedResponse;
import top.uigpt.dto.RagImportTaskStatusResponse;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 知识库导入异步任务登记：Spring {@link org.springframework.scheduling.annotation.Async} +
 * {@link CompletableFuture}，无 MQ。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeImportJobService {

    private final KnowledgeImportAsyncRunner asyncRunner;

    private final ConcurrentHashMap<String, TaskSnapshot> tasks = new ConcurrentHashMap<>();

    /**
     * 提交已物化（字节已在请求线程读完）的 multipart 副本；立即返回 taskId。
     */
    public RagImportTaskAcceptedResponse submit(MultipartFile[] materialized) {
        String taskId = UUID.randomUUID().toString();
        TaskSnapshot snap = new TaskSnapshot(taskId, Instant.now());
        snap.status = "RUNNING";
        tasks.put(taskId, snap);
        CompletableFuture<Integer> fut = asyncRunner.importFilesAsync(materialized);
        fut.whenComplete((n, ex) -> finalizeSnapshot(taskId, n, ex));
        return new RagImportTaskAcceptedResponse(taskId, "RUNNING");
    }

    public RagImportTaskStatusResponse getStatus(String taskId) {
        if (taskId == null || taskId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "taskId 无效");
        }
        TaskSnapshot s = tasks.get(taskId.strip());
        if (s == null) {
            throw new ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND, "任务不存在或已过期");
        }
        return RagImportTaskStatusResponse.builder()
                .taskId(s.taskId)
                .status(s.status)
                .imported("SUCCEEDED".equals(s.status) ? s.imported : null)
                .error("FAILED".equals(s.status) ? s.error : null)
                .submittedAt(s.submittedAt.toString())
                .finishedAt(s.finishedAt != null ? s.finishedAt.toString() : null)
                .build();
    }

    private void finalizeSnapshot(String taskId, Integer n, Throwable ex) {
        TaskSnapshot s = tasks.get(taskId);
        if (s == null) {
            return;
        }
        s.finishedAt = Instant.now();
        if (ex != null) {
            Throwable c = unwrap(ex);
            s.status = "FAILED";
            s.error = describeFailure(c);
            log.warn("知识库导入任务 {} 失败: {}", taskId, s.error);
        } else {
            s.status = "SUCCEEDED";
            s.imported = n != null ? n : 0;
            log.info("知识库导入任务 {} 完成，新增 {} 条", taskId, s.imported);
        }
    }

    private static Throwable unwrap(Throwable ex) {
        Throwable t = ex;
        while (t instanceof java.util.concurrent.CompletionException && t.getCause() != null) {
            t = t.getCause();
        }
        return t;
    }

    private static String describeFailure(Throwable c) {
        if (c instanceof ResponseStatusException rse) {
            if (rse.getReason() != null && !rse.getReason().isBlank()) {
                return rse.getReason();
            }
            return rse.getStatusCode() + ": " + (rse.getMessage() != null ? rse.getMessage() : "错误");
        }
        return c.getMessage() != null ? c.getMessage() : c.getClass().getSimpleName();
    }

    private static final class TaskSnapshot {
        final String taskId;
        final Instant submittedAt;
        volatile String status;
        volatile int imported;
        volatile String error;
        volatile Instant finishedAt;

        TaskSnapshot(String taskId, Instant submittedAt) {
            this.taskId = taskId;
            this.submittedAt = submittedAt;
        }
    }
}
