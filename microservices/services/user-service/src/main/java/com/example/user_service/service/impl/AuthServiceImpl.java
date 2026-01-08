package com.example.user_service.service.impl;

import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import com.example.enums.UserRole;
import com.example.payload.dto.UserDto;
import com.example.payload.response.AuthResponse;
import com.example.user_service.config.JwtProvider;
import com.example.user_service.mapper.UserMapper;
import com.example.user_service.model.User;
import com.example.user_service.repository.UserRepository;
import com.example.user_service.service.AuthService;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtProvider jwtProvider;
    private final CustomUserDetailsService customUserDetailsService;
    /*
    Steps:
        1. Check if email already exists
        2. Encode password using BCrypt
        3. Save user in database
        4. Generate JWT token
        5. Return token and user information
    */
    @Override
    public AuthResponse signup(UserDto request) throws Exception {
        User existingUser = userRepository.findByEmail(request.getEmail());
        if (existingUser != null) {
            throw new Exception("Email already registered");
        }

        if (request.getRole() == UserRole.ROLE_SYSTEM_ADMIN) {
            throw new Exception("Cannot register as SYSTEM_ADMIN");
        }

        User createdUser = new User();
        createdUser.setEmail(request.getEmail());
        createdUser.setPassword(passwordEncoder.encode(request.getPassword()));
        createdUser.setPhone(request.getPhone());
        createdUser.setFullName(request.getFullName());
        createdUser.setRole(request.getRole());
        createdUser.setLastLogin(LocalDateTime.now());

        User savedUser = userRepository.save(createdUser);

        Authentication authentication = new UsernamePasswordAuthenticationToken(
                savedUser.getEmail(), savedUser.getPassword()
        );

        SecurityContextHolder.getContext().setAuthentication(authentication);
        String jwt = jwtProvider.generateToken(authentication, savedUser.getId());

        AuthResponse response = new AuthResponse();
        response.setTitle("Welcome " + savedUser.getFullName());
        response.setMessage("Registration successful");
        response.setUser(UserMapper.toDto(savedUser));
        response.setJwt(jwt);
        return response;
    }

    /*
    Steps:
        1. Load user by email
        2. Compare password with BCrypt
        3. Update `lastLogin` time
        4. Generate JWT token
        5. Return token and user information
    */
    @Override
    public AuthResponse login(String email, String password) throws Exception {
        Authentication authentication = authenticate(email, password);
        SecurityContextHolder.getContext().setAuthentication(authentication);

        User user = userRepository.findByEmail(email);
        String token = jwtProvider.generateToken(authentication, user.getId());

        user.setLastLogin(LocalDateTime.now());
        userRepository.save(user);

        AuthResponse response = new AuthResponse();
        response.setTitle("Login successful");
        response.setMessage("Welcome back " + user.getFullName());
        response.setJwt(token);
        response.setUser(UserMapper.toDto(user));
        return response;
    }

    private Authentication authenticate(String email, String password) throws Exception {
        UserDetails userDetails = customUserDetailsService
                .loadUserByUsername(email);
        if (userDetails == null) {
            throw new Exception("User not found with email: " + email);
        }
        if (!passwordEncoder.matches(password, userDetails.getPassword())) {
            throw new Exception("Invalid password");
        }
        return new UsernamePasswordAuthenticationToken(
                email, null, userDetails.getAuthorities());
    }
}
    