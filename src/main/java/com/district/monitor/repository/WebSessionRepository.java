package com.district.monitor.repository;

import com.district.monitor.entity.WebSession;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface WebSessionRepository extends JpaRepository<WebSession, Long> {
    Optional<WebSession> findByTokenHash(String tokenHash);
    void deleteByTokenHash(String tokenHash);
}
