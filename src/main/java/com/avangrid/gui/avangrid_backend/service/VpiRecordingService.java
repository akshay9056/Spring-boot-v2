package com.avangrid.gui.avangrid_backend.service;

import com.avangrid.gui.avangrid_backend.exception.BlobAccessException;
import com.avangrid.gui.avangrid_backend.exception.InvalidRequestException;
import com.avangrid.gui.avangrid_backend.exception.RecordingNotFoundException;
import com.avangrid.gui.avangrid_backend.exception.RecordingProcessingException;
import com.avangrid.gui.avangrid_backend.model.*;

import com.avangrid.gui.avangrid_backend.repository.AzureBlobRepository;
import com.avangrid.gui.avangrid_backend.repository.RecordingsRepo;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StreamUtils;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import ws.schild.jave.Encoder;
import ws.schild.jave.MultimediaObject;
import ws.schild.jave.encode.AudioAttributes;
import ws.schild.jave.encode.EncodingAttributes;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.io.*;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@Service
public class VpiRecordingService {

    private final RecordingsRepo recordingsRepo;

    private final AzureBlobRepository vpiAzureRepository;

    public VpiRecordingService(RecordingsRepo recordingsRepo,AzureBlobRepository vpiAzureRepository ) {
        this.recordingsRepo = recordingsRepo;
        this.vpiAzureRepository = vpiAzureRepository;
    }

    public VpiSearchResponse getTableData( VpiSearchRequest request){

        if (request == null) {
            throw new InvalidRequestException("Request cannot be null");
        }
        LocalDateTime fromDate = parseDateTime(request.getFrom_date());
        LocalDateTime toDate = parseDateTime(request.getTo_date());
        validateDateRange(fromDate, toDate);

        VpiSearchResponse response = new VpiSearchResponse();
        PaginationResponse pageResponse = new PaginationResponse();
        Specification<Recording> spec = buildSpecification(request);

        int pageNumber = request.getPagination().getPageNumber();
        int pageSize = request.getPagination().getPageSize() > 0 ? request.getPagination().getPageSize() : 20;
        int safePage = (pageNumber > 0 ? pageNumber - 1 : 0);

        Pageable pageable = PageRequest.of(safePage, pageSize, Sort.by("dateAdded").descending());
        Page<Recording> pageResult = recordingsRepo.findAll(spec, pageable);

        List<Map<String, Object>> records = new ArrayList<>();
        for (Recording rec : pageResult.getContent()) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("fileName", rec.getFileName());
            map.put("extensionNum", rec.getExtensionNum());
            map.put("objectId", rec.getObjectId());
            map.put("channelNum", rec.getChannelNum());
            map.put("aniAliDigits", rec.getAniAliDigits());
            map.put("name", rec.getName());
            map.put("dateAdded", rec.getDateAdded());
            map.put("opco",rec.getOpco());
            map.put("agentID",rec.getAgentID());
            map.put("duration",rec.getDuration());
            map.put("direction",rec.getDirection());
            records.add(map);
        }

        response.setData(records);
        response.setMessage("Success");
        response.setStatus("200");

        pageResponse.setPageNumber(pageResult.getNumber() + 1);
        pageResponse.setPageSize( pageResult.getSize());
        pageResponse.setTotalRecords(pageResult.getTotalElements());
        pageResponse.setTotalPages(pageResult.getTotalPages());
        response.setPagination(pageResponse);

        return response;
    }

    private Specification<Recording> buildSpecification(VpiSearchRequest request) {

        Specification<Recording> spec = Specification.where(null);
        VpiFiltersRequest filters = request.getFilters();
        spec = spec.and(RecordingSpecifications.dateBetween(parseDateTime(request.getFrom_date()), parseDateTime(request.getTo_date())));

        if (filters.getFileName() != null && !filters.getFileName().isEmpty()) {
            spec = spec.and(RecordingSpecifications.fileNameContainsAny(filters.getFileName()));
        }

        if (request.getOpco() != null && !request.getOpco().isEmpty()) {
            spec = spec.and(RecordingSpecifications.containsString("opco",request.getOpco()));
        }

        if (filters.getExtensionNum() != null && !filters.getExtensionNum().isEmpty()) {
            spec = spec.and(RecordingSpecifications.extensionNumContainsAny(filters.getExtensionNum()));
        }

        if (filters.getObjectID() != null && !filters.getObjectID().isEmpty()) {
            spec = spec.and(RecordingSpecifications.objectIdContainsAny(filters.getObjectID()));
        }

        if (filters.getChannelNum() != null && !filters.getChannelNum().isEmpty()) {
            spec = spec.and(RecordingSpecifications.channelNumContainsAny( filters.getChannelNum()));
        }

        if (filters.getAniAliDigits() != null && !filters.getAniAliDigits().isEmpty()) {
            spec = spec.and(RecordingSpecifications.aniAliDigitsContainsAny( filters.getAniAliDigits()));
        }

        if (filters.getName() != null && !filters.getName().isEmpty()) {
            spec = spec.and(RecordingSpecifications.nameContainsAny(filters.getName()));
        }

        return spec;
    }

    public Recording fetchMetadata(RecordingRequest request) {
        validateRequest(request);
        List<Recording> recordingMetadata = recordingsRepo.findAllByOpcoAndFileName(request.getOpco(), request.getFilename());
        if (recordingMetadata == null || recordingMetadata.isEmpty()) {
            throw new RecordingNotFoundException("No Recordings found with OPCO=" + request.getOpco() + " and fileName=" + request.getFilename());
        }
        return recordingMetadata.getFirst();
    }

    public ResponseEntity<ByteArrayResource> getRecordingAsMp3(RecordingRequest request) {

        validateRequest(request);
        LocalDateTime fileDate = parseDateTime(request.getDate());
        String prefix = String.format("%s/%d/%d/%d/%s",
                request.getOpco(),
                    fileDate.getYear(),
                    fileDate.getMonthValue(),
                    fileDate.getDayOfMonth(),
                request.getFilename());

        List<String> blobs;
        try {
            blobs = vpiAzureRepository.listBlobs(prefix);
        } catch (Exception e) {
            throw new BlobAccessException("Failed to list blobs for prefix: " + prefix);
        }
        for (String blobName : blobs) {
            if (blobName.endsWith(".wav")) {
                byte[] wavData = vpiAzureRepository.getBlobContent(blobName);
                try {
                    byte[] mp3Data = convertWavToMp3(wavData);
                    String mp3Filename = request.getFilename().replace(".wav", ".mp3");
                    HttpHeaders headers = new HttpHeaders();
                    headers.setContentType(MediaType.parseMediaType("audio/mpeg"));
                    headers.setContentDispositionFormData("inline", mp3Filename);
                    ByteArrayResource resource = new ByteArrayResource(mp3Data);
                    return ResponseEntity.ok()
                                .headers(headers)
                                .body(resource);

                    } catch (Exception e) {
                        throw new RecordingProcessingException(
                                "Error Converting wav to MP3 " + e.getMessage());
                    }
                }
            }
        throw new RecordingNotFoundException("Recording not found with OPCO=" + request.getOpco() +" and filename="+request.getFilename());
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

    public ResponseEntity<byte[]> downloadZip(List<RecordingRequest> requests) {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             ZipOutputStream zos = new ZipOutputStream(baos)) {

            for (RecordingRequest req : requests) {
                validateRequest(req);
                LocalDateTime fileDate = parseDateTime(req.getDate());
                String prefix = String.format("%s/%d/%d/%d/%s",
                        req.getOpco(),
                        fileDate.getYear(),
                        fileDate.getMonthValue(),
                        fileDate.getDayOfMonth(),
                        req.getFilename());

                // Find matching blob (wav only)
                List<String> blobs = vpiAzureRepository.listBlobs(prefix);
                if (blobs == null || blobs.isEmpty()) {
                    throw new RecordingNotFoundException("No blobs found for prefix: " + prefix);
                }
                for (String blobName : blobs) {
                    if (blobName.endsWith(".wav")) {
                        zos.putNextEntry(new ZipEntry(req.getFilename())); // filename in ZIP

                        try (InputStream blobStream = vpiAzureRepository.getBlobStream(blobName)) {
                            StreamUtils.copy(blobStream, zos);
                        }

                        zos.closeEntry();
                    }
                }
            }

            zos.finish();
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
            headers.setContentDispositionFormData("attachment", "recordings.zip");

            return new ResponseEntity<>(baos.toByteArray(), headers, HttpStatus.OK);

        }
        catch (InvalidRequestException | RecordingNotFoundException e) {
            throw e;
        }
        catch (IOException e) {
            throw new RecordingProcessingException("Error processing ZIP file :"+e);
        }
        catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(("Failed to generate ZIP: " + e.getMessage()).getBytes());
        }
    }

    private void validateRequest(RecordingRequest req) {
        if (req.getFilename() == null || req.getFilename().isBlank()) {
            throw new InvalidRequestException("Filename is required");
        }
        if (req.getOpco() == null || req.getOpco().isBlank()) {
            throw new InvalidRequestException("OPCO is required");
        }
        if (req.getDate() == null || req.getDate().isBlank()) {
            throw new InvalidRequestException("Date is required");
        }
        Set<String> allowedOpcos = Set.of("RGE", "CMP", "NYSEG");
        if (!allowedOpcos.contains(req.getOpco().trim())) {
            throw new InvalidRequestException("Invalid Opco");
        }
    }

    private void validateDateRange(LocalDateTime fromDate, LocalDateTime toDate) {
        if (toDate.isBefore(fromDate)) {
            throw new InvalidRequestException("Provide valid date range");
        }
    }

    private LocalDateTime parseDateTime(String dateStr) {
        try {
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
            return LocalDateTime.parse(dateStr, formatter);
        } catch (DateTimeParseException e) {
            throw new InvalidRequestException("Invalid date format " + dateStr);
        }
    }
}
