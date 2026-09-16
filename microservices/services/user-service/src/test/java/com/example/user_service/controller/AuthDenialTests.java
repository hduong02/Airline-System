package com.example.user_service.controller;

import com.example.enums.UserRole;
import com.example.user_service.config.JwtProvider;
import com.example.user_service.model.User;
import com.example.user_service.repository.UserRepository;
import com.example.user_service.service.impl.AuthServiceImpl;
import com.example.user_service.service.impl.CustomUserDetailsService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

class AuthDenialTests {
    private final UserRepository users = mock(UserRepository.class);
    private final PasswordEncoder passwords = mock(PasswordEncoder.class);
    private final JwtProvider jwt = mock(JwtProvider.class);
    private final User user = new User();
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();
        user.setId(50103L);
        user.setEmail("traveler@example.test");
        user.setPassword("encoded-test-password");
        user.setRole(UserRole.ROLE_USER);
        when(users.findByEmail(user.getEmail())).thenReturn(user);
        mvc = MockMvcBuilders.standaloneSetup(new AuthController(new AuthServiceImpl(
                users, passwords, jwt, new CustomUserDetailsService(users))))
                .setControllerAdvice(new AuthExceptionHandler()).build();
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void invalidPasswordReturnsUnauthorizedWithoutTokenOrLoginUpdate() throws Exception {
        login("traveler@example.test", "wrong-password", 401);
        verify(users, never()).save(any());
        verifyNoInteractions(jwt);
        assertNull(user.getLastLogin());
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    void unknownEmailReturnsSameUnauthorizedDenial() throws Exception {
        login("missing@example.test", "wrong-password", 401);
        verify(users, never()).save(any());
        verifyNoInteractions(jwt, passwords);
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    void validCredentialsStillLoginAndUpdateLastLogin() throws Exception {
        when(passwords.matches("correct-password", user.getPassword())).thenReturn(true);
        when(jwt.generateToken(any(), eq(user.getId()))).thenReturn("test-token");
        login(user.getEmail(), "correct-password", 200);
        verify(users).save(user);
        verify(jwt).generateToken(any(), eq(user.getId()));
        assertNotNull(user.getLastLogin());
    }

    @Test
    void duplicateRegistrationReturnsConflict() throws Exception {
        mvc.perform(post("/auth/signup").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"traveler@example.test\",\"password\":\"test-password\"}"))
                .andExpect(status().isConflict());
        verify(users, never()).save(any());
        verifyNoInteractions(jwt, passwords);
    }

    @Test
    void systemAdminRegistrationReturnsForbidden() throws Exception {
        mvc.perform(post("/auth/signup").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"admin@example.test\",\"password\":\"test-password\",\"role\":\"ROLE_SYSTEM_ADMIN\"}"))
                .andExpect(status().isForbidden());
        verify(users, never()).save(any());
        verifyNoInteractions(jwt, passwords);
    }

    @Test
    void malformedLoginRemainsBadRequestWithoutParserDetails() throws Exception {
        mvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{invalid-json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Invalid authentication request"))
                .andExpect(jsonPath("$.trace").doesNotExist())
                .andExpect(jsonPath("$.exception").doesNotExist());
        verifyNoInteractions(jwt, passwords);
    }

    @Test
    void unexpectedDatabaseFailureReturnsSafeServerError() throws Exception {
        when(users.findByEmail(user.getEmail())).thenThrow(new IllegalStateException("database unavailable"));
        mvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"traveler@example.test\",\"password\":\"test-password\"}"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.message").value("Unable to process authentication request"))
                .andExpect(jsonPath("$.trace").doesNotExist())
                .andExpect(jsonPath("$.exception").doesNotExist());
        verifyNoInteractions(jwt, passwords);
    }

    private void login(String email, String password, int expectedStatus) throws Exception {
        var result = mvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().is(expectedStatus));
        if (expectedStatus == 401) {
            result.andExpect(jsonPath("$.message").value("Invalid email or password"))
                    .andExpect(jsonPath("$.trace").doesNotExist())
                    .andExpect(jsonPath("$.exception").doesNotExist());
        }
    }
}
