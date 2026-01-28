package com.avangrid.gui.avangrid_backend.controller;




import com.avangrid.gui.avangrid_backend.model.Recording;
import com.avangrid.gui.avangrid_backend.model.RecordingRequest;
import com.avangrid.gui.avangrid_backend.model.VpiSearchRequest;
import com.avangrid.gui.avangrid_backend.repository.RecordingsRepo;
import com.avangrid.gui.avangrid_backend.model.VpiSearchResponse;

import com.avangrid.gui.avangrid_backend.service.VpiRecordingService;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.List;

@CrossOrigin(origins = "*")
@RestController
public class MainController {

    private final RecordingsRepo repository;

    private final VpiRecordingService service;

    public MainController(RecordingsRepo repository, VpiRecordingService service) {

        this.repository = repository;
        this.service = service;
    }

    @GetMapping("/recordings")
    public List<Recording> getAllRecordings() {
        return repository.findAll();
    }

    @PostMapping("/fetch-metadata")
    public ResponseEntity<VpiSearchResponse> getAllRecordings(@RequestBody VpiSearchRequest request) {
        VpiSearchResponse response = service.getTableData(request);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/recording")
    public ResponseEntity<ByteArrayResource> getRecording(
            @RequestBody RecordingRequest request) {
        return service.getRecordingAsMp3(request);
    }

    @PostMapping("/recording-metadata")
    public Recording getMetadata(@RequestBody RecordingRequest request){
        return service.fetchMetadata(request);
    }

    @PostMapping("/download-recordings")
    public ResponseEntity<byte[]> downloadRecordings(
            @RequestBody List<RecordingRequest> requests) throws IOException {
        return service.downloadZip(requests);
    }






}
