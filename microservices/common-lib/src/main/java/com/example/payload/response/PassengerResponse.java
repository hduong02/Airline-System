package com.example.payload.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.time.LocalDate;

import com.example.enums.Gender;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PassengerResponse {

    private Long id;
    private String firstName;
    private String lastName;
    private String email;
    private String phone;
    private LocalDate dateOfBirth;
    private Gender gender;

    private String nationality;

    private Long primaryUserId;
    private String primaryUserName;


    private Boolean isActive;
    private Integer age;
    private Boolean isAdult;
    private String fullName;

    private Instant createdAt;
    private Instant updatedAt;
}