package com.example.api_gateway.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.cloud.gateway.server.mvc.filter.LoadBalancerFilterFunctions;
import org.springframework.cloud.gateway.server.mvc.handler.HandlerFunctions;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.HandlerFilterFunction;
import org.springframework.web.servlet.function.HandlerFunction;
import org.springframework.web.servlet.function.ServerRequest;
import org.springframework.web.servlet.function.ServerResponse;

class AirlineAuthorizationTests {

    private RouterFunction<ServerResponse> routes;

    @BeforeEach
    void setUp() {
        JwtUtil jwtUtil = mock(JwtUtil.class);
        for (String role : List.of("ROLE_USER", "ROLE_AIRLINE_OWNER", "ROLE_SYSTEM_ADMIN",
                "ROLE_USER,ROLE_SYSTEM_ADMIN", "ROLE_SYSTEM_ADMIN_FAKE")) {
            when(jwtUtil.isTokenValid(role)).thenReturn(true);
            when(jwtUtil.extractAuthorities(role)).thenReturn(role);
            when(jwtUtil.extractEmail(role)).thenReturn("test@example.com");
            when(jwtUtil.extractUserId(role)).thenReturn(1L);
        }

        // Keep the actual route predicates and auth filters, but replace the
        // network hop so a successful response means the request was forwarded.
        try (var loadBalancer = mockStatic(LoadBalancerFilterFunctions.class);
                var proxy = mockStatic(HandlerFunctions.class)) {
            proxy.<HandlerFunction<ServerResponse>>when(HandlerFunctions::http)
                    .thenReturn(request -> ServerResponse.ok().build());
            loadBalancer.<HandlerFilterFunction<ServerResponse, ServerResponse>>when(
                    () -> LoadBalancerFilterFunctions.lb("airline-service"))
                    .thenReturn((request, next) -> next.handle(request));
            RouteConfig config = new RouteConfig(jwtUtil);
            routes = config.adminAirlineServiceRoutes().and(config.airlineServiceRoutes());
        }
    }

    @ParameterizedTest
    @CsvSource({
            "approve,ROLE_USER", "suspend,ROLE_USER", "ban,ROLE_USER",
            "approve,ROLE_AIRLINE_OWNER", "suspend,ROLE_AIRLINE_OWNER", "ban,ROLE_AIRLINE_OWNER",
            "approve,ROLE_SYSTEM_ADMIN_FAKE"
    })
    void statusChangesRejectNonAdmins(String action, String role) {
        assertDenied(request("POST", "/api/airlines/102/" + action, role), HttpStatus.FORBIDDEN);
    }

    @ParameterizedTest
    @ValueSource(strings = {"approve", "suspend", "ban"})
    void statusChangesAllowSystemAdmin(String action) throws Exception {
        assertEquals(HttpStatus.OK, dispatch(request("POST", "/api/airlines/102/" + action,
                "ROLE_SYSTEM_ADMIN")).statusCode());
    }

    @ParameterizedTest
    @ValueSource(strings = {"approve", "suspend", "ban"})
    void statusChangesRequireAuthentication(String action) {
        assertDenied(request("POST", "/api/airlines/102/" + action, null), HttpStatus.UNAUTHORIZED);
        assertDenied(request("POST", "/api/airlines/102/" + action, "invalid"), HttpStatus.UNAUTHORIZED);
    }

    @Test
    void travelerCannotSpoofAdminHeader() {
        assertDenied(request("POST", "/api/airlines/102/approve", "ROLE_USER"), HttpStatus.FORBIDDEN);
    }

    @Test
    void adminMayHaveMultipleRoles() throws Exception {
        assertEquals(HttpStatus.OK, dispatch(request("POST", "/api/airlines/102/approve",
                "ROLE_USER,ROLE_SYSTEM_ADMIN")).statusCode());
    }

    @Test
    void listingRemainsAdminOnly() throws Exception {
        assertDenied(request("GET", "/api/airlines", "ROLE_USER"), HttpStatus.FORBIDDEN);
        assertEquals(HttpStatus.OK, dispatch(request("GET", "/api/airlines", "ROLE_SYSTEM_ADMIN"))
                .statusCode());
    }

    @ParameterizedTest
    @CsvSource({"POST,/api/airlines", "PUT,/api/airlines", "GET,/api/airlines/admin",
            "GET,/api/airlines/dropdown", "GET,/api/airlines/102", "GET,/api/aircrafts"})
    void ownerRoutesRemainAccessible(String method, String path) throws Exception {
        assertEquals(HttpStatus.OK, dispatch(request(method, path, "ROLE_AIRLINE_OWNER")).statusCode());
    }

    private ServerRequest request(String method, String path, String token) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        if (token != null) {
            request.addHeader("Authorization", "Bearer " + token);
        }
        request.addHeader("X-User-Roles", "ROLE_SYSTEM_ADMIN");
        return ServerRequest.create(request, List.of());
    }

    private ServerResponse dispatch(ServerRequest request) throws Exception {
        return routes.route(request).orElseThrow().handle(request);
    }

    private void assertDenied(ServerRequest request, HttpStatus status) {
        ResponseStatusException error = assertThrows(ResponseStatusException.class, () -> dispatch(request));
        assertEquals(status, error.getStatusCode());
    }
}
