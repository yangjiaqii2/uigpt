package top.uigpt.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import top.uigpt.entity.PromptTemplate;

public interface PromptTemplateRepository extends JpaRepository<PromptTemplate, Long> {

    Page<PromptTemplate> findAllByOrderByUpdatedAtDesc(Pageable pageable);
}
