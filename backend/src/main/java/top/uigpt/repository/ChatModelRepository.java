package top.uigpt.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import top.uigpt.entity.ChatModel;

import java.util.List;

public interface ChatModelRepository extends JpaRepository<ChatModel, Long> {

    List<ChatModel> findByEnabledTrueOrderBySortOrderAscIdAsc();
}
