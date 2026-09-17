package com.example.payload.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDateTime;
import java.util.List;

public record FlightDepartureFilterRequest(
        @NotNull @Size(max = 100) List<@NotNull Long> ids,
        @NotNull LocalDateTime boundary,
        boolean upcoming) {}
