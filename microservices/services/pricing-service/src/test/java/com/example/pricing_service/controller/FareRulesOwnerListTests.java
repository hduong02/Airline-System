package com.example.pricing_service.controller;

import com.example.payload.response.AirlineResponse;
import com.example.payload.response.FareRulesResponse;
import com.example.pricing_service.client.AirlineClient;
import com.example.pricing_service.service.FareRulesService;
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
class FareRulesOwnerListTests {
    @Mock private FareRulesService rules;
    @Mock private AirlineClient airlines;
    @InjectMocks private FareRulesController controller;

    @Test
    void frontendListResolvesAirlineFromAuthenticatedOwner() throws Exception {
        when(airlines.getAirlineByOwner(50020L)).thenReturn(
                AirlineResponse.builder().id(14L).ownerId(50020L).build());
        when(rules.getFareRulesByAirlineId(14L)).thenReturn(
                List.of(FareRulesResponse.builder().id(100L).airlineId(14L).build()));

        MockMvcBuilders.standaloneSetup(controller).build()
                .perform(get("/api/fare-rules").header("X-User-Id", "50020")
                        .param("page", "0").param("size", "100").param("airlineId", "999"))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].id").value(100))
                .andExpect(jsonPath("$[0].airlineId").value(14));
        verify(airlines).getAirlineByOwner(50020L);
        verify(rules).getFareRulesByAirlineId(14L);
    }

    @Test
    void existingExplicitAirlineListRemainsAvailable() throws Exception {
        when(rules.getFareRulesByAirlineId(14L)).thenReturn(List.of());
        MockMvcBuilders.standaloneSetup(controller).build()
                .perform(get("/api/fare-rules/airline/14"))
                .andExpect(status().isOk()).andExpect(content().json("[]"));
    }

    @Test
    void anotherOwnerReceivesOnlyTheirAirlinesRules() throws Exception {
        when(airlines.getAirlineByOwner(50021L)).thenReturn(
                AirlineResponse.builder().id(15L).ownerId(50021L).build());
        when(rules.getFareRulesByAirlineId(15L)).thenReturn(List.of());

        MockMvcBuilders.standaloneSetup(controller).build()
                .perform(get("/api/fare-rules").header("X-User-Id", "50021")
                        .param("airlineId", "14"))
                .andExpect(status().isOk()).andExpect(content().json("[]"));
        verify(rules).getFareRulesByAirlineId(15L);
    }

    @Test
    void ownerListRequiresTrustedUserIdentity() throws Exception {
        MockMvcBuilders.standaloneSetup(controller).build()
                .perform(get("/api/fare-rules"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(airlines, rules);
    }
}
