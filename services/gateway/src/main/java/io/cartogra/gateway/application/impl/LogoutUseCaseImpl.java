package io.cartogra.gateway.application.impl;

import io.cartogra.gateway.application.LogoutUseCase;
import io.cartogra.gateway.infrastructure.jdbc.RefreshTokenRepository;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

@Service
public class LogoutUseCaseImpl implements LogoutUseCase {

    private final RefreshTokenRepository refreshTokenRepository;

    public LogoutUseCaseImpl(RefreshTokenRepository refreshTokenRepository) {
        this.refreshTokenRepository = refreshTokenRepository;
    }

    @Override
    public void execute(String rawRefreshToken) {
        String hash = sha256(rawRefreshToken);
        refreshTokenRepository.findActiveByHash(hash)
            .ifPresent(token -> refreshTokenRepository.revoke(token.id()));
    }

    private static String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
