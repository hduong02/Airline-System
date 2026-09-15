package com.example.api_gateway.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.cloud.gateway.server.mvc.filter.LoadBalancerFilterFunctions;
import org.springframework.cloud.gateway.server.mvc.handler.HandlerFunctions;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.function.*;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PaymentBatchAuthorizationTests {
    private RouterFunction<ServerResponse> routes;

    @BeforeEach
    void setUp() {
        JwtUtil jwt = mock(JwtUtil.class);
        when(jwt.isTokenValid("customer-token")).thenReturn(true);
        when(jwt.extractUserId("customer-token")).thenReturn(20L);
        when(jwt.extractEmail("customer-token")).thenReturn("customer@example.com");
        when(jwt.extractAuthorities("customer-token")).thenReturn("ROLE_USER");
        for (String roles : List.of("ROLE_AIRLINE_OWNER", "ROLE_SYSTEM_ADMIN",
                "ROLE_USER, ROLE_SYSTEM_ADMIN", "ROLE_SYSTEM_ADMIN_FAKE")) {
            when(jwt.isTokenValid(roles)).thenReturn(true);
            when(jwt.extractUserId(roles)).thenReturn(30L);
            when(jwt.extractEmail(roles)).thenReturn("test@example.com");
            when(jwt.extractAuthorities(roles)).thenReturn(roles);
        }

        try (var loadBalancer = mockStatic(LoadBalancerFilterFunctions.class);
                var proxy = mockStatic(HandlerFunctions.class)) {
            proxy.<HandlerFunction<ServerResponse>>when(HandlerFunctions::http)
                    .thenReturn(request -> ServerResponse.ok()
                            .header("Forwarded-User-Id", request.headers().firstHeader("X-User-Id")).build());
            loadBalancer.<HandlerFilterFunction<ServerResponse, ServerResponse>>when(
                    () -> LoadBalancerFilterFunctions.lb("payment-service"))
                    .thenReturn((request, next) -> next.handle(request));
            routes = new RouteConfig(jwt).paymentServiceRoutes();
        }
    }

    @Test
    void listingRejectsCustomersAndOwnersEvenWithSpoofedAdminHeader() {
        for (String token : List.of("customer-token", "ROLE_AIRLINE_OWNER", "ROLE_SYSTEM_ADMIN_FAKE")) {
            for (String path : List.of("/api/payments", "/api/payments/")) {
                ServerRequest request = request("GET", path, token);
                var error = assertThrows(ResponseStatusException.class,
                        () -> routes.route(request).orElseThrow().handle(request));
                assertEquals(HttpStatus.FORBIDDEN, error.getStatusCode());
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"ROLE_SYSTEM_ADMIN", "ROLE_USER, ROLE_SYSTEM_ADMIN"})
    void listingAllowsSystemAdmins(String token) throws Exception {
        ServerRequest request = request("GET", "/api/payments", token);
        assertEquals(HttpStatus.OK, routes.route(request).orElseThrow().handle(request).statusCode());
    }

    @Test
    void listingRequiresValidAuthentication() {
        for (String token : new String[] {null, "invalid"}) {
            ServerRequest request = request("GET", "/api/payments", token);
            var error = assertThrows(ResponseStatusException.class,
                    () -> routes.route(request).orElseThrow().handle(request));
            assertEquals(HttpStatus.UNAUTHORIZED, error.getStatusCode());
        }
    }

    @Test
    void publicBatchUsesTokenIdentityOverClientSuppliedHeader() throws Exception {
        ServerRequest request = request("/api/payments/batch/bookings", "customer-token");
        ServerResponse response = routes.route(request).orElseThrow().handle(request);
        assertEquals("20", response.headers().getFirst("Forwarded-User-Id"));
    }

    @Test
    void publicBatchRequiresValidAuthentication() {
        for (String token : new String[] {null, "invalid"}) {
            ServerRequest request = request("/api/payments/batch/bookings", token);
            var error = assertThrows(ResponseStatusException.class,
                    () -> routes.route(request).orElseThrow().handle(request));
            assertEquals(HttpStatus.UNAUTHORIZED, error.getStatusCode());
        }
    }

    @Test
    void internalBatchIsNotRoutedToPaymentService() {
        assertTrue(routes.route(request("/internal/payments/batch/bookings", "customer-token")).isEmpty());
    }

    private ServerRequest request(String path, String token) {
        return request("POST", path, token);
    }

    private ServerRequest request(String method, String path, String token) {
        var request = new MockHttpServletRequest(method, path);
        if (token != null) {
            request.addHeader("Authorization", "Bearer " + token);
        }
        request.addHeader("X-User-Id", "10");
        request.addHeader("X-User-Roles", "ROLE_SYSTEM_ADMIN");
        return ServerRequest.create(request, List.of());
    }
}
