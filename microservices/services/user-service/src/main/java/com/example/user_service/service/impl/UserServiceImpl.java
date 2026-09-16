package com.example.user_service.service.impl;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import com.example.user_service.model.User;
import com.example.user_service.repository.UserRepository;
import com.example.user_service.service.UserService;
import com.example.enums.UserRole;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import jakarta.persistence.criteria.Predicate;

import java.util.ArrayList;
import java.util.Locale;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {

    private final UserRepository userRepository;

    @Override
    public User getUserByEmail(String email) throws Exception {
        User user = userRepository.findByEmail(email);
        if (user == null) {
            throw new Exception("User not found with email: " + email);
        }
        return user;
    }

    @Override
    public User getUserById(Long id) throws Exception {
        return userRepository.findById(id)
                .orElseThrow(() -> new Exception("User not found with id: " + id));
    }

    @Override
    public Page<User> getUsers(int page, int size, String search, UserRole role, String sortBy, String direction) {
        if (!Set.of("fullName", "email", "lastLogin", "id").contains(sortBy)
                || !("asc".equalsIgnoreCase(direction) || "desc".equalsIgnoreCase(direction))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid user sort order");
        }
        String keyword = search == null ? "" : search.trim().toLowerCase(Locale.ROOT);
        if (keyword.length() > 100) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Search must be at most 100 characters");
        }
        // Escape LIKE wildcards so search text is treated literally.
        String pattern = "%" + keyword.replace("!", "!!").replace("%", "!%")
                .replace("_", "!_") + "%";
        Specification<User> filter = (root, query, cb) -> {
            var predicates = new ArrayList<Predicate>();
            if (!keyword.isEmpty()) {
                predicates.add(cb.or(
                        cb.like(cb.lower(root.get("fullName")), pattern, '!'),
                        cb.like(cb.lower(root.get("email")), pattern, '!'),
                        cb.like(cb.lower(root.get("phone")), pattern, '!')));
            }
            if (role != null) predicates.add(cb.equal(root.get("role"), role));
            return cb.and(predicates.toArray(Predicate[]::new));
        };
        Sort sort = Sort.by(Sort.Direction.fromString(direction), sortBy);
        if (!"id".equals(sortBy)) sort = sort.and(Sort.by("id"));
        return userRepository.findAll(filter, PageRequest.of(Math.max(0, page), Math.clamp(size, 1, 100), sort));
    }
}
