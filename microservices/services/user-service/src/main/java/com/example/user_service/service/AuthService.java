package com.example.user_service.service;

import com.example.payload.dto.UserDto;
import com.example.payload.response.AuthResponse;

public interface AuthService {
    AuthResponse login(String email, String password) throws Exception;
    AuthResponse signup(UserDto request) throws Exception;
}
