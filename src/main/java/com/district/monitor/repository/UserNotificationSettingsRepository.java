package com.district.monitor.repository;

import com.district.monitor.entity.UserNotificationSettings;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface UserNotificationSettingsRepository extends JpaRepository<UserNotificationSettings, Long> {
    Optional<UserNotificationSettings> findByChatId(String chatId);
}
