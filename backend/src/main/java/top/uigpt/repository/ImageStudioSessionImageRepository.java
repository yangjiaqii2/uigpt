package top.uigpt.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import top.uigpt.entity.ImageStudioSessionImage;

import java.util.List;
import java.util.Optional;

public interface ImageStudioSessionImageRepository extends JpaRepository<ImageStudioSessionImage, Long> {

    List<ImageStudioSessionImage> findBySessionIdOrderBySortOrderAsc(long sessionId);

    Optional<ImageStudioSessionImage> findTopBySessionIdOrderBySortOrderDesc(long sessionId);

    Optional<ImageStudioSessionImage> findByObjectKey(String objectKey);

    Optional<ImageStudioSessionImage> findByIdAndSessionId(long id, long sessionId);

    int countBySessionId(long sessionId);

    void deleteBySessionId(long sessionId);
}
