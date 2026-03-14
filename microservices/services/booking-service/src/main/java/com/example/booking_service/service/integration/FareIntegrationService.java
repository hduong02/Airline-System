package com.example.booking_service.service.integration;

import org.springframework.stereotype.Service;

import com.example.booking_service.client.PricingClient;
import com.example.payload.response.FareResponse;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class FareIntegrationService {

private final PricingClient pricingClient;

    public Double calculateFareTotal(Long fareId) {
        FareResponse fare = pricingClient.getFareById(fareId);
        Double baseFare = fare.getBaseFare();
        Double taxesAndFees = fare.getTaxesAndFees() != null
                ? fare.getTaxesAndFees()
                : 0.0;
        Double airlineFees = fare.getAirlineFees() != null
                ? fare.getAirlineFees()
                : 0.0;
        return baseFare + taxesAndFees + airlineFees;
    }
}
