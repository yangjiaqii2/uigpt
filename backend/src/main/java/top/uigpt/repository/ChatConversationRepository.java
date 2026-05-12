package top.uigpt.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import top.uigpt.entity.ChatConversation;

import java.util.List;
import java.util.Optional;

public interface ChatConversationRepository extends JpaRepository<ChatConversation, Long> {

    List<ChatConversation> findByUserIdOrderByUpdatedAtDesc(Long userId);

    @Query(
            "SELECT c FROM ChatConversation c WHERE c.userId = :userId ORDER BY "
                    + "CASE WHEN c.pinnedAt IS NULL THEN 1 ELSE 0 END, c.pinnedAt DESC, c.updatedAt DESC")
    List<ChatConversation> findByUserIdOrderPinnedFirst(@Param("userId") Long userId);

    Optional<ChatConversation> findByIdAndUserId(Long id, Long userId);

    Optional<ChatConversation> findByUserIdAndStudioChannel(Long userId, String studioChannel);

    long countByUserId(Long userId);
}
