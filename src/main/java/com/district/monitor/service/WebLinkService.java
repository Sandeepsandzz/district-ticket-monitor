package com.district.monitor.service;

import com.district.monitor.entity.WebSession;
import com.district.monitor.entity.WebUserAccount;
import com.district.monitor.repository.WebSessionRepository;
import com.district.monitor.repository.WebUserAccountRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Telegram linking codes are short-lived; authenticated web sessions are persisted as hashes. */
@Service
public class WebLinkService {
    public record Profile(String chatId, String displayName) {}
    private record Credential(String chatId, String displayName, Instant expiresAt) {}
    private final SecureRandom random = new SecureRandom();
    private final Clock clock;
    private final WebUserAccountRepository accounts;
    private final WebSessionRepository sessionRepository;
    private final int sessionDays;
    private final Map<String, Credential> codes = new ConcurrentHashMap<>();
    private final Map<String, Credential> sessions = new ConcurrentHashMap<>();

    public WebLinkService() { this(Clock.systemUTC(), null, null, 30); }

    @Autowired
    public WebLinkService(WebUserAccountRepository accounts, WebSessionRepository sessionRepository,
                          @Value("${web.session-days:30}") int sessionDays) {
        this(Clock.systemUTC(), accounts, sessionRepository, Math.max(1, sessionDays));
    }

    WebLinkService(Clock clock) { this(clock, null, null, 1); }

    private WebLinkService(Clock clock, WebUserAccountRepository accounts,
                           WebSessionRepository sessionRepository, int sessionDays) {
        this.clock = clock;
        this.accounts = accounts;
        this.sessionRepository = sessionRepository;
        this.sessionDays = sessionDays;
    }

    public synchronized String issueCode(String chatId) {
        return issueCode(chatId, "Movie fan");
    }

    public synchronized String issueCode(String chatId, String displayName) {
        cleanup();
        codes.entrySet().removeIf(entry -> entry.getValue().chatId().equals(chatId));
        String code = randomToken(24);
        String safeName = displayName == null || displayName.isBlank() ? "Movie fan" : displayName.trim();
        codes.put(code, new Credential(chatId, safeName, clock.instant().plus(Duration.ofMinutes(10))));
        return code;
    }

    @Transactional
    public synchronized String exchange(String code) {
        cleanup();
        Credential credential = code == null ? null : codes.remove(code);
        if (credential == null || !credential.expiresAt().isAfter(clock.instant())) return null;
        String token = randomToken(32);
        Instant expires = clock.instant().plus(accounts == null ? Duration.ofHours(24) : Duration.ofDays(sessionDays));
        if (accounts == null || sessionRepository == null) {
            sessions.put(token, new Credential(credential.chatId(), credential.displayName(), expires));
        } else {
            WebUserAccount account = accounts.findByChatId(credential.chatId()).orElseGet(() -> new WebUserAccount(credential.chatId(), credential.displayName()));
            account.setDisplayName(credential.displayName());
            account.setEnabled(true);
            account.touch(clock.instant());
            accounts.save(account);
            sessionRepository.save(new WebSession(credential.chatId(), hash(token), expires));
        }
        return token;
    }

    @Transactional
    public String resolve(String token) {
        Profile profile = resolveProfile(token);
        return profile == null ? null : profile.chatId();
    }

    @Transactional
    public Profile resolveProfile(String token) {
        if (token == null) return null;
        Credential credential = sessions.get(token);
        if (accounts != null && sessionRepository != null) {
            WebSession session = sessionRepository.findByTokenHash(hash(token)).orElse(null);
            if (session == null || !session.getExpiresAt().isAfter(clock.instant())) {
                if (session != null) sessionRepository.delete(session);
                return null;
            }
            WebUserAccount account = accounts.findByChatId(session.getChatId()).orElse(null);
            if (account == null || !account.isEnabled()) return null;
            if (session.getLastSeenAt() == null || session.getLastSeenAt().plusSeconds(300).isBefore(clock.instant())) {
                session.touch(clock.instant()); sessionRepository.save(session);
                account.touch(clock.instant()); accounts.save(account);
            }
            return new Profile(account.getChatId(), account.getDisplayName());
        }
        if (credential == null) return null;
        if (!credential.expiresAt().isAfter(clock.instant())) {
            sessions.remove(token, credential);
            return null;
        }
        return new Profile(credential.chatId(), credential.displayName());
    }

    @Transactional
    public void logout(String token) {
        if (token == null) return;
        sessions.remove(token);
        if (sessionRepository != null) sessionRepository.deleteByTokenHash(hash(token));
    }

    private void cleanup() {
        Instant now = clock.instant();
        codes.entrySet().removeIf(entry -> !entry.getValue().expiresAt().isAfter(now));
        sessions.entrySet().removeIf(entry -> !entry.getValue().expiresAt().isAfter(now));
    }

    private String randomToken(int bytes) {
        byte[] value = new byte[bytes];
        random.nextBytes(value);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    private String hash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(64);
            for (byte item : digest) result.append(String.format("%02x", item));
            return result.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }
}
