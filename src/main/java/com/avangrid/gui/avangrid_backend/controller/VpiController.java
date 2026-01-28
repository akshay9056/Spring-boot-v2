package com.avangrid.gui.avangrid_backend.controller;

import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.core.io.ByteArrayResource;

import com.avangrid.gui.avangrid_backend.service.VpiService;



@RestController
@RequestMapping("/vpi")
public class VpiController {
    
    @Autowired
    private VpiService vpiService;
    
    @GetMapping("/metadata")
    public ResponseEntity<Map<String, Object>> getMetadataInRange(
            @RequestParam("from_date") String fromDate,
            @RequestParam("to_date") String toDate,
            @RequestParam("opco") String opco,
            @RequestParam(value = "page_number", defaultValue = "1") int pageNumber,
            @RequestParam(value = "page_size", defaultValue = "50") int pageSize,
            @RequestParam(value = "session_id", required = false) String sessionId) {
        
        System.out.println("Running Vpi Metadata");
        
        Map<String, Object> response = vpiService.getMetadataInRange(
            fromDate, toDate, opco, pageNumber, pageSize, sessionId);
        
        System.out.println("Completed Vpi Metadata");
        return ResponseEntity.ok(response);
    }

    @GetMapping("/filter")
    public ResponseEntity<Map<String, Object>> getFilteredMetadata(
             @RequestParam String sessionId,
            @RequestParam(required = false) List<String> extensionNum,
            @RequestParam(required = false) List<String> objectID,
            @RequestParam(required = false) List<String> channelNum,
            @RequestParam(required = false) List<String> AniAliDigits,
            @RequestParam(required = false) List<String> Name,
            @RequestParam(defaultValue = "1") int pageNumber,
            @RequestParam(defaultValue = "10") int pageSize){
        
        System.out.println("Running Vpi Filtered Metadata");
        
        Map<String, Object> response = vpiService.getFilteredMetadata(sessionId, 
            extensionNum, objectID, channelNum, AniAliDigits, Name, pageNumber, pageSize);
        
        System.out.println("Completed Vpi Filtered Metadata");
        return ResponseEntity.ok(response);
    }


    @GetMapping("/check-connection")
    public ResponseEntity<String> checkConnection() { 
        String response = vpiService.checkConnection();  
        return ResponseEntity.ok(response);
    }

    @GetMapping("/recording")
    public ResponseEntity<ByteArrayResource> getRecording(
            @RequestParam String filename,
            @RequestParam String date,
            @RequestParam String opco) {

        return vpiService.getRecordingAsMp3(filename, date, opco);
    }

    @GetMapping("/debug")
    public ResponseEntity<List<String>> getCmpWavFiles() {
        List<String> wavFiles = vpiService.getAllCmpWavFiles();
        return ResponseEntity.ok(wavFiles);
    }



}
