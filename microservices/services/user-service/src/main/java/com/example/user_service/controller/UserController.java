package com.example.user_service.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import com.example.payload.dto.UserDto;
import com.example.user_service.mapper.UserMapper;
import com.example.user_service.model.User;
import com.example.user_service.service.UserService;

import com.example.enums.UserRole;
import org.springframework.data.domain.Page;

@RestController
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    @GetMapping("/api/users/profile")
    public ResponseEntity<UserDto> getUserProfile(
            @RequestHeader("X-User-Email") String email) throws Exception {
        User user = userService.getUserByEmail(email);
        return ResponseEntity.ok(UserMapper.toDto(user));
    }

    @GetMapping("/api/users/{userId}")
    public ResponseEntity<UserDto> getUserById(
            @PathVariable Long userId) throws Exception {
        User user = userService.getUserById(userId);
        return ResponseEntity.ok(UserMapper.toDto(user));
    }

    @GetMapping("/api/users")
    public ResponseEntity<Page<UserDto>> getUsers(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size,
            @RequestParam(defaultValue = "") String search,
            @RequestParam(required = false) UserRole role,
            @RequestParam(defaultValue = "fullName") String sortBy,
            @RequestParam(defaultValue = "asc") String direction) {
        return ResponseEntity.ok(userService.getUsers(page, size, search, role, sortBy, direction)
                .map(UserMapper::toDto));
    }
}

