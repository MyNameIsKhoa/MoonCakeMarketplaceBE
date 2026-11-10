package mss.mooncake.identity.service;

public interface GoogleIdentityTokenService {
    GoogleIdentity verify(String idToken);

    record GoogleIdentity(String email, String name) { }
}
