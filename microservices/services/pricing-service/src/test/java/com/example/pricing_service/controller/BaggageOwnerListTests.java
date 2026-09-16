package com.example.pricing_service.controller;

import com.example.payload.response.AirlineResponse;
import com.example.payload.response.BaggagePolicyResponse;
import com.example.pricing_service.client.AirlineClient;
import com.example.pricing_service.service.BaggagePolicyService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(MockitoExtension.class)
class BaggageOwnerListTests {
    @Mock private BaggagePolicyService policies;
    @Mock private AirlineClient airlines;
    @InjectMocks private BaggagePolicyController controller;

    @Test
    void frontendListResolvesAirlineFromAuthenticatedOwner() throws Exception {
        when(airlines.getAirlineByOwner(50020L)).thenReturn(
                AirlineResponse.builder().id(14L).ownerId(50020L).build());
        when(policies.getBaggagePoliciesByAirlineId(14L)).thenReturn(
                List.of(BaggagePolicyResponse.builder().id(100L).airlineId(14L).build()));
        MockMvcBuilders.standaloneSetup(controller).build()
                .perform(get("/api/baggage-policies/airline").header("X-User-Id", "50020")
                        .param("airlineId", "999"))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].id").value(100))
                .andExpect(jsonPath("$[0].airlineId").value(14));
        verify(airlines).getAirlineByOwner(50020L);
        verify(policies).getBaggagePoliciesByAirlineId(14L);
    }

    @Test
    void existingExplicitAirlineListRemainsAvailable() throws Exception {
        when(policies.getBaggagePoliciesByAirlineId(14L)).thenReturn(List.of());
        MockMvcBuilders.standaloneSetup(controller).build()
                .perform(get("/api/baggage-policies/airline/14"))
                .andExpect(status().isOk()).andExpect(content().json("[]"));
    }

    @Test
    void ownerListRequiresTrustedUserIdentity() throws Exception {
        MockMvcBuilders.standaloneSetup(controller).build()
                .perform(get("/api/baggage-policies/airline"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(airlines, policies);
    }
}
