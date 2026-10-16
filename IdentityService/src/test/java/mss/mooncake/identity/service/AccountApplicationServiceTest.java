package mss.mooncake.identity.service;

import java.time.LocalDateTime;
import java.util.Optional;
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
import mss.mooncake.identity.service.impl.AccountApplicationServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AccountApplicationServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private CustomerProfileRepository customerProfileRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private JwtTokenProvider jwtTokenProvider;

    private AccountApplicationServiceImpl accountService;

    @BeforeEach
    void setUp() {
        accountService = new AccountApplicationServiceImpl(
                userRepository,
                customerProfileRepository,
                passwordEncoder,
                jwtTokenProvider
        );
    }

    @Test
    void configuredAdminIdMustExistBeforeServiceStarts() {
        ReflectionTestUtils.setField(accountService, "configuredAdminUserIds", "1");
        when(userRepository.existsById(1L)).thenReturn(false);

        assertThrows(IllegalStateException.class, () -> ReflectionTestUtils.invokeMethod(accountService, "loadAdminUserIds"));
    }

    @Test
    void existingConfiguredAdminReceivesAdminRole() {
        ReflectionTestUtils.setField(accountService, "configuredAdminUserIds", "1");
        when(userRepository.existsById(1L)).thenReturn(true);
        ReflectionTestUtils.invokeMethod(accountService, "loadAdminUserIds");

        UserEntity user = new UserEntity("admin@example.com", "hash", UserStatus.ACTIVE, LocalDateTime.now(), LocalDateTime.now());
        ReflectionTestUtils.setField(user, "id", 1L);
        when(userRepository.findByEmail("admin@example.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("password123", "hash")).thenReturn(true);
        when(customerProfileRepository.findByUserId(1L)).thenReturn(Optional.empty());

        accountService.login(new LoginRequest("admin@example.com", "password123"));

        verify(jwtTokenProvider).generateToken(eq(1L), isNull(), eq("admin@example.com"),
                argThat(roles -> roles.contains("ROLE_ADMIN")));
    }

    @Test
    void register_Success() {
        RegisterRequest request = new RegisterRequest("Nguyen Van A", "test@example.com", "password123", "0901234567");

        when(userRepository.existsByEmail("test@example.com")).thenReturn(false);
        when(passwordEncoder.encode("password123")).thenReturn("hashedPassword");

        when(userRepository.save(any(UserEntity.class))).thenAnswer(invocation -> {
            UserEntity u = invocation.getArgument(0);
            ReflectionTestUtils.setField(u, "id", 1L);
            return u;
        });

        when(customerProfileRepository.save(any(CustomerProfileEntity.class))).thenAnswer(invocation -> {
            CustomerProfileEntity cp = invocation.getArgument(0);
            ReflectionTestUtils.setField(cp, "id", 10L);
            return cp;
        });

        UserSummaryResponse response = accountService.register(request);

        assertNotNull(response);
        assertEquals(1L, response.userId());
        assertEquals(10L, response.customerId());
        assertEquals("Nguyen Van A", response.fullName());
        assertEquals("test@example.com", response.email());
        assertEquals("0901234567", response.phone());

        verify(userRepository).save(any(UserEntity.class));
        verify(customerProfileRepository).save(any(CustomerProfileEntity.class));
    }

    @Test
    void register_DuplicateEmail_ThrowsDuplicateResourceException() {
        RegisterRequest request = new RegisterRequest("Nguyen Van A", "duplicate@example.com", "password123", null);
        when(userRepository.existsByEmail("duplicate@example.com")).thenReturn(true);

        DuplicateResourceException ex = assertThrows(DuplicateResourceException.class, () -> accountService.register(request));
        assertTrue(ex.getMessage().contains("already registered"));
        verify(userRepository, never()).save(any());
    }

    @Test
    void login_Success() {
        LoginRequest request = new LoginRequest("test@example.com", "password123");

        UserEntity user = new UserEntity("test@example.com", "hashedPassword", UserStatus.ACTIVE, LocalDateTime.now(), LocalDateTime.now());
        ReflectionTestUtils.setField(user, "id", 1L);

        CustomerProfileEntity profile = new CustomerProfileEntity(1L, "Nguyen Van A", "0901234567", LocalDateTime.now(), LocalDateTime.now());
        ReflectionTestUtils.setField(profile, "id", 10L);

        when(userRepository.findByEmail("test@example.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("password123", "hashedPassword")).thenReturn(true);
        when(customerProfileRepository.findByUserId(1L)).thenReturn(Optional.of(profile));
        when(jwtTokenProvider.generateToken(eq(1L), eq(10L), eq("test@example.com"), any())).thenReturn("mock.jwt.token");
        when(jwtTokenProvider.getExpirationSeconds()).thenReturn(86400L);

        AuthResponse response = accountService.login(request);

        assertNotNull(response);
        assertEquals("mock.jwt.token", response.accessToken());
        assertEquals("Bearer", response.tokenType());
        assertEquals(86400L, response.expiresIn());
        assertEquals("Nguyen Van A", response.user().fullName());
    }

    @Test
    void login_WrongPassword_ThrowsUnauthorizedException() {
        LoginRequest request = new LoginRequest("test@example.com", "wrongpass");

        UserEntity user = new UserEntity("test@example.com", "hashedPassword", UserStatus.ACTIVE, LocalDateTime.now(), LocalDateTime.now());
        when(userRepository.findByEmail("test@example.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("wrongpass", "hashedPassword")).thenReturn(false);

        UnauthorizedException ex = assertThrows(UnauthorizedException.class, () -> accountService.login(request));
        assertEquals("Invalid email or password", ex.getMessage());
    }

    @Test
    void login_UserNotFound_ThrowsUnauthorizedException() {
        LoginRequest request = new LoginRequest("unknown@example.com", "pass");
        when(userRepository.findByEmail("unknown@example.com")).thenReturn(Optional.empty());

        UnauthorizedException ex = assertThrows(UnauthorizedException.class, () -> accountService.login(request));
        assertEquals("Invalid email or password", ex.getMessage());
    }

    @Test
    void login_InactiveUser_ThrowsForbiddenException() {
        LoginRequest request = new LoginRequest("inactive@example.com", "password123");

        UserEntity user = new UserEntity("inactive@example.com", "hashedPassword", UserStatus.INACTIVE, LocalDateTime.now(), LocalDateTime.now());
        when(userRepository.findByEmail("inactive@example.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("password123", "hashedPassword")).thenReturn(true);

        assertThrows(ForbiddenException.class, () -> accountService.login(request));
    }

    @Test
    void getProfile_Success() {
        UserEntity user = new UserEntity("test@example.com", "hash", UserStatus.ACTIVE, LocalDateTime.now(), LocalDateTime.now());
        ReflectionTestUtils.setField(user, "id", 1L);

        CustomerProfileEntity profile = new CustomerProfileEntity(1L, "Nguyen Van A", "0901234567", LocalDateTime.now(), LocalDateTime.now());
        ReflectionTestUtils.setField(profile, "id", 10L);

        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(customerProfileRepository.findByUserId(1L)).thenReturn(Optional.of(profile));

        UserProfileResponse response = accountService.getProfile(1L);

        assertNotNull(response);
        assertEquals(1L, response.userId());
        assertEquals(10L, response.customerId());
        assertEquals("Nguyen Van A", response.fullName());
        assertEquals("test@example.com", response.email());
        assertEquals("0901234567", response.phone());
    }

    @Test
    void updateProfile_Success() {
        UserEntity user = new UserEntity("test@example.com", "hash", UserStatus.ACTIVE, LocalDateTime.now(), LocalDateTime.now());
        ReflectionTestUtils.setField(user, "id", 1L);

        CustomerProfileEntity profile = new CustomerProfileEntity(1L, "Nguyen Van A", "0901234567", LocalDateTime.now(), LocalDateTime.now());
        ReflectionTestUtils.setField(profile, "id", 10L);

        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(customerProfileRepository.findByUserId(1L)).thenReturn(Optional.of(profile));

        UpdateProfileRequest updateRequest = new UpdateProfileRequest("Nguyen Van B", "0988888888");
        UserProfileResponse response = accountService.updateProfile(1L, updateRequest);

        assertNotNull(response);
        assertEquals("Nguyen Van B", response.fullName());
        assertEquals("0988888888", response.phone());
        verify(customerProfileRepository).save(profile);
        verify(userRepository).save(user);
    }
}
