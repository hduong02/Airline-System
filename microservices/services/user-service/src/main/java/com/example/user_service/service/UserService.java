package com.example.user_service.service;

import com.example.enums.UserRole;
import org.springframework.data.domain.Page;

import com.example.user_service.model.User;

public interface UserService {
    
    User getUserByEmail(String email) throws Exception;

    User getUserById(Long id) throws Exception;

    Page<User> getUsers(int page, int size, String search, UserRole role, String sortBy, String direction);
}
