package com.avangrid.gui.avangrid_backend.controller;

import com.avangrid.gui.avangrid_backend.model.*;
import com.avangrid.gui.avangrid_backend.service.VpiRecordingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
@Validated
@Tag(name = "VPI Recording APIs")
public class MainController {

    private final VpiRecordingService service;

    // -------------------- SEARCH --------------------

    @Operation(summary = "Search VPI recordings")
    @PostMapping(value = "/search", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<VpiSearchResponse> search(
            @Valid @RequestBody VpiSearchRequest request) {

        return ResponseEntity.ok(service.getTableData(request));
    }

    // -------------------- METADATA --------------------

    @Operation(summary = "Get recording metadata")
    @GetMapping(value = "/metadata", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> getMetadata(
            @RequestParam @NotNull UUID id,
            @RequestParam @NotBlank String opco) {

        return ResponseEntity.ok(service.getMetadata(id, opco));
    }

    // -------------------- SINGLE RECORDING --------------------

    @Operation(summary = "Download single VPI recording")
    @PostMapping(value = "/recording", produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public ResponseEntity<ByteArrayResource> getRecording(
            @Valid @RequestBody RecordingRequest request) {

        return service.getRecordingVpi(request);
    }

    // -------------------- BULK DOWNLOAD --------------------

    @Operation(summary = "Download multiple VPI recordings (ZIP)")
    @PostMapping(value = "/download", produces = "application/zip")
    public ResponseEntity<byte[]> download(
            @Valid @RequestBody List<RecordingRequest> requests) {

        // Controller validates request shape (API responsibility)
        if (requests == null || requests.isEmpty()) {
            return ResponseEntity.badRequest().build();
        }

        return service.downloadVpi(requests);
    }
}
