package mss.mooncake.identity.service;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Locale;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
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
    private final byte[] otpHashKey;
    private final int verificationTtlMinutes;
    private final int resetTtlMinutes;

    public AuthEmailFlowService(UserRepository userRepository, AuthActionTokenRepository tokenRepository,
                                OutboundEmailService emailService, PasswordEncoder passwordEncoder,
<<<<<<< Updated upstream
                                @Value("${app.frontend-url:http://localhost:5173}") String frontendUrl,
                                @Value("${app.auth.email-verification-ttl-minutes:1440}") int verificationTtlMinutes,
                                @Value("${app.auth.password-reset-ttl-minutes:30}") int resetTtlMinutes) {
=======
                                @Value("${security.jwt.secret}") String jwtSecret,
                                @Value("${app.auth.email-verification-ttl-minutes:10}") int verificationTtlMinutes,
                                @Value("${app.auth.password-reset-ttl-minutes:10}") int resetTtlMinutes) {
>>>>>>> Stashed changes
        this.userRepository = userRepository;
        this.tokenRepository = tokenRepository;
        this.emailService = emailService;
        this.passwordEncoder = passwordEncoder;
        this.otpHashKey = jwtSecret.getBytes(StandardCharsets.UTF_8);
        this.verificationTtlMinutes = verificationTtlMinutes;
        this.resetTtlMinutes = resetTtlMinutes;
    }

    @Transactional
    public void sendVerification(UserEntity user) {
        if (user.isEmailVerified()) return;
        String code = String.format(Locale.ROOT, "%06d", RANDOM.nextInt(1_000_000));
        if (!issue(user, AuthTokenPurpose.EMAIL_VERIFICATION, verificationTtlMinutes, code)) return;
        emailService.send(user.getEmail(), "Xác minh email MoonCake Marketplace",
                "Chào bạn,\n\nMã xác minh email MoonCake Marketplace của bạn là: " + code
                        + "\n\nMã có hiệu lực trong " + verificationTtlMinutes
                        + " phút và chỉ sử dụng được một lần. Nếu bạn không tạo tài khoản, hãy bỏ qua email này.");
    }

    @Transactional
    public void requestVerification(String email) {
        userRepository.findByEmail(normalize(email)).filter(user -> !user.isEmailVerified())
                .ifPresent(this::sendVerification);
    }

    @Transactional
    public void verifyEmail(String rawToken) {
        if (rawToken == null || !rawToken.matches("\\d{6}")) {
            throw new UnauthorizedException("Invalid or expired verification code");
        }
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
                    String code = String.format(Locale.ROOT, "%06d", RANDOM.nextInt(1_000_000));
                    if (!issue(user, AuthTokenPurpose.PASSWORD_RESET, resetTtlMinutes, code)) return;
                    emailService.send(user.getEmail(), "Mã đặt lại mật khẩu MoonCake Marketplace",
                            "Mã đặt lại mật khẩu của bạn là: " + code
                                    + "\n\nMã có hiệu lực trong " + resetTtlMinutes
                                    + " phút và chỉ sử dụng được một lần. Nếu bạn không yêu cầu, hãy bỏ qua email này.");
                });
    }

    @Transactional
    public void resetPassword(String rawToken, String newPassword) {
        if (rawToken == null || !rawToken.matches("\\d{6}")) {
            throw new UnauthorizedException("Invalid or expired password reset code");
        }
        AuthActionTokenEntity token = consume(rawToken, AuthTokenPurpose.PASSWORD_RESET);
        UserEntity user = userRepository.findById(token.getUserId())
                .orElseThrow(() -> new UnauthorizedException("Invalid or expired password reset token"));
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        user.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
        token.setUsedAt(LocalDateTime.now(ZoneOffset.UTC));
    }

    private boolean issue(UserEntity user, AuthTokenPurpose purpose, int ttlMinutes, String code) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        boolean recentlyIssued = tokenRepository.findFirstByUserIdAndPurposeOrderByCreatedAtDesc(user.getId(), purpose)
                .stream().findFirst()
                .map(token -> token.getCreatedAt().isAfter(now.minusMinutes(1)))
                .orElse(false);
        if (recentlyIssued) return false;
        saveToken(user, purpose, ttlMinutes, now, code);
        return true;
    }

    private void saveToken(UserEntity user, AuthTokenPurpose purpose, int ttlMinutes,
                           LocalDateTime now, String rawToken) {
        tokenRepository.deleteByUserIdAndPurpose(user.getId(), purpose);
        tokenRepository.save(new AuthActionTokenEntity(user.getId(), hash(rawToken), purpose,
                now.plusMinutes(ttlMinutes), now));
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
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(otpHashKey, "HmacSHA256"));
            return java.util.HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.GeneralSecurityException ex) {
            throw new IllegalStateException("OTP hashing is unavailable", ex);
        }
    }

    private String normalize(String email) { return email.trim().toLowerCase(Locale.ROOT); }
}
