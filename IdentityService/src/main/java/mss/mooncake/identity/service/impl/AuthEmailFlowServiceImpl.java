package mss.mooncake.identity.service.impl;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
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
import mss.mooncake.identity.service.AuthEmailFlowService;
import mss.mooncake.identity.service.OutboundEmailService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthEmailFlowServiceImpl implements AuthEmailFlowService {
    private static final SecureRandom RANDOM = new SecureRandom();
    private final UserRepository userRepository;
    private final AuthActionTokenRepository tokenRepository;
    private final OutboundEmailService emailService;
    private final PasswordEncoder passwordEncoder;
    private final String frontendUrl;
    private final byte[] otpHashKey;
    private final int verificationTtlMinutes;
    private final int resetTtlMinutes;

    public AuthEmailFlowServiceImpl(UserRepository userRepository, AuthActionTokenRepository tokenRepository,
                                OutboundEmailService emailService, PasswordEncoder passwordEncoder,
                                @Value("${app.frontend-url:http://localhost:3000}") String frontendUrl,
                                @Value("${security.jwt.secret}") String jwtSecret,
                                @Value("${app.auth.email-verification-ttl-minutes:1440}") int verificationTtlMinutes,
                                @Value("${app.auth.password-reset-ttl-minutes:30}") int resetTtlMinutes) {
        this.userRepository = userRepository;
        this.tokenRepository = tokenRepository;
        this.emailService = emailService;
        this.passwordEncoder = passwordEncoder;
        this.frontendUrl = frontendUrl.replaceAll("/+$", "");
        this.otpHashKey = jwtSecret.getBytes(StandardCharsets.UTF_8);
        this.verificationTtlMinutes = verificationTtlMinutes;
        this.resetTtlMinutes = resetTtlMinutes;
    }

    @Override
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

    @Override
    @Transactional
    public void requestVerification(String email) {
        userRepository.findByEmail(normalize(email)).filter(user -> !user.isEmailVerified())
                .ifPresent(this::sendVerification);
    }

    @Override
    @Transactional
    public void verifyEmail(String rawToken) {
        AuthActionTokenEntity token = consume(rawToken, AuthTokenPurpose.EMAIL_VERIFICATION);
        UserEntity user = userRepository.findById(token.getUserId())
                .orElseThrow(() -> new UnauthorizedException("Liên kết xác minh email không hợp lệ hoặc đã hết hạn"));
        user.setEmailVerified(true);
        user.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
        token.setUsedAt(LocalDateTime.now(ZoneOffset.UTC));
    }

    @Override
    @Transactional
    public void requestPasswordReset(String email) {
        userRepository.findByEmail(normalize(email))
                .filter(user -> user.isEmailVerified() && user.getStatus() == UserStatus.ACTIVE)
                .ifPresent(user -> {
                    String code = issue(user, AuthTokenPurpose.PASSWORD_RESET, resetTtlMinutes);
                    if (code == null) return;
                    emailService.send(user.getEmail(), "Mã đặt lại mật khẩu MoonCake Marketplace",
                            "Mã đặt lại mật khẩu của bạn là: " + code
                                    + "\n\nMã có hiệu lực trong " + resetTtlMinutes
                                    + " phút và chỉ sử dụng được một lần. Nếu bạn không yêu cầu, hãy bỏ qua email này.");
                });
    }

    @Override
    @Transactional(noRollbackFor = UnauthorizedException.class)
    public void resetPassword(String email, String code, String newPassword) {
        UserEntity user = userRepository.findByEmail(normalize(email))
                .filter(candidate -> candidate.isEmailVerified() && candidate.getStatus() == UserStatus.ACTIVE)
                .orElseThrow(() -> new UnauthorizedException("Invalid or expired password reset code"));
        AuthActionTokenEntity token = tokenRepository
                .findUsableTokenByUserForUpdate(user.getId(), AuthTokenPurpose.PASSWORD_RESET)
                .filter(candidate -> candidate.getExpiresAt().isAfter(LocalDateTime.now(ZoneOffset.UTC)))
                .orElseThrow(() -> new UnauthorizedException("Invalid or expired password reset code"));
        if (!MessageDigest.isEqual(token.getTokenHash().getBytes(StandardCharsets.UTF_8),
                resetCodeHash(user.getEmail(), code).getBytes(StandardCharsets.UTF_8))) {
            token.recordFailedAttempt();
            if (token.getFailedAttempts() >= 5) token.setUsedAt(LocalDateTime.now(ZoneOffset.UTC));
            throw new UnauthorizedException("Invalid or expired password reset code");
        }
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
        tokenRepository.flush();
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String rawToken = purpose == AuthTokenPurpose.PASSWORD_RESET
                ? String.format(Locale.ROOT, "%06d", RANDOM.nextInt(1_000_000))
                : Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        String tokenHash = purpose == AuthTokenPurpose.PASSWORD_RESET
                ? resetCodeHash(user.getEmail(), rawToken) : hash(rawToken);
        tokenRepository.save(new AuthActionTokenEntity(user.getId(), tokenHash, purpose,
                now.plusMinutes(ttlMinutes), now));
        return rawToken;
    }

    private AuthActionTokenEntity consume(String rawToken, AuthTokenPurpose purpose) {
        AuthActionTokenEntity token = tokenRepository
                .findUsableTokenForUpdate(hash(rawToken), purpose)
                .orElseThrow(() -> new UnauthorizedException("Liên kết không hợp lệ hoặc đã hết hạn"));
        if (!token.getExpiresAt().isAfter(LocalDateTime.now(ZoneOffset.UTC))) {
            throw new UnauthorizedException("Liên kết không hợp lệ hoặc đã hết hạn");
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

    private String resetCodeHash(String email, String code) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(otpHashKey, "HmacSHA256"));
            String scopedCode = AuthTokenPurpose.PASSWORD_RESET + ":" + normalize(email) + ":" + code;
            return java.util.HexFormat.of().formatHex(mac.doFinal(scopedCode.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.GeneralSecurityException ex) {
            throw new IllegalStateException("OTP hashing is unavailable", ex);
        }
    }

    private String normalize(String email) { return email.trim().toLowerCase(Locale.ROOT); }
}
