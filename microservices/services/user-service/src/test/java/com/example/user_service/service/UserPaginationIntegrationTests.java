package com.example.user_service.service;

import com.example.enums.UserRole;
import com.example.user_service.model.User;
import com.example.user_service.repository.UserRepository;
import com.example.user_service.service.impl.UserServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

@DataJpaTest(properties = {"spring.config.import=", "spring.cloud.config.enabled=false"}, showSql = false)
@Import(UserServiceImpl.class)
class UserPaginationIntegrationTests {
    @Autowired UserRepository users;
    @Autowired UserService service;

    @BeforeEach
    void seed() {
        users.deleteAll();
        users.saveAll(IntStream.range(0, 130).mapToObj(i -> {
            User user = new User();
            user.setFullName("Same Name");
            user.setEmail("person" + i + "@example.test");
            user.setPhone(i == 129 ? "special-phone" : null);
            user.setRole(i == 129 ? UserRole.ROLE_AIRLINE_OWNER : UserRole.ROLE_USER);
            user.setPassword("test-hash");
            return user;
        }).toList());
        users.flush();
    }

    @Test
    void databasePaginationIsBoundedAndStableForDuplicateNames() {
        var first = service.getUsers(0, 25, "", null, "fullName", "asc");
        var second = service.getUsers(1, 25, "", null, "fullName", "asc");
        assertEquals(25, first.getNumberOfElements());
        assertEquals(130, first.getTotalElements());
        assertEquals(6, first.getTotalPages());
        assertTrue(first.stream().noneMatch(a -> second.stream().anyMatch(b -> a.getId().equals(b.getId()))));
        assertTrue(first.getContent().getLast().getId() < second.getContent().getFirst().getId());
        assertEquals(100, service.getUsers(-1, Integer.MAX_VALUE, "", null, "id", "asc").getNumberOfElements());
    }

    @Test
    void searchAndRoleFilterApplyBeforePagination() {
        var result = service.getUsers(0, 25, " PERSON129@EXAMPLE.TEST ", UserRole.ROLE_AIRLINE_OWNER, "email", "desc");
        assertEquals(1, result.getTotalElements());
        assertEquals("person129@example.test", result.getContent().getFirst().getEmail());
        assertEquals(1, service.getUsers(0, 25, "special-phone", null, "id", "asc").getTotalElements());
        assertEquals(130, service.getUsers(0, 25, "same name", null, "id", "asc").getTotalElements());
        assertEquals(0, service.getUsers(0, 25, "person129", UserRole.ROLE_USER, "id", "asc").getTotalElements());
        assertEquals(0, service.getUsers(0, 25, "%", null, "id", "asc").getTotalElements());
        assertEquals(0, service.getUsers(0, 25, "_", null, "id", "asc").getTotalElements());
    }

    @Test
    void unsupportedSortAndOversizedSearchAreBadRequests() {
        for (var args : List.of(new String[]{"password", "asc", ""}, new String[]{"email", "invalid", ""}, new String[]{"id", "asc", "x".repeat(101)})) {
            var failure = assertThrows(ResponseStatusException.class,
                    () -> service.getUsers(0, 25, args[2], null, args[0], args[1]));
            assertEquals(400, failure.getStatusCode().value());
        }
    }
}
