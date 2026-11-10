package mss.mooncake.identity.service;

import mss.mooncake.identity.entity.UserEntity;

public interface AuthEmailFlowService {
    void sendVerification(UserEntity user);
    void requestVerification(String email);
    void verifyEmail(String rawToken);
    void requestPasswordReset(String email);
    void resetPassword(String rawToken, String newPassword);
}
