package com.example.ancillary_service.service;

import com.example.ancillary_service.client.AirlineClient;
import com.example.ancillary_service.model.Ancillary;
import com.example.ancillary_service.repository.AncillaryRepository;
import com.example.ancillary_service.repository.InsuranceCoverageRepository;
import com.example.ancillary_service.service.impl.AncillaryServiceImpl;
import com.example.enums.AncillaryType;
import com.example.payload.request.AncillaryRequest;
import com.example.payload.response.AirlineResponse;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AncillaryListingTests {
    @Test
    void createdAncillaryAppearsOnlyInItsAirlineCatalogue() {
        AncillaryRepository repository = mock(AncillaryRepository.class);
        InsuranceCoverageRepository coverages = mock(InsuranceCoverageRepository.class);
        AirlineClient airlines = mock(AirlineClient.class);
        when(airlines.getAirlineByOwner(50020L)).thenReturn(
                AirlineResponse.builder().id(14L).ownerId(50020L).build());
        when(airlines.getAirlineByOwner(50021L)).thenReturn(
                AirlineResponse.builder().id(15L).ownerId(50021L).build());
        List<Ancillary> stored = new ArrayList<>();
        when(repository.save(any(Ancillary.class))).thenAnswer(call -> {
            Ancillary ancillary = call.getArgument(0);
            ancillary.setId(100L);
            stored.add(ancillary);
            return ancillary;
        });
        when(repository.findByAirlineId(any())).thenAnswer(call -> {
            Long airlineId = call.getArgument(0);
            return stored.stream().filter(ancillary -> airlineId.equals(ancillary.getAirlineId())).toList();
        });
        AncillaryServiceImpl service = new AncillaryServiceImpl(repository, coverages, airlines);
        service.createAncillary(50020L, AncillaryRequest.builder()
                .type(AncillaryType.BAGGAGE).name("Extra baggage").build());

        assertEquals(List.of(100L), service.getByAirlineId(50020L).stream()
                .map(ancillary -> ancillary.getId()).toList());
        assertEquals(14L, stored.getFirst().getAirlineId());
        assertTrue(service.getByAirlineId(50021L).isEmpty());
    }
}
