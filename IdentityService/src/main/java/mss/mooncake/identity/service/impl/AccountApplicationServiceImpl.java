package mss.mooncake.identity.service.impl;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import jakarta.annotation.PostConstruct;
import mss.mooncake.identity.dto.request.LoginRequest;
import mss.mooncake.identity.dto.request.RegisterRequest;
import mss.mooncake.identity.dto.request.UpdateProfileRequest;
import mss.mooncake.identity.dto.response.AuthResponse;
import mss.mooncake.identity.dto.response.UserProfileResponse;
import mss.mooncake.identity.dto.response.UserSummaryResponse;
import mss.mooncake.identity.entity.CustomerProfileEntity;
import mss.mooncake.identity.entity.UserEntity;
import mss.mooncake.identity.enums.UserStatus;
import mss.mooncake.identity.exception.DuplicateResourceException;
import mss.mooncake.identity.exception.ForbiddenException;
import mss.mooncake.identity.exception.ResourceNotFoundException;
import mss.mooncake.identity.exception.UnauthorizedException;
import mss.mooncake.identity.repository.CustomerProfileRepository;
import mss.mooncake.identity.repository.UserRepository;
import mss.mooncake.identity.security.JwtTokenProvider;
import mss.mooncake.identity.service.AccountApplicationService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AccountApplicationServiceImpl implements AccountApplicationService {

    private final UserRepository userRepository;
    private final CustomerProfileRepository customerProfileRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;
    @Value("${security.admin.user-ids:}")
    private String configuredAdminUserIds;
    private Set<Long> adminUserIds = Set.of();

    public AccountApplicationServiceImpl(UserRepository userRepository,
                                          CustomerProfileRepository customerProfileRepository,
                                          PasswordEncoder passwordEncoder,
                                          JwtTokenProvider jwtTokenProvider) {
        this.userRepository = userRepository;
        this.customerProfileRepository = customerProfileRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtTokenProvider = jwtTokenProvider;
    }

    @PostConstruct
    void loadAdminUserIds() {
        if (configuredAdminUserIds == null || configuredAdminUserIds.isBlank()) {
            adminUserIds = Set.of();
            return;
        }
        adminUserIds = Arrays.stream(configuredAdminUserIds.split(","))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .map(Long::valueOf)
                .collect(Collectors.toUnmodifiableSet());
        for (Long adminUserId : adminUserIds) {
            if (adminUserId <= 0 || !userRepository.existsById(adminUserId)) {
                throw new IllegalStateException("ADMIN_USER_IDS must reference existing, operator-provisioned users: " + adminUserId);
            }
        }
    }

    @Override
    @Transactional
    public UserSummaryResponse register(RegisterRequest request) {
        String normalizedEmail = request.email().trim().toLowerCase();
        if (userRepository.existsByEmail(normalizedEmail)) {
            throw new DuplicateResourceException("Email is already registered: " + normalizedEmail);
        }

        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        String encodedPassword = passwordEncoder.encode(request.password());

        UserEntity user = new UserEntity(
                normalizedEmail,
                encodedPassword,
                UserStatus.ACTIVE,
                now,
                now
        );
        UserEntity savedUser = userRepository.save(user);

        CustomerProfileEntity profile = new CustomerProfileEntity(
                savedUser.getId(),
                request.fullName().trim(),
                request.phone() != null ? request.phone().trim() : null,
                now,
                now
        );
        CustomerProfileEntity savedProfile = customerProfileRepository.save(profile);

        return new UserSummaryResponse(
                savedUser.getId(),
                savedProfile.getId(),
                savedProfile.getFullName(),
                savedUser.getEmail(),
                savedProfile.getPhone()
        );
    }

    @Override
    @Transactional(readOnly = true)
    public AuthResponse login(LoginRequest request) {
        String normalizedEmail = request.email().trim().toLowerCase();
        UserEntity user = userRepository.findByEmail(normalizedEmail)
                .orElseThrow(() -> new UnauthorizedException("Invalid email or password"));

        if (user.getPasswordHash() == null || !passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw new UnauthorizedException("Invalid email or password");
        }

        if (user.getStatus() != UserStatus.ACTIVE) {
            throw new ForbiddenException("Account is not active. Current status: " + user.getStatus());
        }

        CustomerProfileEntity profile = customerProfileRepository.findByUserId(user.getId())
                .orElse(null);

        Long customerId = profile != null ? profile.getId() : null;
        String fullName = profile != null ? profile.getFullName() : null;
        String phone = profile != null ? profile.getPhone() : null;

        List<String> roles = adminUserIds.contains(user.getId())
                ? List.of("ROLE_CUSTOMER", "ROLE_ADMIN")
                : List.of("ROLE_CUSTOMER");
        String token = jwtTokenProvider.generateToken(user.getId(), customerId, user.getEmail(), roles);

        UserSummaryResponse userSummary = new UserSummaryResponse(
                user.getId(),
                customerId,
                fullName,
                user.getEmail(),
                phone
        );

        return new AuthResponse(
                token,
                "Bearer",
                jwtTokenProvider.getExpirationSeconds(),
                userSummary
        );
    }

    @Override
    @Transactional(readOnly = true)
    public UserProfileResponse getProfile(Long userId) {
        UserEntity user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found with id: " + userId));

        CustomerProfileEntity profile = customerProfileRepository.findByUserId(userId)
                .orElseThrow(() -> new ResourceNotFoundException("Customer profile not found for user id: " + userId));

        return new UserProfileResponse(
                user.getId(),
                profile.getId(),
                user.getEmail(),
                profile.getFullName(),
                profile.getPhone(),
                user.getStatus().name(),
                profile.getCreatedAt(),
                profile.getUpdatedAt()
        );
    }

    @Override
    @Transactional
    public UserProfileResponse updateProfile(Long userId, UpdateProfileRequest request) {
        UserEntity user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found with id: " + userId));

        CustomerProfileEntity profile = customerProfileRepository.findByUserId(userId)
                .orElseThrow(() -> new ResourceNotFoundException("Customer profile not found for user id: " + userId));

        boolean updated = false;
        if (request.fullName() != null && !request.fullName().isBlank()) {
            profile.setFullName(request.fullName().trim());
            updated = true;
        }

        if (request.phone() != null) {
            profile.setPhone(request.phone().trim());
            updated = true;
        }

        if (updated) {
            LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
            profile.setUpdatedAt(now);
            user.setUpdatedAt(now);
            customerProfileRepository.save(profile);
            userRepository.save(user);
        }

        return new UserProfileResponse(
                user.getId(),
                profile.getId(),
                user.getEmail(),
                profile.getFullName(),
                profile.getPhone(),
                user.getStatus().name(),
                profile.getCreatedAt(),
                profile.getUpdatedAt()
        );
    }

    @Override
    @Transactional(readOnly = true)
    public UserSummaryResponse getUserSummary(Long userId) {
        UserEntity user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found with id: " + userId));

        CustomerProfileEntity profile = customerProfileRepository.findByUserId(userId)
                .orElse(null);

        return new UserSummaryResponse(
                user.getId(),
                profile != null ? profile.getId() : null,
                profile != null ? profile.getFullName() : null,
                user.getEmail(),
                profile != null ? profile.getPhone() : null
        );
    }
}
