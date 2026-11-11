package com.district.monitor.repository;

import com.district.monitor.entity.WebPushSubscription;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface WebPushSubscriptionRepository extends JpaRepository<WebPushSubscription, Long> {
    List<WebPushSubscription> findByChatId(String chatId);
    Optional<WebPushSubscription> findByChatIdAndEndpoint(String chatId, String endpoint);
    void deleteByChatIdAndEndpoint(String chatId, String endpoint);
}
