package com.district.monitor.repository;

import com.district.monitor.entity.WatchRule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface WatchRuleRepository extends JpaRepository<WatchRule, Long> {
    List<WatchRule> findByAcknowledgedFalse();
    List<WatchRule> findByChatIdAndAcknowledgedFalse(String chatId);
    List<WatchRule> findByChatId(String chatId);
    Optional<WatchRule> findByIdAndChatId(Long id, String chatId);
    List<WatchRule> findByChatIdAndMovieNameIgnoreCaseAndAcknowledgedFalse(String chatId, String movieName);
    void deleteByChatId(String chatId);
}
