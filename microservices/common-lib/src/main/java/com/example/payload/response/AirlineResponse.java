package com.example.payload.response;

import lombok.*;

import java.time.Instant;

import com.example.embeddable.Support;
import com.example.enums.AirlineStatus;
import com.example.payload.dto.UserDto;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AirlineResponse {

    private Long id;

    private String iataCode;
    private String icaoCode;

    private String name;
    private String alias;
    private String country;

    private String logoUrl;
    private String website;

    private AirlineStatus status;
    private String alliance;

    private Instant createdAt;
    private Instant updatedAt;

    private Long ownerId;
    private UserDto owner;
    private Long updatedById;

    private CityResponse headquartersCity;
    private Support support;
}
