package com.district.monitor.service;

import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Short-lived credentials are deliberately kept in memory, never in the database or logs. */
@Service
public class WebLinkService {
    private record Credential(String chatId, Instant expiresAt) {}
    private final SecureRandom random = new SecureRandom();
    private final Clock clock;
    private final Map<String, Credential> codes = new ConcurrentHashMap<>();
    private final Map<String, Credential> sessions = new ConcurrentHashMap<>();

    public WebLinkService() { this(Clock.systemUTC()); }
    WebLinkService(Clock clock) { this.clock = clock; }

    public synchronized String issueCode(String chatId) {
        cleanup();
        codes.entrySet().removeIf(entry -> entry.getValue().chatId().equals(chatId));
        String code = randomToken(24);
        codes.put(code, new Credential(chatId, clock.instant().plus(Duration.ofMinutes(10))));
        return code;
    }

    public synchronized String exchange(String code) {
        cleanup();
        Credential credential = code == null ? null : codes.remove(code);
        if (credential == null || !credential.expiresAt().isAfter(clock.instant())) return null;
        String token = randomToken(32);
        sessions.put(token, new Credential(credential.chatId(), clock.instant().plus(Duration.ofHours(24))));
        return token;
    }

    public String resolve(String token) {
        if (token == null) return null;
        Credential credential = sessions.get(token);
        if (credential == null) return null;
        if (!credential.expiresAt().isAfter(clock.instant())) {
            sessions.remove(token, credential);
            return null;
        }
        return credential.chatId();
    }

    public void logout(String token) { if (token != null) sessions.remove(token); }

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
}
