package com.example.user_service.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import com.example.user_service.model.User;

public interface UserRepository extends JpaRepository<User, Long>, JpaSpecificationExecutor<User> {
    User findByEmail(String email);
}
