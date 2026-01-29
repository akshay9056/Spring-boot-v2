package com.avangrid.gui.avangrid_backend.service;

import com.avangrid.gui.avangrid_backend.exception.InvalidRequestException;
import com.avangrid.gui.avangrid_backend.exception.RecordingNotFoundException;
import com.avangrid.gui.avangrid_backend.exception.RecordingProcessingException;
import com.avangrid.gui.avangrid_backend.infra.cmp.entity.VpiCaptureCmp;
import com.avangrid.gui.avangrid_backend.infra.cmp.repository.VpiCmpRepo;
import com.avangrid.gui.avangrid_backend.infra.nyseg.entity.VpiCaptureNyseg;
import com.avangrid.gui.avangrid_backend.infra.nyseg.repository.VpiNysegRepo;
import com.avangrid.gui.avangrid_backend.infra.rge.entity.VpiCaptureRge;
import com.avangrid.gui.avangrid_backend.infra.rge.repository.VpiRgeRepo;
import com.avangrid.gui.avangrid_backend.model.*;
import com.avangrid.gui.avangrid_backend.infra.azure.AzureBlobRepository;

import net.bramp.ffmpeg.FFmpeg;
import net.bramp.ffmpeg.FFprobe;
import net.bramp.ffmpeg.builder.FFmpegBuilder;
import net.bramp.ffmpeg.FFmpegExecutor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.util.StreamUtils;
import org.springframework.util.StringUtils;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Service for managing VPI (Voice Portal Interface) recordings.
 * Handles recording retrieval, conversion, download, and metadata operations.
 */
@Service
public class VpiRecordingService {

    private static final Logger logger = LoggerFactory.getLogger(VpiRecordingService.class);

    // Constants
    private static final DateTimeFormatter DATE_TIME_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MIN_PAGE_NUMBER = 1;
    private static final String STATUS_SUCCESS = "200";
    private static final String MESSAGE_SUCCESS = "Success";
    private static final Set<String> ALLOWED_OPCOS = Set.of("RGE", "CMP","NYSEG");

    // File naming constants
    private static final String WAV_EXTENSION = ".wav";
    private static final String MP3_EXTENSION = ".mp3";
    private static final int FILENAME_DATE_START = 5;
    private static final int FILENAME_DATE_END = 15;
    private static final int FILENAME_TIME_START = 16;
    private static final int FILENAME_TIME_END = 24;
    private static final int FILENAME_CUSTOMER_START = 24;

    // Audio conversion constants
    private static final String AUDIO_CODEC = "libmp3lame";
    private static final int AUDIO_BITRATE = 128000;
    private static final int AUDIO_CHANNELS = 2;
    private static final int AUDIO_SAMPLE_RATE = 44100;
    private static final String TEMP_DIR_PREFIX = "audio_conversion_";
    private static final String INPUT_FILE_PREFIX = "input_";
    private static final String OUTPUT_FILE_PREFIX = "output_";

    // Configurable paths
    @Value("${ffmpeg.path:ffmpeg}")
    private String ffmpegPath;

    @Value("${ffprobe.path:ffprobe}")
    private String ffprobePath;

    // Dependencies
    private final AzureBlobRepository vpiAzureRepository;
    private final VpiCmpRepo cmpRepo;
    private final VpiNysegRepo nysegRepo;
    private final VpiRgeRepo rgeRepo;

    public VpiRecordingService(
            AzureBlobRepository vpiAzureRepository,
            @Autowired(required = false) VpiCmpRepo cmpRepo,
            @Autowired(required = false) VpiNysegRepo nysegRepo,
            @Autowired(required = false) VpiRgeRepo rgeRepo) {
        this.vpiAzureRepository = vpiAzureRepository;
        this.cmpRepo = cmpRepo;
        this.nysegRepo = nysegRepo;
        this.rgeRepo = rgeRepo;
    }

    private void assertRepoEnabled(String opco) {
        switch (opco.toUpperCase()) {
            case "CMP" -> {
                if (cmpRepo == null) {
                    throw new InvalidRequestException("CMP datasource is disabled");
                }
            }
            case "NYSEG" -> {
                if (nysegRepo == null) {
                    throw new InvalidRequestException("NYSEG datasource is disabled");
                }
            }
            case "RGE" -> {
                if (rgeRepo == null) {
                    throw new InvalidRequestException("RGE datasource is disabled");
                }
            }
        }
    }

    /**
     * Retrieves paginated table data based on search criteria.
     *
     * @param request Search request containing date range, OPCO, filters, and pagination
     * @return VpiSearchResponse with paginated results
     * @throws InvalidRequestException if date range or parameters are invalid
     */
    public VpiSearchResponse getTableData(VpiSearchRequest request) {
        logger.debug("Fetching table data for request: {}", request);

        validateSearchRequest(request);

        LocalDateTime from = parseDateTime(request.getFrom_date());
        LocalDateTime to = parseDateTime(request.getTo_date());

        if (to.isBefore(from)) {
            throw new InvalidRequestException("End date must be after start date");
        }

        Pageable pageable = createPageable(request.getPagination());
        Page<VpiMetadata> pageResult = search(from, to, request.getOpco(), request.getFilters(), pageable);

        return buildSearchResponse(pageResult);
    }

    /**
     * Retrieves full metadata for a specific recording.
     *
     * @param id Unique identifier of the recording
     * @param opco Operating company code
     * @return Map containing all metadata fields
     * @throws InvalidRequestException if OPCO is invalid
     * @throws RecordingNotFoundException if recording not found
     */
    public Map<String, Object> getMetadata(UUID id, String opco) {
        logger.debug("Fetching metadata for id: {} and opco: {}", id, opco);

        validateOpco(opco);

        List<Map<String, Object>> metadata = switch (opco.toUpperCase()) {
            case "CMP" -> metadataFull(cmpRepo.findByObjectId(id));
            case "NYSEG" -> metadataFull(nysegRepo.findByObjectId(id));
            case "RGE" -> metadataFull(rgeRepo.findByObjectId(id));
            default -> throw new InvalidRequestException("Invalid OPCO code: " + opco);
        };

        if (metadata.isEmpty()) {
            throw new RecordingNotFoundException(
                    String.format("Recording not found with ID=%s and OPCO=%s", id, opco));
        }

        return metadata.getFirst();
    }

    /**
     * Retrieves a VPI recording and converts it to MP3 format.
     *
     * @param request Recording request with filename, OPCO, and date
     * @return ResponseEntity containing MP3 audio data
     * @throws InvalidRequestException if request parameters are invalid
     * @throws RecordingNotFoundException if recording is not found
     * @throws RecordingProcessingException if conversion fails
     */
    public ResponseEntity<ByteArrayResource> getRecordingVpi(RecordingRequest request) {
        logger.info("Retrieving recording for: {}", request.getUsername());

        validateRequest(request);


        LocalDateTime fileDate = parseDateTime(request.getDate());
        String blobName = findRecordingBlob(request.getOpco(), fileDate, request.getUsername());

        byte[] wavData = downloadBlob(blobName);
        byte[] mp3Data = convertWavToMp3(wavData);

        return buildAudioResponse(mp3Data, request.getUsername());
    }

    /**
     * Downloads multiple VPI recordings as a ZIP file.
     *
     * @param requests List of recording requests
     * @return ResponseEntity containing ZIP file with all recordings
     * @throws RecordingProcessingException if ZIP creation fails
     */
    public ResponseEntity<byte[]> downloadVpi(List<RecordingRequest> requests) {
        logger.info("Downloading {} recordings as ZIP", requests.size());

        if (requests.isEmpty()) {
            throw new InvalidRequestException("Request list cannot be empty");
        }

        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             ZipOutputStream zos = new ZipOutputStream(baos)) {

            for (RecordingRequest req : requests) {
                addRecordingToZip(req, zos);
            }

            zos.finish();
            return buildZipResponse(baos.toByteArray());

        } catch (IOException e) {
            logger.error("Error creating ZIP file", e);
            throw new RecordingProcessingException("Failed to create ZIP file: " + e.getMessage(), e);
        }
    }

    /**
     * Searches for recordings based on criteria.
     *
     * @param from Start date/time
     * @param to End date/time
     * @param opco Operating company
     * @param filters Additional search filters
     * @param pageable Pagination information
     * @return Page of VpiMetadata results
     */
    public Page<VpiMetadata> search(
            LocalDateTime from,
            LocalDateTime to,
            String opco,
            VpiFiltersRequest filters,
            Pageable pageable) {

        logger.debug("Searching recordings for OPCO: {} from {} to {}", opco, from, to);

        validateOpco(opco);

        return switch (opco.toUpperCase()) {
            case "CMP" -> searchCmp(from, to, filters, pageable);
            case "NYSEG" -> searchNyseg(from, to, filters, pageable);
            case "RGE" -> searchRge(from, to, filters, pageable);
            default -> throw new InvalidRequestException("Invalid OPCO code: " + opco);
        };
    }

    // ========== Private Validation Methods ==========

    private void validateSearchRequest(VpiSearchRequest request) {
        if (request == null) {
            throw new InvalidRequestException("Search request cannot be null");
        }
        if (!StringUtils.hasText(request.getFrom_date())) {
            throw new InvalidRequestException("From date is required");
        }
        if (!StringUtils.hasText(request.getTo_date())) {
            throw new InvalidRequestException("To date is required");
        }
        if (!StringUtils.hasText(request.getOpco())) {
            throw new InvalidRequestException("OPCO is required");
        }
        validateOpco(request.getOpco());

    }

    private void validateRequest(RecordingRequest req) {
        if (req == null) {
            throw new InvalidRequestException("Request cannot be null");
        }
        
        if (!StringUtils.hasText(req.getOpco())) {
            throw new InvalidRequestException("OPCO is required");
        }
        if (!StringUtils.hasText(req.getDate())) {
            throw new InvalidRequestException("Date is required");
        }
        validateOpco(req.getOpco());
    }

    private void validateOpco(String opco) {
        assertRepoEnabled(opco);
        if (!StringUtils.hasText(opco)) {
            throw new InvalidRequestException("OPCO cannot be empty");
        }
        if (!ALLOWED_OPCOS.contains(opco.trim().toUpperCase())) {
            throw new InvalidRequestException(
                    String.format("Invalid OPCO '%s'. Allowed values: %s",
                            opco, String.join(", ", ALLOWED_OPCOS)));
        }
    }

    // ========== Private Utility Methods ==========

    private LocalDateTime parseDateTime(String dateStr) {
        try {
            return LocalDateTime.parse(dateStr, DATE_TIME_FORMATTER);
        } catch (Exception e) {
            logger.error("Invalid date format: {}", dateStr, e);
            throw new InvalidRequestException(
                    String.format("Invalid date format '%s'. Expected format: yyyy-MM-dd HH:mm:ss", dateStr), e);
        }
    }

    private Pageable createPageable(PaginationRequest pagination) {
        int pageNumber = pagination != null ? pagination.getPageNumber() : MIN_PAGE_NUMBER;
        int requestedPageSize = pagination != null ? pagination.getPageSize() : DEFAULT_PAGE_SIZE;

        int pageSize = requestedPageSize > 0 ? requestedPageSize : DEFAULT_PAGE_SIZE;
        int safePage = Math.max(pageNumber - 1, 0);

        return PageRequest.of(safePage, pageSize, Sort.by("dateAdded").descending());
    }

    private VpiSearchResponse buildSearchResponse(Page<VpiMetadata> pageResult) {
        VpiSearchResponse response = new VpiSearchResponse();
        PaginationResponse pageResponse = new PaginationResponse();

        response.setData(pageResult.getContent());
        response.setMessage(MESSAGE_SUCCESS);
        response.setStatus(STATUS_SUCCESS);

        pageResponse.setPageNumber(pageResult.getNumber() + 1);
        pageResponse.setPageSize(pageResult.getSize());
        pageResponse.setTotalRecords(pageResult.getTotalElements());
        pageResponse.setTotalPages(pageResult.getTotalPages());
        response.setPagination(pageResponse);

        return response;
    }

    private String findRecordingBlob(String opco, LocalDateTime fileDate, String filename) {
        String prefix = buildDayPrefix(opco, fileDate.toLocalDate());
        String normalizedCustomer = normalize(filename);

        String blobName = findMatchingBlob(prefix, fileDate, normalizedCustomer);

        if (!StringUtils.hasText(blobName)) {
            throw new RecordingNotFoundException(String.format(
                    "Recording not found with OPCO='%s' and filename='%s' for date '%s'",
                    opco, filename, fileDate));
        }

        return blobName;
    }

    private String findMatchingBlob(String prefix, LocalDateTime expectedDateTime, String normalizedCustomer) {
        List<String> blobs = vpiAzureRepository.listBlobs(prefix);
        String stack = "";
        for (String blobName : blobs) {
            if (!blobName.endsWith(WAV_EXTENSION)) {
                continue;
            }
            if (!matchesTimestamp(blobName, expectedDateTime)) {
                continue;
            }
            if (matchesCustomer(blobName, normalizedCustomer)) {
                logger.debug("Matching recording found: {}", blobName);
                return blobName;
            }
            else{
                stack = blobName;
            }
        }

        if (!stack.isEmpty()) {
            return stack;
        }
        logger.warn("No matching blob found for prefix: {} and customer: {}", prefix, normalizedCustomer);
        return "";
    }

    private boolean matchesTimestamp(String blobName, LocalDateTime expected) {
        try {
            LocalDateTime extracted = extractDateTime(blobName);
            return extracted.equals(expected);
        } catch (RuntimeException ex) {
            logger.debug("Skipping invalid filename format: {}", blobName);
            return false;
        }
    }

    private boolean matchesCustomer(String blobName, String normalizedCustomer) {
        String actualCustomer = normalize(extractCustomerName(blobName));
        return actualCustomer.equals(normalizedCustomer);
    }

    private LocalDateTime extractDateTime(String blobName) {
        String fileName = extractFileName(blobName);

        if (fileName.length() < FILENAME_TIME_END) {
            throw new IllegalArgumentException("Filename too short: " + fileName);
        }

        String date = fileName.substring(FILENAME_DATE_START, FILENAME_DATE_END);
        String time = fileName.substring(FILENAME_TIME_START, FILENAME_TIME_END).replace('-', ':');

        return LocalDateTime.parse(date + "T" + time);
    }

    private String extractCustomerName(String blobName) {
        String fileName = extractFileName(blobName);
        int endIndex = fileName.indexOf(WAV_EXTENSION);

        if (endIndex == -1 || fileName.length() < FILENAME_CUSTOMER_START) {
            throw new IllegalArgumentException("Invalid filename format: " + fileName);
        }

        return fileName.substring(FILENAME_CUSTOMER_START, endIndex);
    }

    private String extractFileName(String blobName) {
        return blobName.substring(blobName.lastIndexOf('/') + 1);
    }

    private String buildDayPrefix(String opco, LocalDate date) {
        return String.format("%s/%d/%d/%d/",
                opco.toUpperCase(Locale.ROOT),
                date.getYear(),
                date.getMonthValue(),
                date.getDayOfMonth());
    }

    private String normalize(String value) {
        if (value == null) {
            return "";
        }
        return value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    private byte[] downloadBlob(String blobName) {
        try {
            return vpiAzureRepository.getBlobContent(blobName);
        } catch (Exception e) {
            logger.error("Failed to download blob: {}", blobName, e);
            throw new RecordingProcessingException("Failed to download recording: " + e.getMessage(), e);
        }
    }

    private byte[] convertWavToMp3(byte[] wavData) {
        Path tempDir = null;
        Path inputFile = null;
        Path outputFile = null;

        try {
            tempDir = Files.createTempDirectory(TEMP_DIR_PREFIX);
            String uniqueId = UUID.randomUUID().toString();
            inputFile = tempDir.resolve(INPUT_FILE_PREFIX + uniqueId + WAV_EXTENSION);
            outputFile = tempDir.resolve(OUTPUT_FILE_PREFIX + uniqueId + MP3_EXTENSION);

            Files.write(inputFile, wavData);

            FFmpeg ffmpeg = new FFmpeg(ffmpegPath);
            FFprobe ffprobe = new FFprobe(ffprobePath);

            FFmpegBuilder builder = new FFmpegBuilder()
                    .setInput(inputFile.toString())
                    .overrideOutputFiles(true)
                    .addOutput(outputFile.toString())
                    .setAudioCodec(AUDIO_CODEC)
                    .setAudioBitRate(AUDIO_BITRATE)
                    .setAudioChannels(AUDIO_CHANNELS)
                    .setAudioSampleRate(AUDIO_SAMPLE_RATE)
                    .done();

            FFmpegExecutor executor = new FFmpegExecutor(ffmpeg, ffprobe);
            executor.createJob(builder).run();

            return Files.readAllBytes(outputFile);

        } catch (IOException e) {
            logger.error("IO error during WAV to MP3 conversion", e);
            throw new RecordingProcessingException("Failed to convert audio file: " + e.getMessage(), e);
        } catch (Exception e) {
            logger.error("Unexpected error during WAV to MP3 conversion", e);
            throw new RecordingProcessingException("Audio conversion failed: " + e.getMessage(),e);
        } finally {
            cleanupTempFiles(inputFile, outputFile, tempDir);
        }
    }

    private void cleanupTempFiles(Path... paths) {
        for (Path path : paths) {
            if (path != null) {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException e) {
                    logger.warn("Failed to delete temporary file: {}", path, e);
                }
            }
        }
    }

    private ResponseEntity<ByteArrayResource> buildAudioResponse(byte[] mp3Data, String originalFilename) {
        String mp3Filename = originalFilename.replace(WAV_EXTENSION, MP3_EXTENSION);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType("audio/mpeg"));
        headers.setContentDispositionFormData("inline", mp3Filename);
        headers.setContentLength(mp3Data.length);

        ByteArrayResource resource = new ByteArrayResource(mp3Data);

        return ResponseEntity.ok()
                .headers(headers)
                .body(resource);
    }

    private void addRecordingToZip(RecordingRequest req, ZipOutputStream zos) throws IOException {
        validateRequest(req);

        LocalDateTime fileDate = parseDateTime(req.getDate());
        String prefix = buildDayPrefix(req.getOpco(), fileDate.toLocalDate());
        String normalizedCustomer = normalize(req.getUsername());

        String blobName = findMatchingBlob(prefix, fileDate, normalizedCustomer);

        if (!blobName.endsWith(WAV_EXTENSION)) {
            logger.warn("Skipping non-WAV file: {}", blobName);
            return;
        }

        String zipEntryName = Paths.get(req.getUsername() + WAV_EXTENSION).getFileName().toString();
        zos.putNextEntry(new ZipEntry(zipEntryName));

        try (InputStream blobStream = vpiAzureRepository.getBlobStream(blobName)) {
            StreamUtils.copy(blobStream, zos);
        } catch (Exception e) {
            logger.error("Failed to add recording to ZIP: {}", blobName, e);
            throw new IOException("Failed to add recording: " + blobName, e);
        }

        zos.closeEntry();
    }

    private ResponseEntity<byte[]> buildZipResponse(byte[] zipData) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
        headers.set(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"recordings.zip\"");
        headers.setContentLength(zipData.length);

        return new ResponseEntity<>(zipData, headers, HttpStatus.OK);
    }

    // ========== Search Methods ==========

    private Page<VpiMetadata> searchCmp(
            LocalDateTime from, LocalDateTime to, VpiFiltersRequest filters, Pageable pageable) {
        Specification<VpiCaptureCmp> spec = CaptureSpecifications.build(from, to, filters);
        return mapToMetadata(cmpRepo.findAll(spec, pageable));
    }

    private Page<VpiMetadata> searchNyseg(
            LocalDateTime from, LocalDateTime to, VpiFiltersRequest filters, Pageable pageable) {
        Specification<VpiCaptureNyseg> spec = CaptureSpecifications.build(from, to, filters);
        return mapToMetadata(nysegRepo.findAll(spec, pageable));
    }

    private Page<VpiMetadata> searchRge(
            LocalDateTime from, LocalDateTime to, VpiFiltersRequest filters, Pageable pageable) {
        Specification<VpiCaptureRge> spec = CaptureSpecifications.build(from, to, filters);
        return mapToMetadata(rgeRepo.findAll(spec, pageable));
    }

    // ========== Mapping Methods ==========

    private Page<VpiMetadata> mapToMetadata(Page<? extends VpiCaptureView> page) {
        return page.map(this::convertToMetadata);
    }

    private VpiMetadata convertToMetadata(VpiCaptureView rec) {
        VpiMetadata dto = new VpiMetadata();
        dto.setObjectId(rec.getObjectId());
        dto.setDateAdded(rec.getDateAdded());
        dto.setStartTime(rec.getStartTime());
        dto.setDuration(rec.getDuration());
        dto.setTags(rec.getTags());
        dto.setChannelName(rec.getChannelName());
        dto.setCallId(rec.getCallId());
        dto.setUserId(rec.getUserId());
        dto.setUserName(rec.getUserName());
        dto.setDirection(rec.getDirection());
        return dto;
    }

    private List<Map<String, Object>> metadataFull(List<? extends VpiCaptureView> recordings) {
        return recordings.stream()
                .map(this::buildMetadataMap)
                .toList();
    }

    private Map<String, Object> buildMetadataMap(VpiCaptureView rec) {
        Map<String, Object> map = new LinkedHashMap<>();

        // Identifiers & Timestamps
        map.put("objectId", rec.getObjectId());
        map.put("dateAdded", rec.getDateAdded());
        map.put("resourceId", rec.getResourceId());
        map.put("workstationId", rec.getWorkstationId());
        map.put("userId", rec.getUserId());

        // Timing
        map.put("startTime", rec.getStartTime());
        map.put("gmtOffset", rec.getGmtOffset());
        map.put("gmtStartTime", rec.getGmtStartTime());
        map.put("duration", rec.getDuration());

        // Triggers & Tags
        map.put("triggeredByResourceTypeId", rec.getTriggeredByResourceTypeId());
        map.put("triggeredByObjectId", rec.getTriggeredByObjectId());
        map.put("flagId", rec.getFlagId());
        map.put("tags", rec.getTags());
        map.put("sensitivityLevel", rec.getSensitivityLevel());
        map.put("clientId", rec.getClientId());

        // Channel & Agent Info
        map.put("channelNum", rec.getChannelNum());
        map.put("channelName", rec.getChannelName());
        map.put("extensionNum", rec.getExtensionNum());
        map.put("agentId", rec.getAgentId());
        map.put("pbxDnis", rec.getPbxDnis());
        map.put("anialidigits", rec.getAnialidigits());
        map.put("direction", rec.getDirection());

        // Media Info
        map.put("mediaFileId", rec.getMediaFileId());
        map.put("mediaManagerId", rec.getMediaManagerId());
        map.put("mediaRetention", rec.getMediaRetention());

        // Call IDs
        map.put("callId", rec.getCallId());
        map.put("previousCallId", rec.getPreviousCallId());
        map.put("globalCallId", rec.getGlobalCallId());

        // Service & Platform
        map.put("classOfService", rec.getClassOfService());
        map.put("classOfServiceDate", rec.getClassOfServiceDate());
        map.put("xPlatformRef", rec.getXPlatformRef());

        // Transcription & Audio
        map.put("transcriptResult", rec.getTranscriptResult());
        map.put("warehouseObjectKey", rec.getWarehouseObjectKey());
        map.put("transcriptStatus", rec.getTranscriptStatus());
        map.put("audioChannels", rec.getAudioChannels());
        map.put("hasTalkover", rec.getHasTalkover());

        return map;
    }

}
