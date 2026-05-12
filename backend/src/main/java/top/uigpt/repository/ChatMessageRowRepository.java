package top.uigpt.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import top.uigpt.entity.ChatMessageRow;

import java.util.List;

public interface ChatMessageRowRepository extends JpaRepository<ChatMessageRow, Long> {

    void deleteByConversationId(Long conversationId);

    List<ChatMessageRow> findByConversationIdOrderBySortOrderAsc(Long conversationId);

    @Query(
            "SELECT m.conversationId, COUNT(m) FROM ChatMessageRow m WHERE m.conversationId IN :ids GROUP BY"
                    + " m.conversationId")
    List<Object[]> countGroupedByConversationId(@Param("ids") List<Long> ids);

    @Query(
            "SELECT COALESCE(MAX(m.sortOrder), -1) FROM ChatMessageRow m WHERE m.conversationId = :cid")
    int maxSortOrderByConversationId(@Param("cid") Long conversationId);
}
