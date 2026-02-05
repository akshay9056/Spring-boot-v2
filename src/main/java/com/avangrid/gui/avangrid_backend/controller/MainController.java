package com.avangrid.gui.avangrid_backend.controller;


import com.avangrid.gui.avangrid_backend.model.*;
import com.avangrid.gui.avangrid_backend.service.VpiRecordingService;


import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;



import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/v1")
public class MainController {

    private final VpiRecordingService service;


    public MainController(VpiRecordingService service) {

        this.service = service;
    }

    @PostMapping("/search")
    public ResponseEntity<VpiSearchResponse> search(
            @Valid @RequestBody VpiSearchRequest request) {
        return ResponseEntity.ok(service.getTableData(request));
    }

    @GetMapping("/metadata")
    public ResponseEntity<Map<String, Object>> getMetadata(
            @RequestParam @NotNull UUID id,
            @RequestParam @NotBlank String opco
    ) {
        return ResponseEntity.ok(service.getMetadata(id, opco));
    }


    @PostMapping("/recording")
    public ResponseEntity<ByteArrayResource> getRecordingVpi(
            @Valid @RequestBody RecordingRequest request) {
        return service.getRecordingVpi(request);
    }

    @PostMapping("/download")
    public ResponseEntity<byte[]> downloadVpi(
            @Valid @RequestBody List<RecordingRequest> requests) {
        return service.downloadVpi(requests);
    }

}
