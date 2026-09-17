package com.example.airline_service;

import com.example.airline_service.Controller.AirlineController;
import com.example.airline_service.service.AirlineService;
import com.example.enums.AirlineStatus;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.*;
import org.springframework.data.web.PageableHandlerMethodArgumentResolver;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.List;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AirlinePaginationControllerTest {
    @Test void filtersAndStableSortReachServiceAndPageIsSerialized() throws Exception {
        var service = mock(AirlineService.class);
        var pageable = PageRequest.of(1, 10, Sort.by("name", "id"));
        when(service.getAllAirlines(pageable, "needle", AirlineStatus.ACTIVE))
                .thenReturn(new PageImpl<>(List.of(), pageable, 10));
        var mvc = MockMvcBuilders.standaloneSetup(new AirlineController(service))
                .setCustomArgumentResolvers(new PageableHandlerMethodArgumentResolver()).build();
        mvc.perform(get("/api/airlines").param("page", "1").param("size", "10")
                .param("search", "needle").param("status", "ACTIVE").param("sort", "name,asc"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(10))
                .andExpect(jsonPath("$.number").value(1)).andExpect(jsonPath("$.size").value(10));
        verify(service).getAllAirlines(pageable, "needle", AirlineStatus.ACTIVE);
    }

    @Test void defaultsAndInvalidParameters() throws Exception {
        var service = mock(AirlineService.class);
        var pageable = PageRequest.of(0, 20, Sort.by("name", "id"));
        when(service.getAllAirlines(pageable, null, null)).thenReturn(Page.empty(pageable));
        var mvc = MockMvcBuilders.standaloneSetup(new AirlineController(service))
                .setCustomArgumentResolvers(new PageableHandlerMethodArgumentResolver()).build();
        mvc.perform(get("/api/airlines")).andExpect(status().isOk()).andExpect(jsonPath("$.size").value(20));
        verify(service).getAllAirlines(pageable, null, null);
        for (String[] invalid : List.of(new String[]{"page", "-1"}, new String[]{"size", "0"},
                new String[]{"size", "101"}, new String[]{"page", "bad"}, new String[]{"status", "INVALID"},
                new String[]{"sort", "invalid,asc"})) {
            mvc.perform(get("/api/airlines").param(invalid[0], invalid[1])).andExpect(status().isBadRequest());
        }
        verifyNoMoreInteractions(service);
    }
}
