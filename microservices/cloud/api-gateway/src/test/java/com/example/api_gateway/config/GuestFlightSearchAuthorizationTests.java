package com.example.api_gateway.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.cloud.gateway.server.mvc.filter.LoadBalancerFilterFunctions;
import org.springframework.cloud.gateway.server.mvc.handler.HandlerFunctions;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.function.HandlerFilterFunction;
import org.springframework.web.servlet.function.HandlerFunction;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.ServerRequest;
import org.springframework.web.servlet.function.ServerResponse;

class GuestFlightSearchAuthorizationTests {
    private RouterFunction<ServerResponse> routes;

    @BeforeEach
    void setUp() {
        // Exercise real route matching and authorization without downstream services.
        try (var loadBalancer = mockStatic(LoadBalancerFilterFunctions.class);
                var proxy = mockStatic(HandlerFunctions.class)) {
            proxy.<HandlerFunction<ServerResponse>>when(HandlerFunctions::http)
                    .thenReturn(request -> ServerResponse.ok().build());
            for (String service : List.of("location-service", "flight-service", "airline-service",
                    "seat-service", "pricing-service", "booking-service", "payment-service")) {
                loadBalancer.<HandlerFilterFunction<ServerResponse, ServerResponse>>when(
                        () -> LoadBalancerFilterFunctions.lb(service))
                        .thenReturn((request, next) -> next.handle(request));
            }
            RouteConfig config = new RouteConfig(mock(JwtUtil.class));
            routes = config.publicAirportRoutes().and(config.publicFlightSearchRoutes())
                    .and(config.publicAirlineDropdownRoutes()).and(config.publicSearchCabinRoutes())
                    .and(config.publicSearchFareRoutes()).and(config.adminLocationServiceRoutes())
                    .and(config.adminAirlineServiceRoutes()).and(config.locationServiceRoutes())
                    .and(config.airlineServiceRoutes()).and(config.flightServiceRoutes())
                    .and(config.seatServiceRoutes()).and(config.pricingServiceRoutes())
                    .and(config.bookingServiceRoutes()).and(config.paymentServiceRoutes());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/airports", "/api/airports/", "/api/flights/search",
            "/api/airlines/dropdown", "/api/cabin-classes/aircraft/20",
            "/api/fares/flight/10/cabin-class/2"})
    void guestCanBrowseFlightsAndFares(String path) throws Exception {
        assertEquals(HttpStatus.OK, dispatch("GET", path).statusCode());
    }

    @ParameterizedTest
    @CsvSource({"POST,/api/airports", "PUT,/api/airports/1", "DELETE,/api/airports/1",
            "POST,/api/flights/search", "POST,/api/flights", "PUT,/api/flights/10",
            "GET,/api/airlines", "GET,/api/airlines/admin", "POST,/api/airlines/1/approve",
            "POST,/api/cabin-classes/aircraft/20", "GET,/api/seat-instances/1",
            "GET,/api/fares/30", "POST,/api/fares/flight/10/cabin-class/2",
            "GET,/api/flight-schedules", "GET,/api/bookings", "POST,/api/bookings",
            "GET,/api/payments/1", "POST,/api/payments"})
    void guestCannotAccessPrivateDataOrMutateInventory(String method, String path) {
        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> dispatch(method, path));
        assertEquals(HttpStatus.UNAUTHORIZED, error.getStatusCode());
    }

    private ServerResponse dispatch(String method, String path) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.addHeader("X-User-Roles", "ROLE_SYSTEM_ADMIN");
        return routes.route(ServerRequest.create(request, List.of())).orElseThrow()
                .handle(ServerRequest.create(request, List.of()));
    }
}
