package com.district.monitor.repository;

import com.district.monitor.entity.AlertEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface AlertEventRepository extends JpaRepository<AlertEvent,Long> {
    List<AlertEvent> findTop50ByChatIdOrderByCreatedAtDesc(String chatId);
    List<AlertEvent> findByWatchIdOrderByCreatedAtDesc(Long watchId);
    Optional<AlertEvent> findFirstByWatchIdOrderByCreatedAtDescIdDesc(Long watchId);
}
