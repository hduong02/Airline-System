package com.example.payload.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.ZoneId;

import com.example.embeddable.Address;
import com.example.embeddable.GeoCode;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AirportRequest {

    @NotBlank(message = "IATA code is mandatory")
    @Size(min = 3, max = 3, message = "IATA code must be exactly 3 characters")
    private String iataCode;

    @NotBlank(message = "Airport name is mandatory")
    private String name;

    private ZoneId timeZone;

    @Valid
    private Address address;

    @NotNull(message = "City ID is mandatory")
    private Long cityId;

    @Valid
    private GeoCode geoCode;
}
