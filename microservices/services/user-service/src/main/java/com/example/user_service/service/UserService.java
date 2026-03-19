package com.example.user_service.service;

import java.util.List;

import com.example.user_service.model.User;

public interface UserService {
    
    User getUserByEmail(String email) throws Exception;

    User getUserById(Long id) throws Exception;

    List<User> getUsers() throws Exception;
}
