package com.example.user_service.mapper;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import com.example.payload.dto.UserDto;
import com.example.user_service.model.User;

public class UserMapper {

    private UserMapper() {}

    public static UserDto toDto(User user) {
        UserDto dto = new UserDto();
        dto.setId(user.getId());
        dto.setEmail(user.getEmail());
        dto.setFullName(user.getFullName());
        dto.setPhone(user.getPhone());
        dto.setRole(user.getRole());
        dto.setLastLogin(user.getLastLogin());
        return dto;
    }

    public static List<UserDto> toDtoList(List<User> users) {
        return users.stream()
                .map(UserMapper::toDto)
                .collect(Collectors.toList());
    }

    public static Set<UserDto> toDtoSet(Set<User> users) {
        return users.stream()
                .map(UserMapper::toDto)
                .collect(Collectors.toSet());
    }
}