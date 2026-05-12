package top.uigpt.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import top.uigpt.entity.SiteMailThread;

import java.util.Optional;

public interface SiteMailThreadRepository extends JpaRepository<SiteMailThread, Long> {

    Optional<SiteMailThread> findByUserId(long userId);

    Page<SiteMailThread> findAllByOrderByUpdatedAtDesc(Pageable pageable);
}
