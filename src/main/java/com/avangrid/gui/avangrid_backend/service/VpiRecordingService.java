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

import org.springframework.beans.factory.annotation.Value;
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
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoField;
import java.time.temporal.TemporalAccessor;
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
 *
 * <p>This service provides comprehensive functionality for:
 * <ul>
 *   <li>Recording retrieval and search across multiple operating companies (OPCO)</li>
 *   <li>Audio format conversion from WAV to MP3 using FFmpeg</li>
 *   <li>Bulk download operations with ZIP packaging</li>
 *   <li>Metadata extraction and management</li>
 * </ul>
 *
 * <p>Supported OPCOs: RGE, CMP, NYSEG
 *
 * @author Avangrid Backend Team
 * @version 1.0
 * @since 2024
 */
@Service
public class VpiRecordingService {

    private static final Logger logger = LoggerFactory.getLogger(VpiRecordingService.class);

    // ========== Constants ==========

    private static final DateTimeFormatter DATE_TIME_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter XML_FORMATTER =
            DateTimeFormatter.ofPattern("M/d/yyyy h:mm:ss a", Locale.US);

    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MIN_PAGE_NUMBER = 1;
    private static final String STATUS_SUCCESS = "200";
    private static final String MESSAGE_SUCCESS = "Success";
    private static final Set<String> ALLOWED_OPCOS = Set.of("RGE", "CMP", "NYSEG");

    private static final String WAV_EXTENSION = ".wav";
    private static final String MP3_EXTENSION = ".mp3";
    private static final int FILENAME_CUSTOMER_START = 24;
    private static final int FILENAME_DATETIME_START = 5;
    private static final int FILENAME_DATETIME_END = 24;

    private static final int CONVERSION_TIMEOUT_SECONDS = 120;
    private static final int BUFFER_SIZE = 8192;

    private static final String STATUS_NOT_FOUND = "NOT_FOUND";
    private static final String STATUS_ERROR = "ERROR";
    private static final String STATUS_RECORDING_SUCCESS = "SUCCESS";

    // ========== Dependencies ==========

    @Value("${ffmpeg.path:ffmpeg}")
    private String ffmpegPath;

    private final AzureBlobRepository vpiAzureRepository;
    private final VpiCmpRepo cmpRepo;
    private final VpiNysegRepo nysegRepo;
    private final VpiRgeRepo rgeRepo;
    private final VpiRgeUserRepo rgeUserRepo;
    private final VpiNysegUserRepo nysegUserRepo;
    private final VpiCmpUserRepo cmpUserRepo;
    private final XmlMediaParser xmlParser;

    /**
     * Constructs a new VpiRecordingService with the required dependencies.
     *
     * @param vpiAzureRepository Azure blob storage repository for recording files
     * @param cmpRepo CMP database repository (optional)
     * @param nysegRepo NYSEG database repository (optional)
     * @param rgeRepo RGE database repository (optional)
     * @param cmpUserRepo CMP user repository (optional)
     * @param nysegUserRepo NYSEG user repository (optional)
     * @param rgeUserRepo RGE user repository (optional)
     * @param xmlParser XML metadata parser
     */
    public VpiRecordingService(
            AzureBlobRepository vpiAzureRepository,
            @Autowired(required = false) VpiCmpRepo cmpRepo,
            @Autowired(required = false) VpiNysegRepo nysegRepo,
            @Autowired(required = false) VpiRgeRepo rgeRepo,
            @Autowired(required = false) VpiCmpUserRepo cmpUserRepo,
            @Autowired(required = false) VpiNysegUserRepo nysegUserRepo,
            @Autowired(required = false) VpiRgeUserRepo rgeUserRepo,
            @Autowired XmlMediaParser xmlParser) {
        this.vpiAzureRepository = vpiAzureRepository;
        this.cmpRepo = cmpRepo;
        this.nysegRepo = nysegRepo;
        this.rgeRepo = rgeRepo;
        this.rgeUserRepo = rgeUserRepo;
        this.nysegUserRepo = nysegUserRepo;
        this.cmpUserRepo = cmpUserRepo;
        this.xmlParser = xmlParser;
    }

    // ========== Public API Methods ==========

    /**
     * Retrieves paginated table data based on search criteria.
     *
     * <p>This method supports:
     * <ul>
     *   <li>Date range filtering</li>
     *   <li>OPCO-specific searches</li>
     *   <li>Custom filters (tags, channels, etc.)</li>
     *   <li>User name filtering</li>
     *   <li>Pagination with configurable page size</li>
     * </ul>
     *
     * @param request Search request containing date range, OPCO, filters, and pagination
     * @return VpiSearchResponse with paginated results and metadata
     * @throws InvalidRequestException if date range or parameters are invalid
     * @throws IllegalArgumentException if end date is before start date
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
     * Retrieves comprehensive metadata for a specific recording.
     *
     * <p>Returns all available metadata fields including:
     * <ul>
     *   <li>Timing information (start time, duration, GMT offset)</li>
     *   <li>Channel and agent details</li>
     *   <li>Call identifiers (call ID, global call ID)</li>
     *   <li>Media file information</li>
     *   <li>Transcription status</li>
     * </ul>
     *
     * @param id Unique identifier (UUID) of the recording
     * @param opco Operating company code (RGE, CMP, or NYSEG)
     * @return Map containing all metadata fields with their values
     * @throws InvalidRequestException if OPCO is invalid or null
     * @throws RecordingNotFoundException if recording with given ID is not found
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
     * <p>Process flow:
     * <ol>
     *   <li>Validates the request parameters</li>
     *   <li>Searches for the recording in Azure blob storage</li>
     *   <li>Downloads the WAV file</li>
     *   <li>Converts to MP3 using FFmpeg</li>
     *   <li>Returns as a streamable response</li>
     * </ol>
     *
     * @param request Recording request with filename, OPCO, date, and optional filters
     * @return ResponseEntity containing MP3 audio data with appropriate headers
     * @throws InvalidRequestException if request parameters are invalid
     * @throws RecordingNotFoundException if recording is not found in storage
     * @throws RecordingProcessingException if download or conversion fails
     */
    public ResponseEntity<ByteArrayResource> getRecordingVpi(RecordingRequest request) {
        logger.info("Retrieving recording for: {}", request.getUsername());

        validateRequest(request);
        RecordingSearchResult blobStatus = findRecordingVPI(request);

        byte[] wavData = downloadBlob(blobStatus.getBlobName());
        byte[] mp3Data = convertWavToMp3(wavData);

        return buildAudioResponse(mp3Data, request.getUsername());
    }

    /**
     * Downloads multiple VPI recordings as a ZIP archive.
     *
     * <p>Features:
     * <ul>
     *   <li>Batch processing of multiple recordings</li>
     *   <li>Individual error handling per recording</li>
     *   <li>Status summary JSON file included in ZIP</li>
     *   <li>Continues processing even if some recordings fail</li>
     * </ul>
     *
     * <p>The ZIP contains:
     * <ul>
     *   <li>Successfully retrieved MP3 files</li>
     *   <li>status.json with detailed processing results</li>
     * </ul>
     *
     * @param requests List of recording requests to download
     * @return ResponseEntity containing ZIP file with all available recordings and status
     * @throws InvalidRequestException if request list is empty
     * @throws RecordingProcessingException if ZIP creation fails
     */
    public ResponseEntity<byte[]> downloadVpi(List<RecordingRequest> requests) {
        if (requests == null || requests.isEmpty()) {
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
                logger.warn("No recordings successfully added to ZIP. Returning no content.");
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
            logger.info("ZIP creation successful: {} successes, {} failures", successCount, failureCount);
            return buildZipResponse(baos.toByteArray());

        } catch (IOException e) {
            throw new RecordingProcessingException("Failed to create ZIP", e);
        }
    }

    /**
     * Searches for recordings based on comprehensive criteria.
     *
     * <p>Search capabilities:
     * <ul>
     *   <li>Date/time range filtering</li>
     *   <li>OPCO-specific searches</li>
     *   <li>User name matching (partial matches supported)</li>
     *   <li>Channel, extension, and tag filtering</li>
     *   <li>Duration and call direction filters</li>
     * </ul>
     *
     * @param from Start date/time (inclusive)
     * @param to End date/time (inclusive)
     * @param opco Operating company code
     * @param filters Additional search filters (nullable)
     * @param pageable Pagination information
     * @return Page of VpiMetadata results matching the criteria
     * @throws InvalidRequestException if OPCO is invalid
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
                logger.debug("No users matched the name filter. Returning empty page.");
                return Page.empty(pageable);
            }
        }

        return performSearch(from, to, opco, filters, matchedUserIds, pageable);
    }

    /**
     * Converts WAV audio data to MP3 format using FFmpeg.
     *
     * <p>Conversion specifications:
     * <ul>
     *   <li>Codec: libmp3lame</li>
     *   <li>Bitrate: 128 kbps</li>
     *   <li>Sample rate: 44100 Hz</li>
     *   <li>Channels: 2 (stereo)</li>
     *   <li>Timeout: 120 seconds</li>
     * </ul>
     *
     * <p>This method uses asynchronous I/O for efficient processing and includes
     * comprehensive error handling for conversion failures.
     *
     * @param wavData Raw WAV audio bytes
     * @return MP3 encoded audio bytes
     * @throws InvalidRequestException if WAV data is null or empty
     * @throws RecordingProcessingException if FFmpeg fails or times out
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
           throw new RecordingProcessingException(
                    "Conversion timed out after " + CONVERSION_TIMEOUT_SECONDS + " seconds", e);
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

    /**
     * Validates a search request for completeness and correctness.
     *
     * @param request The search request to validate
     * @throws InvalidRequestException if any required field is missing or invalid
     */
    private void validateSearchRequest(VpiSearchRequest request) {
        if (request == null) {
            throw new InvalidRequestException("Search request cannot be null");
        }
        validateRequiredField(request.getFrom_date(), "From date");
        validateRequiredField(request.getTo_date(), "To date");
        validateRequiredField(request.getOpco(), "OPCO");
        validateOpco(request.getOpco());
    }

    /**
     * Validates a recording request for completeness.
     *
     * @param req The recording request to validate
     * @throws InvalidRequestException if any required field is missing
     */
    private void validateRequest(RecordingRequest req) {
        if (req == null) {
            throw new InvalidRequestException("Request cannot be null");
        }
        validateRequiredField(req.getOpco(), "OPCO");
        validateRequiredField(req.getDate(), "Date");
        validateOpco(req.getOpco());
    }

    /**
     * Validates an OPCO code against allowed values and checks repository availability.
     *
     * @param opco The OPCO code to validate
     * @throws InvalidRequestException if OPCO is invalid or its datasource is disabled
     */
    private void validateOpco(String opco) {
        assertRepoEnabled(opco);
        validateRequiredField(opco, "OPCO");

        if (!ALLOWED_OPCOS.contains(opco.trim().toUpperCase())) {
            throw new InvalidRequestException(
                    String.format("Invalid OPCO '%s'. Allowed values: %s",
                            opco, String.join(", ", ALLOWED_OPCOS)));
        }
    }

    /**
     * Validates that a required field has a value.
     *
     * @param value The field value to check
     * @param fieldName The name of the field (for error messages)
     * @throws InvalidRequestException if the field is null or empty
     */
    private void validateRequiredField(String value, String fieldName) {
        if (!StringUtils.hasText(value)) {
            throw new InvalidRequestException(fieldName + " is required");
        }
    }

    /**
     * Asserts that the repository for the given OPCO is enabled.
     *
     * @param opco The OPCO code to check
     * @throws InvalidRequestException if the datasource for this OPCO is disabled
     */
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

    // ========== Date/Time Utility Methods ==========

    /**
     * Parses a date-time string in the standard format (yyyy-MM-dd HH:mm:ss).
     *
     * @param dateStr The date string to parse
     * @return LocalDateTime object
     * @throws InvalidRequestException if the format is invalid
     */
    private LocalDateTime parseDateTime(String dateStr) {
        try {
            return LocalDateTime.parse(dateStr, DATE_TIME_FORMATTER);
        } catch (DateTimeParseException e) {
            throw new InvalidRequestException(
                    String.format("Invalid date format '%s'. Expected format: yyyy-MM-dd HH:mm:ss", dateStr), e);
        }
    }

    /**
     * Parses a date-time string in XML format (M/d/yyyy h:mm:ss a).
     *
     * @param dateStr The XML date string to parse
     * @return LocalDateTime object
     * @throws InvalidRequestException if the format is invalid
     */
    private LocalDateTime parseDateTimeXML(String dateStr) {
        try {
            return LocalDateTime.parse(dateStr, XML_FORMATTER);
        } catch (DateTimeParseException e) {
            throw new InvalidRequestException(
                    String.format("Invalid date format '%s'. Expected format: M/d/yyyy h:mm:ss a", dateStr), e);
        }
    }

    /**
     * Converts an OffsetDateTime to XML start time format.
     *
     * @param dateTime The datetime to convert
     * @return Formatted string in XML format, or null if input is null
     */
    public static String toXmlStartTime(OffsetDateTime dateTime) {
        if (dateTime == null) {
            return null;
        }

        OffsetDateTime utc = dateTime.withOffsetSameInstant(ZoneOffset.UTC);
        LocalDateTime local = utc.toLocalDateTime().withNano(0);

        return local.format(XML_FORMATTER);
    }

    /**
     * Converts XML start time format to file timestamp format.
     *
     * <p>Example: "1/15/2024 3:45:30 PM" → "2024-01-15_03-45-30"
     *
     * @param xmlStartTime The XML start time string
     * @return File timestamp string
     * @throws IllegalArgumentException if input is null, empty, or invalid format
     */
    public String xmlStartTimeToFileTimestamp(String xmlStartTime) {
        if (xmlStartTime == null || xmlStartTime.isBlank()) {
            throw new IllegalArgumentException("xmlStartTime cannot be null or empty");
        }

        try {
            TemporalAccessor parsed = XML_FORMATTER.parse(xmlStartTime);

            int year = parsed.get(ChronoField.YEAR);
            int month = parsed.get(ChronoField.MONTH_OF_YEAR);
            int day = parsed.get(ChronoField.DAY_OF_MONTH);
            int hour12 = parsed.get(ChronoField.CLOCK_HOUR_OF_AMPM);
            int minute = parsed.get(ChronoField.MINUTE_OF_HOUR);
            int second = parsed.get(ChronoField.SECOND_OF_MINUTE);

            return String.format("%04d-%02d-%02d_%02d-%02d-%02d",
                    year, month, day, hour12, minute, second);

        } catch (DateTimeParseException ex) {
            throw new IllegalArgumentException(
                    "Invalid XML startTime format. Expected: M/d/yyyy h:mm:ss a → " + xmlStartTime, ex);
        }
    }

    // ========== Pagination Utility Methods ==========

    /**
     * Creates a Pageable object from pagination request.
     *
     * @param pagination The pagination request (nullable)
     * @return Pageable with safe defaults if request is null
     */
    private Pageable createPageable(PaginationRequest pagination) {
        int pageNumber = pagination != null ? pagination.getPageNumber() : MIN_PAGE_NUMBER;
        int requestedPageSize = pagination != null ? pagination.getPageSize() : DEFAULT_PAGE_SIZE;

        int pageSize = requestedPageSize > 0 ? requestedPageSize : DEFAULT_PAGE_SIZE;
        int safePage = Math.max(pageNumber - 1, 0);

        return PageRequest.of(safePage, pageSize, Sort.by("dateAdded").descending());
    }

    /**
     * Builds a search response from a page result.
     *
     * @param pageResult The page of results
     * @return Formatted VpiSearchResponse
     */
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

    // ========== String Utility Methods ==========

    /**
     * Cleans a list of names by removing nulls, trimming, and filtering empty strings.
     *
     * @param names The list of names to clean (nullable)
     * @return Cleaned list of names
     */
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

    /**
     * Normalizes a string to lowercase for case-insensitive comparison.
     *
     * @param value The value to normalize
     * @return Normalized lowercase string, or empty string if null
     */
    private String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

    /**
     * Checks if a string is null or empty after trimming.
     *
     * @param value The string to check
     * @return true if null or empty, false otherwise
     */
    private boolean isNullOrEmpty(String value) {
        return value == null || value.trim().isEmpty();
    }

    // ========== Recording Search Methods ==========

    /**
     * Finds a VPI recording in Azure blob storage matching the request criteria.
     *
     * <p>Search strategy:
     * <ol>
     *   <li>Build day-based prefix for blob search</li>
     *   <li>Find XML metadata files matching timestamp and customer</li>
     *   <li>Parse XML files and filter by metadata fields</li>
     *   <li>Return the first successful match</li>
     * </ol>
     *
     * @param req The recording request with search criteria
     * @return RecordingSearchResult containing blob name and match status
     * @throws RecordingNotFoundException if no matching recording is found
     */
    private RecordingSearchResult findRecordingVPI(RecordingRequest req) {
        String fileDate = xmlStartTimeToFileTimestamp(req.getDate());
        String prefix = buildDayPrefix(req.getOpco(), parseDateTimeXML(req.getDate()).toLocalDate());
        String normalizedCustomer = normalize(req.getUsername());

        List<String> xmlCandidates = findXmlCandidates(req.getOpco(), prefix, fileDate, normalizedCustomer);

        if (xmlCandidates.isEmpty()) {
            throw new RecordingNotFoundException("No XML recordings found");
        }

        List<MediaMetadata> matchedMedia = processXmlCandidates(
                xmlCandidates, fileDate, normalizedCustomer, req);

        return buildRecordingResult(matchedMedia, prefix);
    }

    /**
     * Finds XML blob candidates based on OPCO type.
     *
     * @param opco Operating company code
     * @param prefix Blob prefix path
     * @param fileDate File timestamp
     * @param normalizedCustomer Normalized customer name
     * @return List of XML blob names
     */
    private List<String> findXmlCandidates(String opco, String prefix,
                                           String fileDate, String normalizedCustomer) {
        return "CMP".equalsIgnoreCase(opco)
                ? findCmpXmlBlobs(prefix + "Metadata/")
                : findMatchingXmlBlobs(prefix, fileDate, normalizedCustomer);
    }

    /**
     * Processes XML candidates and extracts matching media metadata.
     *
     * @param xmlCandidates List of XML blob names to process
     * @param fileDate Expected file timestamp
     * @param normalizedCustomer Normalized customer name
     * @param req Recording request with filter criteria
     * @return List of matched MediaMetadata
     * @throws RecordingNotFoundException if no matches found
     */
    private List<MediaMetadata> processXmlCandidates(List<String> xmlCandidates,
                                                     String fileDate,
                                                     String normalizedCustomer,
                                                     RecordingRequest req) {
        List<MediaMetadata> matchedMedia = new ArrayList<>();
        boolean metadataFoundButNoMatch = false;

        for (String xmlBlob : xmlCandidates) {
            List<MediaMetadata> metaMatch = parseXml(xmlBlob);

            if (metaMatch.size() > 1) {
                metaMatch = findMatchingMediaCMP(metaMatch, fileDate, normalizedCustomer);
            }

            for (MediaMetadata media : metaMatch) {
                if (matchesMetadata(media, req.getAniAliDigits(), req.getDuration(),
                        req.getExtensionNum(), req.getChannelNum(), req.getObjectId())) {
                    metadataFoundButNoMatch = true;

                    if (STATUS_RECORDING_SUCCESS.equals(media.getResult())) {
                        matchedMedia.add(media);
                    }
                }
            }
        }

        validateMatchedMedia(matchedMedia, metadataFoundButNoMatch);
        return matchedMedia;
    }

    /**
     * Validates that matched media is not empty and throws appropriate exception.
     *
     * @param matchedMedia List of matched media
     * @param metadataFoundButNoMatch Flag indicating if metadata was found but didn't match
     * @throws RecordingNotFoundException if no matches found with appropriate message
     */
    private void validateMatchedMedia(List<MediaMetadata> matchedMedia,
                                      boolean metadataFoundButNoMatch) {
        if (matchedMedia.isEmpty()) {
            if (metadataFoundButNoMatch) {
                throw new RecordingNotFoundException("Recording audio is not migrated to the Azure blob");
            }
            throw new RecordingNotFoundException("Recording xml found but metadata mismatch");
        }
    }

    /**
     * Builds the final recording search result from matched media.
     *
     * @param matchedMedia List of matched media metadata
     * @param prefix Blob prefix path
     * @return RecordingSearchResult with blob name and multiple match flag
     */
    private RecordingSearchResult buildRecordingResult(List<MediaMetadata> matchedMedia, String prefix) {
        RecordingSearchResult result = new RecordingSearchResult();
        result.setBlobName(prefix + matchedMedia.getFirst().getFileName());

        if (matchedMedia.size() > 1) {
            result.setMultipleFound(true);
            logger.warn("Multiple recordings found for request. Using first match.");
        }

        return result;
    }
    /**
     * Finds XML blob files for CMP OPCO.
     *
     * @param dayPrefix The day prefix path
     * @return List of XML blob names
     */
    private List<String> findCmpXmlBlobs(String dayPrefix) {
        List<String> blobs = vpiAzureRepository.listBlobs(dayPrefix);
        return blobs.stream()
                .filter(blob -> blob.toLowerCase(Locale.ROOT).endsWith(".xml"))
                .toList();
    }

    /**
     * Finds XML blobs matching timestamp and customer name.
     *
     * @param prefix The blob prefix
     * @param expectedDateTime The expected timestamp
     * @param normalizedCustomer The normalized customer name
     * @return List of matching XML blob names
     */
    private List<String> findMatchingXmlBlobs(String prefix,
                                              String expectedDateTime,
                                              String normalizedCustomer) {
        List<String> blobs = vpiAzureRepository.listBlobs(prefix);
        List<String> matchedXmls = new ArrayList<>();

        for (String blobName : blobs) {
            if (!blobName.endsWith(".xml")) {
                continue;
            }

            if (!matchesTimestamp(blobName, expectedDateTime)) {
                continue;
            }

            if (matchesCustomer(blobName, normalizedCustomer)) {
                matchedXmls.add(blobName);
            }
        }

        return matchedXmls;
    }

    /**
     * Filters media metadata for CMP recordings matching timestamp and customer.
     *
     * @param metadataList The list of media metadata to filter
     * @param expectedDateTime The expected timestamp
     * @param normalizedCustomer The normalized customer name
     * @return Filtered list of valid metadata
     */
    public List<MediaMetadata> findMatchingMediaCMP(List<MediaMetadata> metadataList,
                                                    String expectedDateTime,
                                                    String normalizedCustomer) {
        if (metadataList == null || metadataList.isEmpty()) {
            logger.debug("Empty or null metadata list provided for validation");
            return Collections.emptyList();
        }

        logger.info("Validating {} metadata records against timestamp: {} and customer: {}",
                metadataList.size(), expectedDateTime, normalizedCustomer);

        List<MediaMetadata> validMetadata = metadataList.stream()
                .filter(Objects::nonNull)
                .filter(meta -> isValidFilename(meta.getFileName(), expectedDateTime, normalizedCustomer))
                .toList();

        logger.info("Validation complete: {} out of {} records passed filename validation",
                validMetadata.size(), metadataList.size());

        return validMetadata;
    }

    /**
     * Validates a filename against expected timestamp and customer name.
     *
     * @param fileName The filename to validate
     * @param expectedDateTime The expected timestamp
     * @param normalizedCustomer The normalized customer name
     * @return true if valid, false otherwise
     */
    private boolean isValidFilename(String fileName, String expectedDateTime, String normalizedCustomer) {
        if (isNullOrEmpty(fileName)) {
            logger.debug("Skipping validation for null or empty filename");
            return false;
        }

        boolean timestampMatches = matchesTimestamp(fileName, expectedDateTime);
        boolean customerMatches = matchesCustomer(fileName, normalizedCustomer);

        if (!timestampMatches || !customerMatches) {
            logger.debug("Filename validation failed for '{}': timestamp={}, customer={}",
                    fileName, timestampMatches, customerMatches);
        }

        return timestampMatches && customerMatches;
    }

    /**
     * Parses XML blob content to extract media metadata.
     *
     * @param blobName The blob name containing XML
     * @return List of media metadata extracted from XML
     * @throws RecordingProcessingException if XML parsing fails
     */
    private List<MediaMetadata> parseXml(String blobName) {
        try {
            byte[] xmlBytes = vpiAzureRepository.getBlobContent(blobName);
            try (InputStream is = new ByteArrayInputStream(xmlBytes)) {
                return processMediaXml(is);
            }
        } catch (IOException e) {
            throw new RecordingProcessingException("Failed parsing XML " + blobName, e);
        }
    }

    /**
     * Processes XML stream to extract media metadata.
     *
     * @param xmlStream The XML input stream
     * @return List of extracted media metadata
     */
    public List<MediaMetadata> processMediaXml(InputStream xmlStream) {
        logger.debug("Starting XML media metadata extraction");

        List<MediaMetadata> metadataList = xmlParser.parse(xmlStream);

        logger.info("Extracted {} media metadata records", metadataList.size());

        long invalidCount = metadataList.stream()
                .filter(metadata -> !metadata.isValid())
                .count();

        if (invalidCount > 0) {
            logger.warn("Found {} invalid metadata records", invalidCount);
        }

        return metadataList;
    }

    // ========== Metadata Matching Methods ==========

    /**
     * Checks if media metadata matches all specified criteria.
     *
     * @param metadata The metadata to check
     * @param aniAliDigits ANI/ALI digits filter (nullable)
     * @param duration Duration filter (nullable)
     * @param extensionNum Extension number filter (nullable)
     * @param channelNum Channel number filter (nullable)
     * @param objectId Object ID filter (nullable)
     * @return true if all non-null criteria match, false otherwise
     */
    private boolean matchesMetadata(MediaMetadata metadata,
                                    String aniAliDigits,
                                    Integer duration,
                                    String extensionNum,
                                    Integer channelNum,
                                    String objectId) {
        if (metadata == null || metadata.getFields() == null) {
            return false;
        }

        Map<String, String> fields = metadata.getFields();

        return matchesField(fields, "aniAliDigits", aniAliDigits) &&
                matchesIntegerField(fields, "duration", duration) &&
                matchesField(fields, "extensionNum", extensionNum) &&
                matchesIntegerField(fields, "channelNum", channelNum) &&
                matchesField(fields, "objectID", objectId);
    }

    /**
     * Checks if a string field matches the expected value.
     * Null or empty expected values are treated as wildcards (always match).
     *
     * @param fields The fields map
     * @param fieldName The field name to check
     * @param expectedValue The expected value (nullable)
     * @return true if matches or is wildcard, false otherwise
     */
    private boolean matchesField(Map<String, String> fields, String fieldName, String expectedValue) {
        if (isNullOrEmpty(expectedValue)) {
            return true;
        }

        String actualValue = fields.get(fieldName);
        if (isNullOrEmpty(actualValue)) {
            return true;
        }

        return expectedValue.equals(actualValue);
    }

    /**
     * Checks if an integer field matches the expected value.
     * Null expected values are treated as wildcards (always match).
     *
     * @param fields The fields map
     * @param fieldName The field name to check
     * @param expectedValue The expected value (nullable)
     * @return true if matches or is wildcard, false otherwise
     */
    private boolean matchesIntegerField(Map<String, String> fields,
                                        String fieldName,
                                        Integer expectedValue) {
        if (expectedValue == null) {
            return true;
        }

        String actualValue = fields.get(fieldName);
        if (isNullOrEmpty(actualValue)) {
            return false;
        }

        try {
            Integer actualInt = Integer.valueOf(actualValue.trim());
            return expectedValue.equals(actualInt);
        } catch (NumberFormatException ex) {
            logger.debug("Invalid integer value for field {} : {}", fieldName, actualValue);
            return false;
        }
    }

    /**
     * Checks if a blob name's timestamp matches the expected value.
     *
     * @param blobName The blob name
     * @param expected The expected timestamp string
     * @return true if matches, false otherwise
     */
    private boolean matchesTimestamp(String blobName, String expected) {
        return extractDateTime(blobName)
                .map(actual -> actual.equals(expected))
                .orElse(false);
    }

    /**
     * Checks if a blob name's customer name matches the expected value.
     *
     * @param blobName The blob name
     * @param normalizedCustomer The normalized expected customer name
     * @return true if matches, false otherwise
     */
    private boolean matchesCustomer(String blobName, String normalizedCustomer) {
        return extractCustomerName(blobName)
                .map(this::normalize)
                .map(name -> name.equals(normalizedCustomer))
                .orElse(false);
    }

    // ========== Blob Path Methods ==========

    /**
     * Builds a day-based prefix for blob storage paths.
     *
     * @param opco The OPCO code
     * @param date The date
     * @return Formatted prefix string (e.g., "RGE/2024/1/15/")
     */
    private String buildDayPrefix(String opco, LocalDate date) {
        return String.format("%s/%d/%d/%d/",
                opco.toUpperCase(Locale.ROOT),
                date.getYear(),
                date.getMonthValue(),
                date.getDayOfMonth());
    }

    /**
     * Downloads blob content from Azure storage.
     *
     * @param blobName The blob name to download
     * @return Byte array of blob content
     * @throws RecordingProcessingException if download fails
     */
    private byte[] downloadBlob(String blobName) {
        try {
            return vpiAzureRepository.getBlobContent(blobName);
        } catch (Exception e) {
            throw new RecordingProcessingException("Failed to download recording: " + e.getMessage(), e);
        }
    }

    // ========== Filename Parsing Methods ==========

    /**
     * Extracts date-time portion from a blob filename.
     *
     * @param blobName The blob name
     * @return Optional containing extracted datetime string
     */
    private Optional<String> extractDateTime(String blobName) {
        try {
            String fileName = extractFileName(blobName);

            if (fileName.length() < FILENAME_DATETIME_END) {
                return Optional.empty();
            }

            return Optional.of(
                    fileName.substring(FILENAME_DATETIME_START, FILENAME_DATETIME_END)
            );
        } catch (Exception ex) {
            logger.debug("Failed to extract datetime from blob: {}", blobName, ex);
            return Optional.empty();
        }
    }

    /**
     * Extracts customer name from a blob filename.
     *
     * @param blobName The blob name
     * @return Optional containing extracted customer name
     */
    private Optional<String> extractCustomerName(String blobName) {
        try {
            String fileName = extractFileName(blobName);
            int endIndex = fileName.indexOf(WAV_EXTENSION);

            if (endIndex == -1 || fileName.length() < FILENAME_CUSTOMER_START) {
                return Optional.empty();
            }

            return Optional.of(
                    fileName.substring(FILENAME_CUSTOMER_START, endIndex)
            );
        } catch (Exception ex) {
            logger.debug("Failed to extract customer name from blob: {}", blobName, ex);
            return Optional.empty();
        }
    }

    /**
     * Extracts the filename portion from a full blob path.
     *
     * @param blobName The full blob path
     * @return The filename only
     */
    private String extractFileName(String blobName) {
        return blobName.substring(blobName.lastIndexOf('/') + 1);
    }

    // ========== FFmpeg Conversion Helper Methods ==========

    /**
     * Builds the FFmpeg command array for WAV to MP3 conversion.
     *
     * @return String array containing FFmpeg command and arguments
     */
    private String[] buildFfmpegCommand() {
        return new String[]{
                ffmpegPath,
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
    }

    /**
     * Starts the FFmpeg process with secure environment settings.
     *
     * @return Started Process instance
     * @throws IOException if process creation fails
     */
    private Process startFfmpegProcess() throws IOException {
        ProcessBuilder pb = new ProcessBuilder(buildFfmpegCommand());

        if ("/usr/bin/ffmpeg".equals(ffmpegPath)) {
            Map<String, String> env = pb.environment();
            env.clear();
            env.put("PATH", "/usr/bin:/bin");
        }

        return pb.start();
    }

    /**
     * Asynchronously reads the error stream from FFmpeg process.
     *
     * @param process The FFmpeg process
     * @return CompletableFuture containing error output
     */
    private CompletableFuture<String> readErrorStream(Process process) {
        return CompletableFuture.supplyAsync(() -> {
            try (InputStream stderr = process.getErrorStream();
                 BufferedReader reader = new BufferedReader(
                         new InputStreamReader(stderr, StandardCharsets.UTF_8))) {
                return reader.lines().collect(Collectors.joining("\n"));
            } catch (IOException e) {
                logger.error("Failed to read FFmpeg error stream", e);
                return "Failed to read error stream: " + e.getMessage();
            }
        });
    }

    /**
     * Asynchronously writes WAV data to FFmpeg's stdin.
     *
     * @param process The FFmpeg process
     * @param wavData The WAV data to write
     * @return CompletableFuture that completes when writing is done
     */
    private CompletableFuture<Void> writeInputData(Process process, byte[] wavData) {
        return CompletableFuture.runAsync(() -> {
            try (OutputStream stdin = process.getOutputStream()) {
                stdin.write(wavData);
                stdin.flush();
            } catch (IOException e) {
                logger.error("Failed to write input data to FFmpeg", e);
                throw new UncheckedIOException("Write failed", e);
            }
        });
    }

    /**
     * Asynchronously reads MP3 data from FFmpeg's stdout.
     *
     * @param process The FFmpeg process
     * @return CompletableFuture containing MP3 data
     */
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

    /**
     * Validates the FFmpeg conversion result.
     *
     * @param exitCode FFmpeg process exit code
     * @param errors Error messages from FFmpeg
     * @param inputSize Size of input WAV data
     * @param outputSize Size of output MP3 data
     * @throws RecordingProcessingException if conversion failed
     */
    private void validateConversionResult(int exitCode, String errors, int inputSize, int outputSize) {
        if (exitCode != 0) {
            throw new RecordingProcessingException(
                    String.format("FFmpeg failed (exit %d): %s", exitCode, errors));
        }

        if (!errors.isEmpty()) {
            logger.warn("FFmpeg warnings: {}", errors);
        }

        logger.info("Conversion successful: {} bytes WAV -> {} bytes MP3", inputSize, outputSize);
    }

    /**
     * Forcibly destroys a process if it's still alive.
     *
     * @param process The process to destroy (nullable)
     */
    private void destroyProcess(Process process) {
        if (process != null && process.isAlive()) {
            process.destroyForcibly();
            logger.debug("FFmpeg process destroyed");
        }
    }

    // ========== Response Building Methods ==========

    /**
     * Builds an HTTP response for audio data.
     *
     * @param mp3Data The MP3 audio bytes
     * @param originalFilename The original filename
     * @return ResponseEntity with appropriate headers and audio data
     */
    private ResponseEntity<ByteArrayResource> buildAudioResponse(byte[] mp3Data, String originalFilename) {
        String mp3Filename = originalFilename + MP3_EXTENSION;

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType("audio/mpeg"));
        headers.setContentDispositionFormData("inline", mp3Filename);
        headers.setContentLength(mp3Data.length);

        ByteArrayResource resource = new ByteArrayResource(mp3Data);

        return ResponseEntity.ok()
                .headers(headers)
                .body(resource);
    }

    /**
     * Builds an HTTP response for ZIP file data.
     *
     * @param zipData The ZIP file bytes
     * @return ResponseEntity with appropriate headers and ZIP data
     */
    private ResponseEntity<byte[]> buildZipResponse(byte[] zipData) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
        headers.set(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"recordings.zip\"");
        headers.setContentLength(zipData.length);

        return new ResponseEntity<>(zipData, headers, HttpStatus.OK);
    }

    // ========== ZIP Creation Methods ==========

    /**
     * Adds a single recording to a ZIP output stream.
     *
     * @param req The recording request
     * @param zos The ZIP output stream
     * @return RecordingStatus indicating success or failure
     */
    private RecordingStatus addRecordingToZip(RecordingRequest req, ZipOutputStream zos) {
        validateRequest(req);

        String fileDate = xmlStartTimeToFileTimestamp(req.getDate());
        String zipEntryName = fileDate + req.getUsername();
        String blobName = null;

        try {
            blobName = findRecordingVPI(req).getBlobName();

            if (blobName == null || blobName.isEmpty()) {
                logger.warn("No matching blob found for user={} date={}", req.getUsername(), req.getDate());
                return createNotFoundStatus(req);
            }

            addBlobToZip(blobName, zipEntryName, zos);
            logger.info("Successfully added recording to ZIP: user={} date={} blob={}",
                    req.getUsername(), req.getDate(), blobName);
            return createSuccessStatus(req, zipEntryName);

        } catch (RecordingNotFoundException e) {
            logger.warn("Recording not found for user={} date={}: {}",
                    req.getUsername(), req.getDate(), e.getMessage());
            return createNotFoundStatus(req);

        } catch (IOException e) {
            logger.error("IO error while adding recording to ZIP: user={} date={} blob={}",
                    req.getUsername(), req.getDate(), blobName, e);
            return createErrorStatus(req, zipEntryName, "Failed to write recording to ZIP: " + e.getMessage());

        } catch (Exception e) {
            logger.error("Unexpected error while processing recording: user={} date={} blob={}",
                    req.getUsername(), req.getDate(), blobName, e);
            return createErrorStatus(req, zipEntryName, "Failed to process recording: " + e.getMessage());
        }
    }

    /**
     * Creates a recording status for not found scenarios.
     *
     * @param req The recording request
     * @return RecordingStatus with NOT_FOUND status
     */
    private RecordingStatus createNotFoundStatus(RecordingRequest req) {
        return new RecordingStatus(
                req.getUsername(),
                req.getDate(),
                null,
                STATUS_NOT_FOUND,
                "No matching audio file found"
        );
    }

    /**
     * Creates a recording status for successful operations.
     *
     * @param req The recording request
     * @param zipEntryName The ZIP entry name
     * @return RecordingStatus with SUCCESS status
     */
    private RecordingStatus createSuccessStatus(RecordingRequest req, String zipEntryName) {
        return new RecordingStatus(
                req.getUsername(),
                req.getDate(),
                zipEntryName,
                STATUS_RECORDING_SUCCESS,
                null
        );
    }

    /**
     * Creates a recording status for error scenarios.
     *
     * @param req The recording request
     * @param zipEntryName The ZIP entry name
     * @param errorMessage The error message
     * @return RecordingStatus with ERROR status
     */
    private RecordingStatus createErrorStatus(RecordingRequest req, String zipEntryName, String errorMessage) {
        return new RecordingStatus(
                req.getUsername(),
                req.getDate(),
                zipEntryName,
                STATUS_ERROR,
                errorMessage
        );
    }

    /**
     * Adds a blob to a ZIP output stream.
     *
     * @param blobName The blob name to add
     * @param zipEntryName The name for the ZIP entry
     * @param zos The ZIP output stream
     * @throws IOException if I/O error occurs
     */
    private void addBlobToZip(String blobName, String zipEntryName, ZipOutputStream zos) throws IOException {
        zos.putNextEntry(new ZipEntry(zipEntryName));
        try (InputStream blobStream = vpiAzureRepository.getBlobStream(blobName)) {
            StreamUtils.copy(blobStream, zos);
        } finally {
            zos.closeEntry();
        }
    }

    /**
     * Adds a status summary JSON file to a ZIP.
     *
     * @param summary The status summary
     * @param zos The ZIP output stream
     * @throws IOException if I/O error occurs
     */
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

    // ========== Database Search Methods ==========

    /**
     * Performs a search across the appropriate OPCO repository.
     *
     * @param from Start datetime
     * @param to End datetime
     * @param opco OPCO code
     * @param filters Additional filters
     * @param userIds Matched user IDs
     * @param pageable Pagination settings
     * @return Page of VpiMetadata results
     */
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

    /**
     * Searches CMP repository.
     *
     * @param from Start datetime
     * @param to End datetime
     * @param filters Filters
     * @param userIds User IDs
     * @param pageable Pagination
     * @return Page of results
     */
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

    /**
     * Searches NYSEG repository.
     *
     * @param from Start datetime
     * @param to End datetime
     * @param filters Filters
     * @param userIds User IDs
     * @param pageable Pagination
     * @return Page of results
     */
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

    /**
     * Searches RGE repository.
     *
     * @param from Start datetime
     * @param to End datetime
     * @param filters Filters
     * @param userIds User IDs
     * @param pageable Pagination
     * @return Page of results
     */
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

    /**
     * Fetches user IDs matching any of the provided names.
     *
     * @param opco OPCO code
     * @param names List of names to match
     * @return Set of matched user UUIDs
     */
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

    /**
     * Fetches user names for a set of user IDs.
     *
     * @param opco OPCO code
     * @param userIds Set of user UUIDs
     * @return Map of user ID to full name
     */
    private Map<UUID, String> fetchUserNames(String opco, Set<UUID> userIds) {
        if (userIds.isEmpty()) {
            return Collections.emptyMap();
        }

        String upperOpco = opco.toUpperCase();

        return switch (upperOpco) {
            case "CMP" -> buildUserNameMap(
                    cmpUserRepo.findByUserIdIn(userIds),
                    VpiUsersCmp::getUserId,
                    VpiUsersCmp::getFullName);
            case "NYSEG" -> buildUserNameMap(
                    nysegUserRepo.findByUserIdIn(userIds),
                    VpiUsersNyseg::getUserId,
                    VpiUsersNyseg::getFullName);
            case "RGE" -> buildUserNameMap(
                    rgeUserRepo.findByUserIdIn(userIds),
                    VpiUsersRge::getUserId,
                    VpiUsersRge::getFullName);
            default -> Collections.emptyMap();
        };
    }

    /**
     * Builds a map of user IDs to names from a list of user entities.
     *
     * @param users List of user entities
     * @param idExtractor Function to extract user ID
     * @param nameExtractor Function to extract user name
     * @param <T> Type of user entity
     * @return Map of UUID to name
     */
    private <T> Map<UUID, String> buildUserNameMap(
            List<T> users,
            java.util.function.Function<T, UUID> idExtractor,
            java.util.function.Function<T, String> nameExtractor) {
        return users.stream()
                .collect(Collectors.toMap(idExtractor, nameExtractor));
    }

    // ========== Metadata Retrieval Methods ==========

    /**
     * Retrieves metadata by OPCO and object ID.
     *
     * @param id Object ID
     * @param opco OPCO code
     * @return List of metadata maps
     */
    private List<Map<String, Object>> getMetadataByOpco(UUID id, String opco) {
        String upperOpco = opco.toUpperCase();

        return switch (upperOpco) {
            case "CMP" -> metadataFull(cmpRepo.findByObjectId(id));
            case "NYSEG" -> metadataFull(nysegRepo.findByObjectId(id));
            case "RGE" -> metadataFull(rgeRepo.findByObjectId(id));
            default -> throw new InvalidRequestException("Invalid OPCO code: " + opco);
        };
    }

    /**
     * Converts recording entities to metadata maps.
     *
     * @param recordings List of recording entities
     * @return List of metadata maps
     */
    private List<Map<String, Object>> metadataFull(List<? extends VpiCaptureView> recordings) {
        return recordings.stream()
                .map(this::buildMetadataMap)
                .toList();
    }

    /**
     * Builds a comprehensive metadata map from a recording entity.
     *
     * @param rec The recording entity
     * @return Map containing all metadata fields
     */
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

    /**
     * Adds identifier fields to metadata map.
     */
    private void addIdentifierFields(Map<String, Object> map, VpiCaptureView rec) {
        map.put("objectId", rec.getObjectId());
        map.put("dateAdded", rec.getDateAdded());
        map.put("resourceId", rec.getResourceId());
        map.put("workstationId", rec.getWorkstationId());
        map.put("userId", rec.getUserId());
    }

    /**
     * Adds timing fields to metadata map.
     */
    private void addTimingFields(Map<String, Object> map, VpiCaptureView rec) {
        map.put("startTime", rec.getStartTime());
        map.put("gmtOffset", rec.getGmtOffset());
        map.put("gmtStartTime", rec.getGmtStartTime());
        map.put("duration", rec.getDuration());
    }

    /**
     * Adds trigger and tag fields to metadata map.
     */
    private void addTriggerAndTagFields(Map<String, Object> map, VpiCaptureView rec) {
        map.put("triggeredByResourceTypeId", rec.getTriggeredByResourceTypeId());
        map.put("triggeredByObjectId", rec.getTriggeredByObjectId());
        map.put("flagId", rec.getFlagId());
        map.put("tags", rec.getTags());
        map.put("sensitivityLevel", rec.getSensitivityLevel());
        map.put("clientId", rec.getClientId());
    }

    /**
     * Adds channel and agent fields to metadata map.
     */
    private void addChannelAndAgentFields(Map<String, Object> map, VpiCaptureView rec) {
        map.put("channelNum", rec.getChannelNum());
        map.put("channelName", rec.getChannelName());
        map.put("extensionNum", rec.getExtensionNum());
        map.put("agentId", rec.getAgentId());
        map.put("pbxDnis", rec.getPbxDnis());
        map.put("anialidigits", rec.getAnialidigits());
        map.put("direction", rec.getDirection());
    }

    /**
     * Adds media fields to metadata map.
     */
    private void addMediaFields(Map<String, Object> map, VpiCaptureView rec) {
        map.put("mediaFileId", rec.getMediaFileId());
        map.put("mediaManagerId", rec.getMediaManagerId());
        map.put("mediaRetention", rec.getMediaRetention());
    }

    /**
     * Adds call ID fields to metadata map.
     */
    private void addCallIdFields(Map<String, Object> map, VpiCaptureView rec) {
        map.put("callId", rec.getCallId());
        map.put("previousCallId", rec.getPreviousCallId());
        map.put("globalCallId", rec.getGlobalCallId());
    }

    /**
     * Adds service fields to metadata map.
     */
    private void addServiceFields(Map<String, Object> map, VpiCaptureView rec) {
        map.put("classOfService", rec.getClassOfService());
        map.put("classOfServiceDate", rec.getClassOfServiceDate());
        map.put("xPlatformRef", rec.getXPlatformRef());
    }

    /**
     * Adds transcription fields to metadata map.
     */
    private void addTranscriptionFields(Map<String, Object> map, VpiCaptureView rec) {
        map.put("transcriptResult", rec.getTranscriptResult());
        map.put("warehouseObjectKey", rec.getWarehouseObjectKey());
        map.put("transcriptStatus", rec.getTranscriptStatus());
        map.put("audioChannels", rec.getAudioChannels());
        map.put("hasTalkover", rec.getHasTalkover());
    }

    // ========== Entity Mapping Methods ==========

    /**
     * Enriches a page of recordings with user names and converts to DTOs.
     *
     * @param page Page of recording entities
     * @param opco OPCO code
     * @return Page of VpiMetadata DTOs
     */
    private Page<VpiMetadata> enrichAndMap(Page<? extends VpiCaptureView> page, String opco) {
        if (page.isEmpty()) {
            return Page.empty(page.getPageable());
        }

        Set<UUID> userIds = extractUserIds(page);
        Map<UUID, String> userNameMap = fetchUserNames(opco, userIds);

        return page.map(rec -> convertToMetadata(rec, opco, userNameMap));
    }

    /**
     * Extracts unique user IDs from a page of recordings.
     *
     * @param page Page of recordings
     * @return Set of user UUIDs
     */
    private Set<UUID> extractUserIds(Page<? extends VpiCaptureView> page) {
        return page.getContent().stream()
                .map(VpiCaptureView::getUserId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
    }

    /**
     * Converts a recording entity to a VpiMetadata DTO.
     *
     * @param rec The recording entity
     * @param opco OPCO code
     * @param userNameMap Map of user IDs to names
     * @return VpiMetadata DTO
     */
    private VpiMetadata convertToMetadata(
            VpiCaptureView rec,
            String opco,
            Map<UUID, String> userNameMap) {

        VpiMetadata dto = new VpiMetadata();

        dto.setObjectId(rec.getObjectId());
        dto.setDateAdded(toXmlStartTime(rec.getDateAdded()));
        dto.setStartTime(toXmlStartTime(rec.getStartTime()));
        dto.setDuration(rec.getDuration());
        dto.setTags(rec.getTags());
        dto.setChannelName(rec.getChannelName());
        dto.setCallId(rec.getCallId());
        dto.setUserId(rec.getUserId());
        dto.setAgentId(rec.getAgentId());
        dto.setExtensionNum(rec.getExtensionNum());
        dto.setChannelNum(rec.getChannelNum());
        dto.setAniAliDigits(rec.getAnialidigits());
        dto.setUsername(userNameMap.get(rec.getUserId()));
        dto.setDirection(rec.getDirection());
        dto.setOpco(opco);

        return dto;
    }
}