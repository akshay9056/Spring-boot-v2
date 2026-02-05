package com.avangrid.gui.avangrid_backend.service;

import com.avangrid.gui.avangrid_backend.exception.InvalidRequestException;
import com.avangrid.gui.avangrid_backend.exception.RecordingNotFoundException;
import com.avangrid.gui.avangrid_backend.exception.RecordingProcessingException;
import com.avangrid.gui.avangrid_backend.infra.cmp.entity.VpiCaptureCmp;
import com.avangrid.gui.avangrid_backend.infra.cmp.entity.VpiUsersCmp;
import com.avangrid.gui.avangrid_backend.infra.cmp.repository.VpiCmpRepo;
import com.avangrid.gui.avangrid_backend.infra.cmp.repository.VpiCmpUserRepo;
import com.avangrid.gui.avangrid_backend.infra.nyseg.entity.VpiCaptureNyseg;
import com.avangrid.gui.avangrid_backend.infra.nyseg.entity.VpiUsersNyseg;
import com.avangrid.gui.avangrid_backend.infra.nyseg.repository.VpiNysegRepo;
import com.avangrid.gui.avangrid_backend.infra.nyseg.repository.VpiNysegUserRepo;
import com.avangrid.gui.avangrid_backend.infra.rge.entity.VpiCaptureRge;
import com.avangrid.gui.avangrid_backend.infra.rge.entity.VpiUsersRge;
import com.avangrid.gui.avangrid_backend.infra.rge.repository.VpiRgeRepo;
import com.avangrid.gui.avangrid_backend.infra.rge.repository.VpiRgeUserRepo;
import com.avangrid.gui.avangrid_backend.model.*;
import com.avangrid.gui.avangrid_backend.infra.azure.AzureBlobRepository;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
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
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

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
    private static final Set<String> ALLOWED_OPCOS = Set.of("RGE", "CMP", "NYSEG");

    // File naming constants
    private static final String WAV_EXTENSION = ".wav";
    private static final String MP3_EXTENSION = ".mp3";
    private static final int FILENAME_DATE_START = 5;
    private static final int FILENAME_DATE_END = 15;
    private static final int FILENAME_TIME_START = 16;
    private static final int FILENAME_TIME_END = 24;
    private static final int FILENAME_CUSTOMER_START = 24;

    // FFmpeg constants
    private static final String[] FFMPEG_COMMAND = {
            "ffmpeg",
            "-hide_banner",
            "-loglevel", "warning",
            "-i", "pipe:0",
            "-vn",
            "-acodec", "libmp3lame",
            "-ab", "128k",
            "-ac", "2",
            "-ar", "44100",
            "-f", "mp3",
            "pipe:1"
    };
    private static final int CONVERSION_TIMEOUT_SECONDS = 60;
    private static final int BUFFER_SIZE = 8192;

    // Recording status constants
    private static final String STATUS_NOT_FOUND = "NOT_FOUND";
    private static final String STATUS_ERROR = "ERROR";
    private static final String STATUS_RECORDING_SUCCESS = "SUCCESS";

    // Dependencies
    private final AzureBlobRepository vpiAzureRepository;
    private final VpiCmpRepo cmpRepo;
    private final VpiNysegRepo nysegRepo;
    private final VpiRgeRepo rgeRepo;
    private final VpiRgeUserRepo rgeUserRepo;
    private final VpiNysegUserRepo nysegUserRepo;
    private final VpiCmpUserRepo cmpUserRepo;

    public VpiRecordingService(
            AzureBlobRepository vpiAzureRepository,
            @Autowired(required = false) VpiCmpRepo cmpRepo,
            @Autowired(required = false) VpiNysegRepo nysegRepo,
            @Autowired(required = false) VpiRgeRepo rgeRepo,
            @Autowired(required = false) VpiCmpUserRepo cmpUserRepo,
            @Autowired(required = false) VpiNysegUserRepo nysegUserRepo,
            @Autowired(required = false) VpiRgeUserRepo rgeUserRepo) {
        this.vpiAzureRepository = vpiAzureRepository;
        this.cmpRepo = cmpRepo;
        this.nysegRepo = nysegRepo;
        this.rgeRepo = rgeRepo;
        this.rgeUserRepo = rgeUserRepo;
        this.nysegUserRepo = nysegUserRepo;
        this.cmpUserRepo = cmpUserRepo;
    }

    // ========== Public API Methods ==========

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

        OffsetDateTime from = parseDateTime(request.getFrom_date()).atOffset(ZoneOffset.UTC);
        OffsetDateTime to = parseDateTime(request.getTo_date()).atOffset(ZoneOffset.UTC);

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
     * @param id   Unique identifier of the recording
     * @param opco Operating company code
     * @return Map containing all metadata fields
     * @throws InvalidRequestException        if OPCO is invalid
     * @throws RecordingNotFoundException if recording not found
     */
    public Map<String, Object> getMetadata(UUID id, String opco) {
        logger.debug("Fetching metadata for id: {} and opco: {}", id, opco);

        validateOpco(opco);

        List<Map<String, Object>> metadata = getMetadataByOpco(id, opco);

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
     * @throws InvalidRequestException           if request parameters are invalid
     * @throws RecordingNotFoundException        if recording is not found
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
        if (requests.isEmpty()) {
            throw new InvalidRequestException("Request list cannot be empty");
        }

        List<RecordingStatus> statuses = new ArrayList<>();
        int successCount = 0;
        int failureCount = 0;

        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             ZipOutputStream zos = new ZipOutputStream(baos)) {

            for (RecordingRequest req : requests) {
                RecordingStatus status = addRecordingToZip(req, zos);
                statuses.add(status);

                if (STATUS_RECORDING_SUCCESS.equals(status.getStatus())) {
                    successCount++;
                } else {
                    failureCount++;
                }
            }

            if (successCount == 0) {
                return ResponseEntity.noContent().build();
            }

            ZipStatusSummary summary = new ZipStatusSummary(
                    requests.size(),
                    successCount,
                    failureCount,
                    statuses
            );
            addStatusFileToZip(summary, zos);

            zos.finish();
            return buildZipResponse(baos.toByteArray());

        } catch (IOException e) {
            throw new RecordingProcessingException("Failed to create ZIP", e);
        }
    }

    /**
     * Searches for recordings based on criteria.
     *
     * @param from     Start date/time
     * @param to       End date/time
     * @param opco     Operating company
     * @param filters  Additional search filters
     * @param pageable Pagination information
     * @return Page of VpiMetadata results
     */
    public Page<VpiMetadata> search(
            OffsetDateTime from,
            OffsetDateTime to,
            String opco,
            VpiFiltersRequest filters,
            Pageable pageable) {

        logger.debug("Searching recordings for OPCO: {} from {} to {}", opco, from, to);

        validateOpco(opco);

        List<String> cleanedNames = cleanNames(filters != null ? filters.getName() : null);
        Set<UUID> matchedUserIds = Collections.emptySet();

        if (!cleanedNames.isEmpty()) {
            matchedUserIds = fetchMatchedUserIds(opco, cleanedNames);

            if (matchedUserIds.isEmpty()) {
                return Page.empty(pageable);
            }
        }

        return performSearch(from, to, opco, filters, matchedUserIds, pageable);
    }

    /**
     * Converts WAV audio data to MP3 format using FFmpeg.
     *
     * @param wavData Raw WAV audio bytes
     * @return MP3 encoded audio bytes
     * @throws RecordingProcessingException if conversion fails
     */
    public byte[] convertWavToMp3(byte[] wavData) {
        if (wavData == null || wavData.length == 0) {
            throw new InvalidRequestException("WAV data is empty");
        }

        Process process = null;
        try {
            process = startFfmpegProcess();
            final Process proc = process;

            CompletableFuture<String> errorReader = readErrorStream(proc);
            CompletableFuture<Void> writer = writeInputData(proc, wavData);
            CompletableFuture<byte[]> reader = readOutputData(proc);

            CompletableFuture.allOf(writer, reader, errorReader)
                    .get(CONVERSION_TIMEOUT_SECONDS, TimeUnit.SECONDS);

            byte[] mp3Data = reader.get();
            String errors = errorReader.get();

            int exitCode = process.waitFor();

            validateConversionResult(exitCode, errors, wavData.length, mp3Data.length);

            return mp3Data;

        } catch (TimeoutException e) {
            destroyProcess(process);
            throw new RecordingProcessingException("Conversion timed out after " + CONVERSION_TIMEOUT_SECONDS + " seconds", e);
        } catch (ExecutionException e) {
            throw new RecordingProcessingException("Conversion failed", e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            destroyProcess(process);
            throw new RecordingProcessingException("Conversion interrupted", e);
        } catch (IOException e) {
            throw new RecordingProcessingException("Failed to start FFmpeg process", e);
        } finally {
            destroyProcess(process);
        }
    }

    // ========== Validation Methods ==========

    private void validateSearchRequest(VpiSearchRequest request) {
        if (request == null) {
            throw new InvalidRequestException("Search request cannot be null");
        }
        validateRequiredField(request.getFrom_date(), "From date");
        validateRequiredField(request.getTo_date(), "To date");
        validateRequiredField(request.getOpco(), "OPCO");
        validateOpco(request.getOpco());
    }

    private void validateRequest(RecordingRequest req) {
        if (req == null) {
            throw new InvalidRequestException("Request cannot be null");
        }
        validateRequiredField(req.getOpco(), "OPCO");
        validateRequiredField(req.getDate(), "Date");
        validateOpco(req.getOpco());
    }

    private void validateOpco(String opco) {
        assertRepoEnabled(opco);
        validateRequiredField(opco, "OPCO");

        if (!ALLOWED_OPCOS.contains(opco.trim().toUpperCase())) {
            throw new InvalidRequestException(
                    String.format("Invalid OPCO '%s'. Allowed values: %s",
                            opco, String.join(", ", ALLOWED_OPCOS)));
        }
    }

    private void validateRequiredField(String value, String fieldName) {
        if (!StringUtils.hasText(value)) {
            throw new InvalidRequestException(fieldName + " is required");
        }
    }

    private void assertRepoEnabled(String opco) {
        String upperOpco = opco.toUpperCase();

        if ("CMP".equals(upperOpco) && cmpRepo == null) {
            throw new InvalidRequestException("CMP datasource is disabled");
        }
        if ("NYSEG".equals(upperOpco) && nysegRepo == null) {
            throw new InvalidRequestException("NYSEG datasource is disabled");
        }
        if ("RGE".equals(upperOpco) && rgeRepo == null) {
            throw new InvalidRequestException("RGE datasource is disabled");
        }
    }

    // ========== Utility Methods ==========

    private LocalDateTime parseDateTime(String dateStr) {
        try {
            return LocalDateTime.parse(dateStr, DATE_TIME_FORMATTER);
        } catch (Exception e) {
            throw new InvalidRequestException(
                    String.format("Invalid date format '%s'. Expected format: yyyy-MM-dd HH:mm:ss", dateStr), e);
        }
    }

    public static String toRequiredFormat(OffsetDateTime dateTime) {
        if (dateTime == null) {
            return null;
        }

        return dateTime
                .atZoneSameInstant(ZoneId.systemDefault())
                .format(DATE_TIME_FORMATTER);
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

    private List<String> cleanNames(List<String> names) {
        if (names == null) {
            return Collections.emptyList();
        }

        return names.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(name -> !name.isEmpty())
                .toList();
    }

    private String normalize(String value) {
        if (value == null) {
            return "";
        }
        return value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    // ========== Blob Management Methods ==========

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
        String fallbackBlob = "";

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
            fallbackBlob = blobName;
        }

        if (!fallbackBlob.isEmpty()) {
            return fallbackBlob;
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

    private String buildDayPrefix(String opco, LocalDate date) {
        return String.format("%s/%d/%d/%d/",
                opco.toUpperCase(Locale.ROOT),
                date.getYear(),
                date.getMonthValue(),
                date.getDayOfMonth());
    }

    private byte[] downloadBlob(String blobName) {
        try {
            return vpiAzureRepository.getBlobContent(blobName);
        } catch (Exception e) {
            throw new RecordingProcessingException("Failed to download recording: " + e.getMessage(), e);
        }
    }

    // ========== Filename Parsing Methods ==========

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

    // ========== FFmpeg Conversion Helper Methods ==========

    private Process startFfmpegProcess() throws IOException {
        ProcessBuilder pb = new ProcessBuilder(FFMPEG_COMMAND);
        return pb.start();
    }

    private CompletableFuture<String> readErrorStream(Process process) {
        return CompletableFuture.supplyAsync(() -> {
            try (InputStream stderr = process.getErrorStream();
                 BufferedReader reader = new BufferedReader(new InputStreamReader(stderr, StandardCharsets.UTF_8))) {
                return reader.lines().collect(Collectors.joining("\n"));
            } catch (IOException e) {
                return "Failed to read error stream: " + e.getMessage();
            }
        });
    }

    private CompletableFuture<Void> writeInputData(Process process, byte[] wavData) {
        return CompletableFuture.runAsync(() -> {
            try (OutputStream stdin = process.getOutputStream()) {
                stdin.write(wavData);
                stdin.flush();
            } catch (IOException e) {
                throw new UncheckedIOException("Write failed", e);
            }
        });
    }

    private CompletableFuture<byte[]> readOutputData(Process process) {
        return CompletableFuture.supplyAsync(() -> {
            try (InputStream stdout = process.getInputStream();
                 ByteArrayOutputStream output = new ByteArrayOutputStream()) {

                byte[] buffer = new byte[BUFFER_SIZE];
                int bytesRead;
                while ((bytesRead = stdout.read(buffer)) != -1) {
                    output.write(buffer, 0, bytesRead);
                }
                return output.toByteArray();

            } catch (IOException e) {
                throw new UncheckedIOException("Read failed", e);
            }
        });
    }

    private void validateConversionResult(int exitCode, String errors, int inputSize, int outputSize) {
        if (exitCode != 0) {
            logger.error("FFmpeg conversion failed. Exit code: {}, Errors: {}", exitCode, errors);
            throw new RecordingProcessingException(
                    String.format("FFmpeg failed (exit %d): %s", exitCode, errors));
        }

        if (!errors.isEmpty()) {
            logger.warn("FFmpeg warnings: {}", errors);
        }

        logger.info("Conversion successful: {} bytes WAV -> {} bytes MP3", inputSize, outputSize);
    }

    private void destroyProcess(Process process) {
        if (process != null && process.isAlive()) {
            process.destroyForcibly();
        }
    }

    // ========== Response Building Methods ==========

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

    private ResponseEntity<byte[]> buildZipResponse(byte[] zipData) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
        headers.set(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"recordings.zip\"");
        headers.setContentLength(zipData.length);

        return new ResponseEntity<>(zipData, headers, HttpStatus.OK);
    }

    // ========== ZIP Creation Methods ==========

    private RecordingStatus addRecordingToZip(RecordingRequest req, ZipOutputStream zos) throws IOException {
        validateRequest(req);

        LocalDateTime fileDate = parseDateTime(req.getDate());
        String prefix = buildDayPrefix(req.getOpco(), fileDate.toLocalDate());
        String normalizedCustomer = normalize(req.getUsername());

        String blobName = findMatchingBlob(prefix, fileDate, normalizedCustomer);
        if (blobName.isEmpty()) {
            logger.warn("No matching blob found for user={} date={}", req.getUsername(), req.getDate());
            return createNotFoundStatus(req);
        }

        String zipEntryName = buildZipEntryName(fileDate, req.getUsername());

        try {
            addBlobToZip(blobName, zipEntryName, zos);
            return createSuccessStatus(req, zipEntryName);
        } catch (Exception e) {
            logger.error("Failed to add recording to ZIP: {}", blobName, e);
            return createErrorStatus(req, zipEntryName, e.getMessage());
        }
    }

    private RecordingStatus createNotFoundStatus(RecordingRequest req) {
        return new RecordingStatus(
                req.getUsername(),
                req.getDate(),
                null,
                STATUS_NOT_FOUND,
                "No matching audio file found"
        );
    }

    private RecordingStatus createSuccessStatus(RecordingRequest req, String zipEntryName) {
        return new RecordingStatus(
                req.getUsername(),
                req.getDate(),
                zipEntryName,
                STATUS_RECORDING_SUCCESS,
                null
        );
    }

    private RecordingStatus createErrorStatus(RecordingRequest req, String zipEntryName, String errorMessage) {
        return new RecordingStatus(
                req.getUsername(),
                req.getDate(),
                zipEntryName,
                STATUS_ERROR,
                errorMessage
        );
    }

    private String buildZipEntryName(LocalDateTime fileDate, String username) {
        String formattedDate = fileDate.toLocalDate().toString();
        return formattedDate + "_" + username + WAV_EXTENSION;
    }

    private void addBlobToZip(String blobName, String zipEntryName, ZipOutputStream zos) throws IOException {
        zos.putNextEntry(new ZipEntry(zipEntryName));
        try (InputStream blobStream = vpiAzureRepository.getBlobStream(blobName)) {
            StreamUtils.copy(blobStream, zos);
        } finally {
            zos.closeEntry();
        }
    }

    private void addStatusFileToZip(ZipStatusSummary summary, ZipOutputStream zos) throws IOException {
        ZipEntry entry = new ZipEntry("status.json");
        zos.putNextEntry(entry);

        ObjectMapper mapper = new ObjectMapper();
        String json = mapper
                .writerWithDefaultPrettyPrinter()
                .writeValueAsString(summary);

        zos.write(json.getBytes(StandardCharsets.UTF_8));
        zos.closeEntry();
    }

    // ========== Search Methods ==========

    private Page<VpiMetadata> performSearch(
            OffsetDateTime from,
            OffsetDateTime to,
            String opco,
            VpiFiltersRequest filters,
            Set<UUID> userIds,
            Pageable pageable) {

        String upperOpco = opco.toUpperCase();

        return switch (upperOpco) {
            case "CMP" -> searchCmp(from, to, filters, userIds, pageable);
            case "NYSEG" -> searchNyseg(from, to, filters, userIds, pageable);
            case "RGE" -> searchRge(from, to, filters, userIds, pageable);
            default -> throw new InvalidRequestException("Invalid OPCO code: " + opco);
        };
    }

    private Page<VpiMetadata> searchCmp(
            OffsetDateTime from,
            OffsetDateTime to,
            VpiFiltersRequest filters,
            Set<UUID> userIds,
            Pageable pageable) {

        Specification<VpiCaptureCmp> spec = CaptureSpecifications.build(from, to, filters, userIds);
        Page<VpiCaptureCmp> page = cmpRepo.findAll(spec, pageable);
        return enrichAndMap(page, "CMP");
    }

    private Page<VpiMetadata> searchNyseg(
            OffsetDateTime from,
            OffsetDateTime to,
            VpiFiltersRequest filters,
            Set<UUID> userIds,
            Pageable pageable) {

        Specification<VpiCaptureNyseg> spec = CaptureSpecifications.build(from, to, filters, userIds);
        Page<VpiCaptureNyseg> page = nysegRepo.findAll(spec, pageable);
        return enrichAndMap(page, "NYSEG");
    }

    private Page<VpiMetadata> searchRge(
            OffsetDateTime from,
            OffsetDateTime to,
            VpiFiltersRequest filters,
            Set<UUID> userIds,
            Pageable pageable) {

        Specification<VpiCaptureRge> spec = CaptureSpecifications.build(from, to, filters, userIds);
        Page<VpiCaptureRge> page = rgeRepo.findAll(spec, pageable);
        return enrichAndMap(page, "RGE");
    }

    // ========== User Management Methods ==========

    private Set<UUID> fetchMatchedUserIds(String opco, List<String> names) {
        if (names == null || names.isEmpty()) {
            return Collections.emptySet();
        }

        String upperOpco = opco.toUpperCase();
        String[] namesArray = names.toArray(new String[0]);

        return switch (upperOpco) {
            case "CMP" -> new HashSet<>(cmpUserRepo.findUserIdsByFullNameContainsAny(namesArray));
            case "NYSEG" -> new HashSet<>(nysegUserRepo.findUserIdsByFullNameContainsAny(namesArray));
            case "RGE" -> new HashSet<>(rgeUserRepo.findUserIdsByFullNameContainsAny(namesArray));
            default -> Collections.emptySet();
        };
    }

    private Map<UUID, String> fetchUserNames(String opco, Set<UUID> userIds) {
        if (userIds.isEmpty()) {
            return Collections.emptyMap();
        }

        String upperOpco = opco.toUpperCase();

        return switch (upperOpco) {
            case "CMP" -> buildUserNameMap(cmpUserRepo.findByUserIdIn(userIds), VpiUsersCmp::getUserId, VpiUsersCmp::getFullName);
            case "NYSEG" -> buildUserNameMap(nysegUserRepo.findByUserIdIn(userIds), VpiUsersNyseg::getUserId, VpiUsersNyseg::getFullName);
            case "RGE" -> buildUserNameMap(rgeUserRepo.findByUserIdIn(userIds), VpiUsersRge::getUserId, VpiUsersRge::getFullName);
            default -> Collections.emptyMap();
        };
    }

    private <T> Map<UUID, String> buildUserNameMap(
            List<T> users,
            java.util.function.Function<T, UUID> idExtractor,
            java.util.function.Function<T, String> nameExtractor) {
        return users.stream()
                .collect(Collectors.toMap(idExtractor, nameExtractor));
    }

    // ========== Metadata Methods ==========

    private List<Map<String, Object>> getMetadataByOpco(UUID id, String opco) {
        String upperOpco = opco.toUpperCase();

        return switch (upperOpco) {
            case "CMP" -> metadataFull(cmpRepo.findByObjectId(id));
            case "NYSEG" -> metadataFull(nysegRepo.findByObjectId(id));
            case "RGE" -> metadataFull(rgeRepo.findByObjectId(id));
            default -> throw new InvalidRequestException("Invalid OPCO code: " + opco);
        };
    }

    private List<Map<String, Object>> metadataFull(List<? extends VpiCaptureView> recordings) {
        return recordings.stream()
                .map(this::buildMetadataMap)
                .toList();
    }

    private Map<String, Object> buildMetadataMap(VpiCaptureView rec) {
        Map<String, Object> map = new LinkedHashMap<>();

        addIdentifierFields(map, rec);
        addTimingFields(map, rec);
        addTriggerAndTagFields(map, rec);
        addChannelAndAgentFields(map, rec);
        addMediaFields(map, rec);
        addCallIdFields(map, rec);
        addServiceFields(map, rec);
        addTranscriptionFields(map, rec);

        return map;
    }

    private void addIdentifierFields(Map<String, Object> map, VpiCaptureView rec) {
        map.put("objectId", rec.getObjectId());
        map.put("dateAdded", rec.getDateAdded());
        map.put("resourceId", rec.getResourceId());
        map.put("workstationId", rec.getWorkstationId());
        map.put("userId", rec.getUserId());
    }

    private void addTimingFields(Map<String, Object> map, VpiCaptureView rec) {
        map.put("startTime", rec.getStartTime());
        map.put("gmtOffset", rec.getGmtOffset());
        map.put("gmtStartTime", rec.getGmtStartTime());
        map.put("duration", rec.getDuration());
    }

    private void addTriggerAndTagFields(Map<String, Object> map, VpiCaptureView rec) {
        map.put("triggeredByResourceTypeId", rec.getTriggeredByResourceTypeId());
        map.put("triggeredByObjectId", rec.getTriggeredByObjectId());
        map.put("flagId", rec.getFlagId());
        map.put("tags", rec.getTags());
        map.put("sensitivityLevel", rec.getSensitivityLevel());
        map.put("clientId", rec.getClientId());
    }

    private void addChannelAndAgentFields(Map<String, Object> map, VpiCaptureView rec) {
        map.put("channelNum", rec.getChannelNum());
        map.put("channelName", rec.getChannelName());
        map.put("extensionNum", rec.getExtensionNum());
        map.put("agentId", rec.getAgentId());
        map.put("pbxDnis", rec.getPbxDnis());
        map.put("anialidigits", rec.getAnialidigits());
        map.put("direction", rec.getDirection());
    }

    private void addMediaFields(Map<String, Object> map, VpiCaptureView rec) {
        map.put("mediaFileId", rec.getMediaFileId());
        map.put("mediaManagerId", rec.getMediaManagerId());
        map.put("mediaRetention", rec.getMediaRetention());
    }

    private void addCallIdFields(Map<String, Object> map, VpiCaptureView rec) {
        map.put("callId", rec.getCallId());
        map.put("previousCallId", rec.getPreviousCallId());
        map.put("globalCallId", rec.getGlobalCallId());
    }

    private void addServiceFields(Map<String, Object> map, VpiCaptureView rec) {
        map.put("classOfService", rec.getClassOfService());
        map.put("classOfServiceDate", rec.getClassOfServiceDate());
        map.put("xPlatformRef", rec.getXPlatformRef());
    }

    private void addTranscriptionFields(Map<String, Object> map, VpiCaptureView rec) {
        map.put("transcriptResult", rec.getTranscriptResult());
        map.put("warehouseObjectKey", rec.getWarehouseObjectKey());
        map.put("transcriptStatus", rec.getTranscriptStatus());
        map.put("audioChannels", rec.getAudioChannels());
        map.put("hasTalkover", rec.getHasTalkover());
    }

    // ========== Mapping Methods ==========

    private Page<VpiMetadata> enrichAndMap(Page<? extends VpiCaptureView> page, String opco) {
        if (page.isEmpty()) {
            return Page.empty(page.getPageable());
        }

        Set<UUID> userIds = extractUserIds(page);
        Map<UUID, String> userNameMap = fetchUserNames(opco, userIds);

        return page.map(rec -> convertToMetadata(rec, opco, userNameMap));
    }

    private Set<UUID> extractUserIds(Page<? extends VpiCaptureView> page) {
        return page.getContent().stream()
                .map(VpiCaptureView::getUserId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
    }

    private VpiMetadata convertToMetadata(
            VpiCaptureView rec,
            String opco,
            Map<UUID, String> userNameMap) {

        VpiMetadata dto = new VpiMetadata();

        dto.setObjectId(rec.getObjectId());
        dto.setDateAdded(toRequiredFormat(rec.getDateAdded()));
        dto.setStartTime(toRequiredFormat(rec.getStartTime()));
        dto.setDuration(rec.getDuration());
        dto.setTags(rec.getTags());
        dto.setChannelName(rec.getChannelName());
        dto.setCallId(rec.getCallId());
        dto.setUserId(rec.getUserId());
        dto.setAgentId(rec.getAgentId());
        dto.setExtensionNum(rec.getExtensionNum());
        dto.setAniAlidigts(rec.getAnialidigits());
        dto.setUserName(userNameMap.get(rec.getUserId()));
        dto.setDirection(rec.getDirection());
        dto.setOpco(opco);

        return dto;
    }
}
