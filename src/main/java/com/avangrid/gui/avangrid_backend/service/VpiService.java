package com.avangrid.gui.avangrid_backend.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import org.springframework.core.io.ClassPathResource;
import org.springframework.util.StreamUtils;
import ws.schild.jave.Encoder;
import ws.schild.jave.MultimediaObject;
import ws.schild.jave.encode.AudioAttributes;
import ws.schild.jave.encode.EncodingAttributes;


import org.springframework.core.io.ByteArrayResource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;


import com.avangrid.gui.avangrid_backend.repository.AzureBlobRepository;


import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;

@Service
public class VpiService {
    
    @Autowired
    private AzureBlobRepository vpiRepository;
    
    private final ConcurrentHashMap<String, List<Map<String, Object>>> sessionCache = new ConcurrentHashMap<>();
    private final Map<String, Object> cacheSessionFilter = new ConcurrentHashMap<>();
    private volatile int recordsCount = 0;
    private static final int MAX_RECORDS = 10000;


    public Map<String, Object> getMetadataInRange(String fromDateStr, String toDateStr, 
                                                 String opco, int pageNumber, int pageSize, String sessionId) {
        
        // Parse and validate dates
        LocalDateTime fromDate = parseDateTime(fromDateStr);
        LocalDateTime toDate = parseDateTime(toDateStr);
        
        validateDateRange(fromDate, toDate);
        validateOpco(opco);
        validatePagination(pageNumber, pageSize);
        
        List<Map<String, Object>> filteredMetadata;
        
        // Reuse session if available
        if (sessionId != null && sessionCache.containsKey(sessionId)) {
            filteredMetadata = sessionCache.get(sessionId);
        } else {
            // Fresh request: generate metadata and create a session
            filteredMetadata = new ArrayList<>();
            LocalDate currentDate = fromDate.toLocalDate();
            
            while (!currentDate.isAfter(toDate.toLocalDate()) && recordsCount < MAX_RECORDS) {
                String prefix = buildPrefix(opco, currentDate);
                List<String> blobNames = vpiRepository.listBlobs(prefix);
                
                for (String blobName : blobNames) {
                    if (blobName.endsWith(".xml")) {
                        byte[] xmlData = vpiRepository.getBlobContent(blobName);
                        List<Map<String, Object>> records = opco.equals("CMP") ? 
                            parseXmlCmp(xmlData) : parseXmlFormat(xmlData);
                        
                        if (currentDate.equals(fromDate.toLocalDate()) || currentDate.equals(toDate.toLocalDate())) {
                            // Filter by time for boundary dates
                            for (Map<String, Object> record : records) {
                                if (isRecordInTimeRange(record, fromDate, toDate)) {
                                    filteredMetadata.add(record);
                                    
                                }
                            }
                        } else {
                            // Add all records for dates in between
                            filteredMetadata.addAll(records);
                        }
                        
                        recordsCount += records.size();
                    }
                }
                currentDate = currentDate.plusDays(1);
            }
            
            sessionId = UUID.randomUUID().toString();
            sessionCache.put(sessionId, filteredMetadata);
        }
        
        // Pagination
        int startIdx = (pageNumber - 1) * pageSize;
        int endIdx = Math.min(startIdx + pageSize, filteredMetadata.size());
        List<Map<String, Object>> pageData = filteredMetadata.subList(startIdx, endIdx);
        
        int totalRecords = filteredMetadata.size();
        int totalPages = (totalRecords + pageSize - 1) / pageSize;
        
        Map<String, Object> response = new HashMap<>();
        response.put("data", pageData);
        response.put("page_number", pageNumber);
        response.put("page_size", pageSize);
        response.put("total_records", totalRecords);
        response.put("total_pages", totalPages);
        response.put("session_id", sessionId);
        
        return response;
    }
    
    private LocalDateTime parseDateTime(String dateStr) {
        try {
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
            return LocalDateTime.parse(dateStr, formatter);
        } catch (DateTimeParseException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid date format");
        }
    }
    
    private void validateDateRange(LocalDateTime fromDate, LocalDateTime toDate) {
        if (toDate.isBefore(fromDate)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "`to_date` must be after `from_date`");
        }
    }
    
    private void validateOpco(String opco) {
        if (!Arrays.asList("CMP", "RGE", "NYSEG").contains(opco)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid `opco` value. Must be 'CMP', 'RGE', or 'NYSEG'");
        }
    }
    
    private void validatePagination(int pageNumber, int pageSize) {
        if (pageNumber < 1) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Page number must be greater than 0");
        }
        if (pageSize < 1) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Page size must be greater than 0");
        }
    }
    
    private String buildPrefix(String opco, LocalDate date) {
        if ("CMP".equals(opco)) {
            return String.format("%s/%d/%d/%d/Metadata/", 
                opco, date.getYear(), date.getMonthValue(), date.getDayOfMonth());
        } else {
            return String.format("%s/%d/%d/%d/", 
                opco, date.getYear(), date.getMonthValue(), date.getDayOfMonth());
        }
    }
    
    private List<Map<String, Object>> parseXmlCmp(byte[] xmlData) {
        List<Map<String, Object>> metadataList = new ArrayList<>();
    try {
        XmlMapper xmlMapper = new XmlMapper();
        JsonNode root = xmlMapper.readTree(xmlData);
        JsonNode mediaNodes = root.path("Objects").path("Media");

        if (mediaNodes.isArray()) {
            for (JsonNode media : mediaNodes) {
                Map<String, Object> mediaData = new HashMap<>();
                // Add attributes
                media.fields().forEachRemaining(entry -> {
                    mediaData.put(entry.getKey(), entry.getValue().isValueNode() ? entry.getValue().asText() : entry.getValue().toString());
                });
                // Add XML attributes (e.g., Type, FileName, Result)
                media.fields().forEachRemaining(entry -> {
                    JsonNode node = entry.getValue();
                    if (node.isObject() && node.has("@Type")) {
                        node.fields().forEachRemaining(attr -> {
                            if (attr.getKey().startsWith("@")) {
                                mediaData.put(attr.getKey().substring(1), attr.getValue().asText());
                            }
                        });
                    }
                });
                metadataList.add(mediaData);
            }
        } else if (mediaNodes.isObject()) {
            Map<String, Object> mediaData = new HashMap<>();
            mediaNodes.fields().forEachRemaining(entry -> {
                mediaData.put(entry.getKey(), entry.getValue().isValueNode() ? entry.getValue().asText() : entry.getValue().toString());
            });
            metadataList.add(mediaData);
        }
    } catch (IOException | IllegalArgumentException e) {
        System.err.println("Error parsing XML to metadata map: " + e.getMessage());
    }
    return metadataList;
    }
    
    private List<Map<String, Object>> parseXmlFormat(byte[] xmlData) {
        List<Map<String, Object>> result = new ArrayList<>();
    try {
        XmlMapper xmlMapper = new XmlMapper();
        JsonNode mediaNode = xmlMapper.readTree(xmlData);

        // Convert JsonNode to Map
        ObjectMapper objectMapper = new ObjectMapper();
        Map<String, Object> mediaMap = objectMapper.convertValue(mediaNode, Map.class);

        result.add(mediaMap);
    } catch (IOException | IllegalArgumentException e) {
        System.err.println("Error parsing XML to metadata map: " + e.getMessage());
            // Optionally log the error or throw custom exception
            
    }
    return result;
    }
    
    private boolean isRecordInTimeRange(Map<String, Object> record, LocalDateTime fromDate, LocalDateTime toDate) {
        try {
            String startTimeStr = (String) record.get("startTime");
            if (startTimeStr == null) return false;
            
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("M/d/yyyy h:mm:ss a");
            LocalDateTime recordDateTime = LocalDateTime.parse(startTimeStr, formatter);
            
            return !recordDateTime.isBefore(fromDate) && !recordDateTime.isAfter(toDate);
        } catch (Exception e) {
            System.err.println("Error parsing record time: " + e.getMessage());
            return false;
        }
    }

    public String checkConnection() {
     boolean available = vpiRepository.isContainerAvailable();
        return available ? "Azure Blob Storage container is accessible ✅"
                         : "Azure Blob Storage container is NOT accessible ❌";

    }

    public List<String> getAllCmpWavFiles() {
        String prefix = "CMP/2016/1/1/Metadata/";
        List<String> allBlobs = vpiRepository.listBlobs(prefix);
        System.out.println("All blobs: " + allBlobs);
        // Filter only .wav files
        return allBlobs.stream()
                .filter(name -> name.toLowerCase().endsWith(".xml"))
                .collect(Collectors.toList());
    }

    public Map<String, Object> getFilteredMetadata(String sessionId, List<String> extensionNum, List<String> objectID,
            List<String> channelNum, List<String> aniAliDigits, List<String> name, int pageNumber, int pageSize) {
        List<Map<String, Object>> metadataList = new ArrayList<>();
        Map<String, List<String>> appliedFilters = createAppliedFiltersMap(extensionNum, objectID, channelNum, aniAliDigits, name);
        
        // Check cache logic
        if (cacheSessionFilter.containsKey(sessionId)) {
            if (Objects.equals(cacheSessionFilter.get("filters"), appliedFilters)) {
                metadataList = (List<Map<String, Object>>) cacheSessionFilter.get(sessionId);
                System.out.println("Using cached session data for sessionId: " + sessionId);

            } else {
                // Get all values from session cache
                metadataList = sessionCache.values().stream()
                        .flatMap(List::stream)
                        .collect(Collectors.toList());
            }
        } else {
            if (!sessionCache.containsKey(sessionId)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Original session not found");
            }
            
            metadataList = sessionCache.get(sessionId);
            
            if (!hasAnyFilter(extensionNum, objectID, channelNum, aniAliDigits, name)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"No Filter applied");
            }
        }
        
        // Perform filtering
        List<Map<String, Object>> filtered = metadataList.stream()
                .filter(metadata -> matchesFilters(metadata, extensionNum, objectID, channelNum, aniAliDigits, name))
                .collect(Collectors.toList());
        
        String newSessionId = UUID.randomUUID().toString();
        cacheSessionFilter.put(newSessionId, filtered);
        cacheSessionFilter.put("filters", appliedFilters);
        
        // Pagination
        int totalRecords = filtered.size();
        int totalPages = (totalRecords + pageSize - 1) / pageSize;
        
        int start = (pageNumber - 1) * pageSize;
        int end = Math.min(start + pageSize, totalRecords);
        List<Map<String, Object>> paginated = filtered.subList(start, end);
        
        System.out.println("Completed VPI filter");
        
        Map<String, Object> response = new HashMap<>();
        response.put("data", paginated);
        response.put("page_number", pageNumber);
        response.put("page_size", pageSize);
        response.put("total_records", totalRecords);
        response.put("total_pages", totalPages);
        response.put("session_id", newSessionId);
        
        return response;
    }
    
    private Map<String, List<String>> createAppliedFiltersMap(List<String> extensionNum, List<String> objectID, 
            List<String> channelNum, List<String> aniAliDigits, List<String> name) {
        Map<String, List<String>> filters = new HashMap<>();
        filters.put("extensionNum", extensionNum);
        filters.put("objectID", objectID);
        filters.put("channelNum", channelNum);
        filters.put("AniAliDigits", aniAliDigits);
        filters.put("Name", name);
        return filters;
    }
    
    private boolean hasAnyFilter(List<String> extensionNum, List<String> objectID, List<String> channelNum, 
            List<String> aniAliDigits, List<String> name) {
        return (extensionNum != null && !extensionNum.isEmpty()) || 
               (objectID != null && !objectID.isEmpty()) || 
               (channelNum != null && !channelNum.isEmpty()) || 
               (aniAliDigits != null && !aniAliDigits.isEmpty()) || 
               (name != null && !name.isEmpty());
    }
    
    private boolean matchesFilters(Map<String, Object> metadata, List<String> extensionNum, List<String> objectID, 
            List<String> channelNum, List<String> aniAliDigits, List<String> name) {
        return matchesFilterList(metadata.get("extensionNum"), extensionNum) &&
               matchesFilterList(metadata.get("objectID"), objectID) &&
               matchesFilterList(metadata.get("channelNum"), channelNum) &&
               matchesFilterListContains(metadata.getOrDefault("aniAliDigits", ""), aniAliDigits) &&
               matchesNameFilter(metadata, name);
    }
    
    private boolean matchesFilterList(Object metadataValue, List<String> filterValues) {
        if (filterValues == null || filterValues.isEmpty()) {
            return true;
        }
        return filterValues.contains(String.valueOf(metadataValue));
    }
    
    private boolean matchesFilterListContains(Object metadataValue, List<String> filterValues) {
        if (filterValues == null || filterValues.isEmpty()) {
            return true;
        }
        String metadataStr = String.valueOf(metadataValue);
        return filterValues.stream().anyMatch(filterValue -> metadataStr.contains(filterValue));
    }
    
    private boolean matchesNameFilter(Map<String, Object> metadata, List<String> nameFilters) {
        if (nameFilters == null || nameFilters.isEmpty()) {
            return true;
        }
        
        String fullName = String.valueOf(metadata.getOrDefault("fullName", ""));
        String name = String.valueOf(metadata.getOrDefault("name", ""));
        
        return nameFilters.stream().anyMatch(filterValue -> 
            fullName.contains(filterValue) || name.contains(filterValue));
    }
    
    // Method to add data to session cache (for testing or initialization)
    public void addToSessionCache(String sessionId, List<Map<String, Object>> data) {
        sessionCache.put(sessionId, data);
    }
    
    // Method to get session cache data
    public List<Map<String, Object>> getSessionCacheData(String sessionId) {
        return sessionCache.get(sessionId);
    }

     public boolean sessionExists(String sessionId) {
        return sessionCache.containsKey(sessionId);
    }

    public ResponseEntity<ByteArrayResource> getRecordingAsMp3(String filename, String date, String opco) {
        try {
            // Parse date like 5/10/2018 4:01:28 PM
            LocalDateTime fileDate = parseDateTime(date);

            // Build prefix path
            String prefix = String.format("%s/%d/%d/%d/%s",
                    opco,
                    fileDate.getYear(),
                    fileDate.getMonthValue(),
                    fileDate.getDayOfMonth(),
                    filename);


            // List blobs with the prefix
            List<String> blobs = vpiRepository.listBlobs(prefix);

            // Find WAV file
            for (String blobName : blobs) {
                if (blobName.endsWith(".wav")) {
                    System.out.println("Found WAV file: " + blobName);

                    // Get WAV data
                    byte[] wavData = vpiRepository.getBlobContent(blobName);

                    // Convert to MP3
                    try {
                        byte[] mp3Data = convertWavToMp3(wavData);

                        // Prepare response
                        String mp3Filename = filename.replace(".wav", ".mp3");

                        HttpHeaders headers = new HttpHeaders();
                        headers.setContentType(MediaType.parseMediaType("audio/mpeg"));
                        headers.setContentDispositionFormData("inline", mp3Filename);

                        ByteArrayResource resource = new ByteArrayResource(mp3Data);

                        return ResponseEntity.ok()
                                .headers(headers)
                                .body(resource);

                    } catch (Exception e) {
                        throw new ResponseStatusException(
                                HttpStatus.INTERNAL_SERVER_ERROR,
                                "Error Converting wav to MP3 " + e.getMessage());
                    }
                }
            }

            // No WAV file found
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Recording not found");

        } catch (DateTimeParseException e) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Invalid date format. Expected M/D/YYYY H:MM:SS AM/PM.");
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            throw new ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "Unexpected error: " + e.getMessage());
        }
    }

    private byte[] convertWavToMp3(byte[] wavData) throws Exception {
        Path tempDir = Files.createTempDirectory("audio_conversion_");
        String uniqueId = UUID.randomUUID().toString();
        Path inputFile = tempDir.resolve("input_" + uniqueId + ".wav");
        Path outputFile = tempDir.resolve("output_" + uniqueId + ".mp3");

        try {
            // Create temporary directory
            Files.write(inputFile, wavData);

            MultimediaObject source = new MultimediaObject(inputFile.toFile());

            AudioAttributes audio = new AudioAttributes();
            audio.setCodec("libmp3lame");
            audio.setBitRate(128000);
            audio.setSamplingRate(44100);
            audio.setChannels(2);

            EncodingAttributes attrs = new EncodingAttributes();
            attrs.setOutputFormat("mp3");
            attrs.setAudioAttributes(audio);

            Encoder encoder = new Encoder();
            encoder.encode(source, outputFile.toFile(), attrs);

            return Files.readAllBytes(outputFile);
        } finally {
            // Clean up temporary files
            Files.deleteIfExists(inputFile);
            Files.deleteIfExists(outputFile);
            Files.deleteIfExists(tempDir);
        }
    }

}