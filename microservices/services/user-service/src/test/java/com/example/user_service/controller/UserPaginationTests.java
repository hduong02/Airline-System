package com.example.user_service.controller;

import com.example.user_service.model.User;
import com.example.enums.UserRole;
import com.example.user_service.service.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class UserPaginationTests {
    @Test
    void defaultRequestIsBoundedAndFiltersAreForwarded() throws Exception {
        UserService service = mock(UserService.class);
        when(service.getUsers(0, 25, "", null, "fullName", "asc"))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 25), 0));
        when(service.getUsers(2, 50, "needle", UserRole.ROLE_USER, "email", "desc"))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(2, 50), 0));
        var mvc = MockMvcBuilders.standaloneSetup(new UserController(service)).build();
        mvc.perform(get("/api/users"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.size").value(25));
        mvc.perform(get("/api/users").param("page", "2").param("size", "50")
                        .param("search", "needle").param("role", "ROLE_USER")
                        .param("sortBy", "email").param("direction", "desc"))
                .andExpect(status().isOk());
        verify(service).getUsers(2, 50, "needle", UserRole.ROLE_USER, "email", "desc");
        mvc.perform(get("/api/users").param("role", "ROLE_UNKNOWN"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void userListReturnsPageMetadataInsteadOfAnUnboundedArray() throws Exception {
        UserService service = mock(UserService.class);
        when(service.getUsers(0, 25, "", null, "fullName", "asc"))
                .thenReturn(new PageImpl<>(List.of(new User()), PageRequest.of(0, 25), 50024));
        MockMvcBuilders.standaloneSetup(new UserController(service)).build()
                .perform(get("/api/users").param("page", "0").param("size", "25"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.size").value(25))
                .andExpect(jsonPath("$.totalElements").value(50024))
                .andExpect(jsonPath("$.content[0].password").isEmpty());
    }
}
