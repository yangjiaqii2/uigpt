package top.uigpt.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import top.uigpt.entity.KnowledgeDocument;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface KnowledgeDocumentRepository extends JpaRepository<KnowledgeDocument, Long> {

    Optional<KnowledgeDocument> findByPointId(String pointId);

    List<KnowledgeDocument> findAllByPointIdIn(Collection<String> pointIds);

    void deleteByPointId(String pointId);

    boolean existsByPointId(String pointId);

    Page<KnowledgeDocument> findAllByOrderByCreatedAtDesc(Pageable pageable);
}
