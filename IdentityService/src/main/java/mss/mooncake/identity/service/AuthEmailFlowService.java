package mss.mooncake.identity.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Locale;
import mss.mooncake.identity.entity.AuthActionTokenEntity;
import mss.mooncake.identity.entity.UserEntity;
import mss.mooncake.identity.enums.AuthTokenPurpose;
import mss.mooncake.identity.enums.UserStatus;
import mss.mooncake.identity.exception.UnauthorizedException;
import mss.mooncake.identity.repository.AuthActionTokenRepository;
import mss.mooncake.identity.repository.UserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthEmailFlowService {
    private static final SecureRandom RANDOM = new SecureRandom();
    private final UserRepository userRepository;
    private final AuthActionTokenRepository tokenRepository;
    private final OutboundEmailService emailService;
    private final PasswordEncoder passwordEncoder;
    private final String frontendUrl;
    private final int verificationTtlMinutes;
    private final int resetTtlMinutes;

    public AuthEmailFlowService(UserRepository userRepository, AuthActionTokenRepository tokenRepository,
                                OutboundEmailService emailService, PasswordEncoder passwordEncoder,
                                @Value("${app.frontend-url:http://localhost:5173}") String frontendUrl,
                                @Value("${app.auth.email-verification-ttl-minutes:1440}") int verificationTtlMinutes,
                                @Value("${app.auth.password-reset-ttl-minutes:30}") int resetTtlMinutes) {
        this.userRepository = userRepository;
        this.tokenRepository = tokenRepository;
        this.emailService = emailService;
        this.passwordEncoder = passwordEncoder;
        this.frontendUrl = frontendUrl.replaceAll("/+$", "");
        this.verificationTtlMinutes = verificationTtlMinutes;
        this.resetTtlMinutes = resetTtlMinutes;
    }

    @Transactional
    public void sendVerification(UserEntity user) {
        if (user.isEmailVerified()) return;
        String rawToken = issue(user, AuthTokenPurpose.EMAIL_VERIFICATION, verificationTtlMinutes);
        if (rawToken == null) return;
        emailService.send(user.getEmail(), "Xác minh email MoonCake Marketplace",
                "Chào bạn,\n\nVui lòng xác minh email bằng liên kết sau:\n" + frontendUrl
                        + "/verify-email?token=" + rawToken + "\n\nLiên kết hết hạn sau "
                        + verificationTtlMinutes + " phút. Nếu bạn không tạo tài khoản, hãy bỏ qua email này.");
    }

    @Transactional
    public void requestVerification(String email) {
        userRepository.findByEmail(normalize(email)).filter(user -> !user.isEmailVerified())
                .ifPresent(this::sendVerification);
    }

    @Transactional
    public void verifyEmail(String rawToken) {
        AuthActionTokenEntity token = consume(rawToken, AuthTokenPurpose.EMAIL_VERIFICATION);
        UserEntity user = userRepository.findById(token.getUserId())
                .orElseThrow(() -> new UnauthorizedException("Invalid or expired verification token"));
        user.setEmailVerified(true);
        user.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
        token.setUsedAt(LocalDateTime.now(ZoneOffset.UTC));
    }

    @Transactional
    public void requestPasswordReset(String email) {
        userRepository.findByEmail(normalize(email))
                .filter(user -> user.isEmailVerified() && user.getStatus() == UserStatus.ACTIVE)
                .ifPresent(user -> {
                    String rawToken = issue(user, AuthTokenPurpose.PASSWORD_RESET, resetTtlMinutes);
                    if (rawToken == null) return;
                    emailService.send(user.getEmail(), "Đặt lại mật khẩu MoonCake Marketplace",
                            "Chúng tôi nhận được yêu cầu đặt lại mật khẩu. Mở liên kết sau để tạo mật khẩu mới:\n"
                                    + frontendUrl + "/reset-password?token=" + rawToken
                                    + "\n\nLiên kết chỉ dùng một lần và hết hạn sau " + resetTtlMinutes
                                    + " phút. Nếu bạn không yêu cầu, hãy bỏ qua email này.");
                });
    }

    @Transactional
    public void resetPassword(String rawToken, String newPassword) {
        AuthActionTokenEntity token = consume(rawToken, AuthTokenPurpose.PASSWORD_RESET);
        UserEntity user = userRepository.findById(token.getUserId())
                .orElseThrow(() -> new UnauthorizedException("Invalid or expired password reset token"));
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        user.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
        token.setUsedAt(LocalDateTime.now(ZoneOffset.UTC));
    }

    private String issue(UserEntity user, AuthTokenPurpose purpose, int ttlMinutes) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        boolean recentlyIssued = tokenRepository.findFirstByUserIdAndPurposeOrderByCreatedAtDesc(user.getId(), purpose)
                .stream().findFirst()
                .map(token -> token.getCreatedAt().isAfter(now.minusMinutes(1)))
                .orElse(false);
        if (recentlyIssued) return null;
        tokenRepository.deleteByUserIdAndPurpose(user.getId(), purpose);
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String rawToken = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        tokenRepository.save(new AuthActionTokenEntity(user.getId(), hash(rawToken), purpose,
                now.plusMinutes(ttlMinutes), now));
        return rawToken;
    }

    private AuthActionTokenEntity consume(String rawToken, AuthTokenPurpose purpose) {
        AuthActionTokenEntity token = tokenRepository
                .findUsableTokenForUpdate(hash(rawToken), purpose)
                .orElseThrow(() -> new UnauthorizedException("Invalid or expired token"));
        if (!token.getExpiresAt().isAfter(LocalDateTime.now(ZoneOffset.UTC))) {
            throw new UnauthorizedException("Invalid or expired token");
        }
        return token;
    }

    private String hash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }

    private String normalize(String email) { return email.trim().toLowerCase(Locale.ROOT); }
}
