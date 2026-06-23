package com.naqqa.auth.service.security;

import com.naqqa.auth.repository.RefreshTokenRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;

@Service
@RequiredArgsConstructor
@Slf4j
public class TokenCleanupService {

    private final RefreshTokenRepository refreshTokenRepository;

    @Scheduled(cron = "0 0 * * * *")
    public void cleanupExpiredTokens() {
        log.info("Cleaning up expired refresh tokens.");
        refreshTokenRepository.deleteAll(refreshTokenRepository.findByExpiryDateBefore(Instant.now()));
    }
}