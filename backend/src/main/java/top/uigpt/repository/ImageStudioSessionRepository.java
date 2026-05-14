package top.uigpt.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import top.uigpt.entity.ImageStudioSession;

import java.util.Optional;

public interface ImageStudioSessionRepository extends JpaRepository<ImageStudioSession, Long> {

    Page<ImageStudioSession> findByUserIdOrderByUpdatedAtDesc(long userId, Pageable pageable);

    Optional<ImageStudioSession> findByIdAndUserId(long id, long userId);
}
