package com.district.monitor.repository;

import com.district.monitor.entity.WebUserAccount;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface WebUserAccountRepository extends JpaRepository<WebUserAccount, Long> {
    Optional<WebUserAccount> findByChatId(String chatId);
}
