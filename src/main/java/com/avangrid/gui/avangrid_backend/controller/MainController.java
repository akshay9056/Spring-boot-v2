package com.avangrid.gui.avangrid_backend.controller;


import com.avangrid.gui.avangrid_backend.model.*;
import com.avangrid.gui.avangrid_backend.service.VpiRecordingService;


import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;


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

//    @GetMapping("/recordings")
//    @Transactional(transactionManager = "cmpTransactionManager")
//    public List<VpiMetadata> getAllRecordings() {
//
//        return captureRepo.findAll()
//                .stream()
//                .map(rec -> {
//                    VpiMetadata dto = new VpiMetadata();
//
//                    dto.setObjectId(rec.getObjectId());
//                    dto.setDateAdded(rec.getDateAdded());
//                    dto.setStartTime(rec.getStartTime());
//                    dto.setDuration(rec.getDuration());
//                    dto.setTags(rec.getTags());
//                    dto.setChannelName(rec.getChannelName());
//                    dto.setCallId(rec.getCallId());
//
//                    if (rec.getUser() != null) {
//                        dto.setUserId(rec.getUser().getUserid());
//                        dto.setUserName(rec.getUser().getFullname());
//                    }
//
//                    return dto;
//                })
//                .toList();
//    }


    @PostMapping("/search")
    public ResponseEntity<VpiSearchResponse> search(
            @Valid @RequestBody VpiSearchRequest request) {
        return ResponseEntity.ok(service.getTableData(request));
    }

    @GetMapping("/metadata")
    public ResponseEntity<Map<String,Object>> getMetadata(
            @Valid @RequestParam(required = true) UUID id,@Valid @RequestParam(required = true) String opco) {
        return ResponseEntity.ok(service.getMetadata(id,opco));
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
