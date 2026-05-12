package top.uigpt.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import top.uigpt.entity.ChatConversationImage;

import java.util.List;

public interface ChatConversationImageRepository extends JpaRepository<ChatConversationImage, Long> {

    List<ChatConversationImage> findByConversationIdOrderByCreatedAtDesc(Long conversationId);

    long countByConversationId(Long conversationId);

    @Query("SELECT COUNT(i) FROM ChatConversationImage i WHERE i.userId = :userId")
    long countImagesForUser(@Param("userId") Long userId);

    @Query(
            "SELECT COUNT(i) FROM ChatConversationImage i WHERE i.userId = :userId AND i.favorite = true")
    long countFavoriteImagesForUser(@Param("userId") Long userId);

    @Query(
            "SELECT i FROM ChatConversationImage i WHERE i.userId = :userId ORDER BY i.createdAt DESC")
    Page<ChatConversationImage> findImagesForUserPage(@Param("userId") Long userId, Pageable pageable);

    Page<ChatConversationImage> findByUserIdAndSkillIdOrderByCreatedAtDesc(
            Long userId, String skillId, Pageable pageable);
}
