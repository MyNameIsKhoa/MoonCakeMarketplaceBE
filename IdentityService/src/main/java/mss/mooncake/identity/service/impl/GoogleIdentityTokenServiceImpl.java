package mss.mooncake.identity.service.impl;

import com.google.api.client.googleapis.auth.oauth2.GoogleIdToken;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdTokenVerifier;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.Locale;
import java.util.Arrays;
import mss.mooncake.identity.exception.UnauthorizedException;
import mss.mooncake.identity.service.GoogleIdentityTokenService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class GoogleIdentityTokenServiceImpl implements GoogleIdentityTokenService {
    private final GoogleIdTokenVerifier verifier;

    public GoogleIdentityTokenServiceImpl(@Value("${security.google.client-ids:}") String clientIds) {
        var audiences = Arrays.stream(clientIds.split(","))
                .map(String::trim).filter(value -> !value.isEmpty()).toList();
        this.verifier = new GoogleIdTokenVerifier.Builder(
                new com.google.api.client.http.javanet.NetHttpTransport(),
                new com.google.api.client.json.gson.GsonFactory())
                .setAudience(audiences)
                .build();
    }

    @Override
    public GoogleIdentity verify(String idToken) {
        if (verifier.getAudience().isEmpty()) {
            throw new IllegalStateException("GOOGLE_CLIENT_IDS must be configured before Google login is enabled");
        }
        try {
            GoogleIdToken token = verifier.verify(idToken);
            if (token == null || !Boolean.TRUE.equals(token.getPayload().getEmailVerified())) {
                throw new UnauthorizedException("Thông tin đăng nhập Google không hợp lệ");
            }
            String email = token.getPayload().getEmail();
            if (email == null || email.isBlank()) {
                throw new UnauthorizedException("Tài khoản Google chưa có email đã xác minh");
            }
            String name = (String) token.getPayload().get("name");
            return new GoogleIdentity(email.trim().toLowerCase(Locale.ROOT), name);
        } catch (IOException | GeneralSecurityException ex) {
            throw new UnauthorizedException("Không thể xác minh đăng nhập Google, vui lòng thử lại");
        }
    }
}
