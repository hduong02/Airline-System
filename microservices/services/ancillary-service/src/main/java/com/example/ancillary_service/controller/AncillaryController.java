package com.example.ancillary_service.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import com.example.ancillary_service.service.AncillaryService;
import com.example.payload.request.AncillaryRequest;
import com.example.payload.response.AncillaryResponse;

import java.util.List;

@RestController
@RequestMapping("/api/ancillaries")
@RequiredArgsConstructor
public class AncillaryController {

    private final AncillaryService ancillaryService;

    @PostMapping
    public ResponseEntity<AncillaryResponse> create(
            @Valid @RequestBody AncillaryRequest request,
            @RequestHeader("X-User-Id") Long userId) {
        return ResponseEntity.ok(ancillaryService.createAncillary(userId, request));
    }

    @GetMapping("/{id}")
    public ResponseEntity<AncillaryResponse> getById(@PathVariable Long id)
            throws Exception {
        return ResponseEntity.ok(ancillaryService.getById(id));
    }

    @GetMapping
    public ResponseEntity<List<AncillaryResponse>> getAllByAirlineId(
            @RequestHeader("X-User-Id") Long userId) {
        return ResponseEntity.ok(ancillaryService.getByAirlineId(userId));
    }

    @PutMapping("/{id}")
    public ResponseEntity<AncillaryResponse> update(@PathVariable Long id,
            @RequestBody AncillaryRequest request) throws Exception {
        return ResponseEntity.ok(ancillaryService.updateAncillary(id, request));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) throws Exception {
        ancillaryService.deleteAncillary(id);
        return ResponseEntity.noContent().build();
    }
}