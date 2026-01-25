package com.example.pricing_service.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import com.example.payload.request.FareRulesRequest;
import com.example.payload.response.FareRulesResponse;
import com.example.pricing_service.service.FareRulesService;

import java.util.List;

@RestController
@RequestMapping("/api/fare-rules")
@RequiredArgsConstructor
public class FareRulesController {

    private final FareRulesService fareRulesService;

    @PostMapping
    public ResponseEntity<FareRulesResponse> createFareRules(
            @Valid @RequestBody FareRulesRequest request) throws Exception {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(fareRulesService.createFareRules(request));
    }

    @GetMapping("/{id}")
    public ResponseEntity<FareRulesResponse> getFareRulesById(
            @PathVariable Long id) throws Exception {
        return ResponseEntity.ok(fareRulesService.getFareRulesById(id));
    }

    @GetMapping("/fare/{fareId}")
    public ResponseEntity<FareRulesResponse> getFareRulesByFareId(
            @PathVariable Long fareId) throws Exception {
        return ResponseEntity.ok(fareRulesService.getFareRulesByFareId(fareId));
    }

    @GetMapping("/airline/{airlineId}")
    public ResponseEntity<List<FareRulesResponse>> getFareRulesByAirlineId(
            @PathVariable Long airlineId) {
        return ResponseEntity.ok(fareRulesService.getFareRulesByAirlineId(airlineId));
    }

    @PutMapping("/{id}")
    public ResponseEntity<FareRulesResponse> updateFareRules(
            @PathVariable Long id,
            @Valid @RequestBody FareRulesRequest request) throws Exception {
        return ResponseEntity.ok(fareRulesService.updateFareRules(id, request));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteFareRules(@PathVariable Long id) throws Exception {
        fareRulesService.deleteFareRules(id);
        return ResponseEntity.noContent().build();
    }
}