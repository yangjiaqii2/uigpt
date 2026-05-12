package top.uigpt.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import top.uigpt.entity.SiteMailMessage;

import java.util.List;
import java.util.Optional;

public interface SiteMailMessageRepository extends JpaRepository<SiteMailMessage, Long> {

    List<SiteMailMessage> findByThreadIdOrderByCreatedAtAsc(long threadId);

    Optional<SiteMailMessage> findFirstByThreadIdOrderByCreatedAtDesc(long threadId);

    @Query(
            "SELECT COUNT(m) FROM SiteMailMessage m WHERE m.threadId = :tid AND m.senderUserId <> :uid AND m.readByUser = false")
    long countUnreadForUser(@Param("tid") long threadId, @Param("uid") long userId);

    @Query(
            "SELECT COUNT(m) FROM SiteMailMessage m WHERE m.threadId = :tid AND m.senderUserId = :uid AND m.readByAdmin = false")
    long countUnreadForAdmin(@Param("tid") long threadId, @Param("uid") long threadOwnerUserId);

    @Modifying
    @Query(
            "UPDATE SiteMailMessage m SET m.readByUser = true WHERE m.threadId = :tid AND m.senderUserId <> :uid AND m.readByUser = false")
    int markReadByUser(@Param("tid") long threadId, @Param("uid") long threadOwnerUserId);

    @Modifying
    @Query(
            "UPDATE SiteMailMessage m SET m.readByAdmin = true WHERE m.threadId = :tid AND m.senderUserId = :uid AND m.readByAdmin = false")
    int markReadByAdmin(@Param("tid") long threadId, @Param("uid") long threadOwnerUserId);

    @Query(
            "SELECT COUNT(m) FROM SiteMailMessage m, SiteMailThread t WHERE m.threadId = t.id AND m.senderUserId = t.userId AND m.readByAdmin = false")
    long countAllUnreadUserMessagesForAdmin();
}
