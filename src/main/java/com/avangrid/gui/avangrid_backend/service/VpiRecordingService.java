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
import com.avangrid.gui.avangrid_backend.infra.azure.AzureBlobRepository;
import com.avangrid.gui.avangrid_backend.model.common.MediaMetadata;
import com.avangrid.gui.avangrid_backend.model.common.RecordingStatus;
import com.avangrid.gui.avangrid_backend.model.common.VpiMetadata;

import com.avangrid.gui.avangrid_backend.model.dto.request.PaginationRequest;
import com.avangrid.gui.avangrid_backend.model.dto.request.RecordingRequest;
import com.avangrid.gui.avangrid_backend.model.dto.request.VpiFiltersRequest;
import com.avangrid.gui.avangrid_backend.model.dto.request.VpiSearchRequest;
import com.avangrid.gui.avangrid_backend.model.dto.response.PaginationResponse;
import com.avangrid.gui.avangrid_backend.model.common.RecordingSearchResult;
import com.avangrid.gui.avangrid_backend.model.dto.response.VpiSearchResponse;
import com.avangrid.gui.avangrid_backend.model.entitiybase.VpiCaptureView;


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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Service for managing VRS (VPI Recording Service) recordings.
 *
 * <p>This service provides comprehensive functionality for the VRS portal, including:
 * <ul>
 *   <li>Recording retrieval and search across all three operating companies (OPCOs)</li>
 *   <li>Audio format conversion from WAV to MP3 using FFmpeg</li>
 *   <li>Bulk download operations with ZIP packaging</li>
 *   <li>Metadata extraction and management from Azure Blob Storage and the database</li>
 * </ul>
 *
 * <p>Supported OPCOs: RGE (Rochester Gas and Electric), CMP (Central Maine Power), NYSEG (New York State Electric and Gas)
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

    private static final String CMP = "CMP";
    private static final String NYSEG = "NYSEG";
    private static final String RGE = "RGE";
    private static final String METADATA = "Metadata/";
    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MIN_PAGE_NUMBER = 1;
    private static final String STATUS_SUCCESS = "200";
    private static final String MESSAGE_SUCCESS = "Success";
    private static final Set<String> ALLOWED_OPCOS = Set.of(RGE, CMP, NYSEG);

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
     * Constructs a new VpiRecordingService with all required dependencies.
     * OPCO-specific repositories (CMP, NYSEG, RGE) are optional and may be disabled
     * via Spring configuration when that datasource is not available.
     *
     * @param vpiAzureRepository Azure Blob Storage repository used to stream and download VRS recording files
     * @param cmpRepo            CMP OPCO database repository (optional — may be null if CMP datasource is disabled)
     * @param nysegRepo          NYSEG OPCO database repository (optional — may be null if NYSEG datasource is disabled)
     * @param rgeRepo            RGE OPCO database repository (optional — may be null if RGE datasource is disabled)
     * @param cmpUserRepo        CMP user repository for agent name lookups (optional)
     * @param nysegUserRepo      NYSEG user repository for agent name lookups (optional)
     * @param rgeUserRepo        RGE user repository for agent name lookups (optional)
     * @param xmlParser          XML metadata parser used to extract {@link MediaMetadata} from VRS XML blob files
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
     * Retrieves paginated VRS recording table data based on the provided search criteria.
     *
     * <p>This method supports:
     * <ul>
     *   <li>Date range filtering (EST input is converted to UTC for database queries)</li>
     *   <li>OPCO-specific searches across CMP, RGE, and NYSEG</li>
     *   <li>Custom filters such as tags, channel numbers, and extension numbers</li>
     *   <li>Agent/user name filtering with partial-match support</li>
     *   <li>Pagination with configurable page size and page number</li>
     * </ul>
     *
     * @param request Search request containing date range, OPCO, optional filters, and pagination settings
     * @return {@link VpiSearchResponse} containing paginated recording results and pagination metadata
     * @throws InvalidRequestException  if any required field (fromDate, toDate, OPCO) is missing or invalid
     * @throws IllegalArgumentException if the end date is before the start date
     */
    public VpiSearchResponse getTableData(VpiSearchRequest request) {
        logger.debug("Fetching table data for request: {}", request);

        validateSearchRequest(request);

        OffsetDateTime from = convertEstToUtc(parseDateTime(request.getFromDate()));
        OffsetDateTime to = convertEstToUtc(parseDateTime(request.getToDate()));

        if (to.isBefore(from)) {
            throw new InvalidRequestException("End date must be after start date");
        }

        Pageable pageable = createPageable(request.getPagination());
        Page<VpiMetadata> pageResult = search(from, to, request.getOpco(), request.getFilters(), pageable);

        return buildSearchResponse(pageResult);
    }

    /**
     * Retrieves the full metadata record for a specific VRS recording identified by its object ID and OPCO.
     *
     * <p>Returns all available metadata fields including:
     * <ul>
     *   <li>Timing information: start time, duration, GMT offset</li>
     *   <li>Channel and agent details: channel name, extension number, agent ID</li>
     *   <li>Call identifiers: call ID, previous call ID, global call ID</li>
     *   <li>Media file information: media file ID, media manager ID, media retention</li>
     *   <li>Transcription status and warehouse object key</li>
     * </ul>
     *
     * @param id   Unique object ID (UUID) of the VRS recording record
     * @param opco Operating company code — must be one of: RGE, CMP, NYSEG
     * @return {@link Map} containing all metadata field names and their corresponding values
     * @throws InvalidRequestException      if the OPCO value is null, blank, or not one of the allowed values
     * @throws RecordingNotFoundException   if no recording record exists for the given ID and OPCO
     */
    public Map<String, Object> getMetadata(UUID id, String opco) {
        logger.debug("Fetching metadata for ....");

        validateOpco(opco);

        List<Map<String, Object>> metadata = getMetadataByOpco(id, opco);

        if (metadata.isEmpty()) {
            throw new RecordingNotFoundException(
                    String.format("Recording not found with ID=%s and OPCO=%s", id, opco));
        }

        return metadata.getFirst();
    }

    /**
     * Retrieves a single VRS recording from Azure Blob Storage and streams it back as an MP3 audio response.
     *
     * <p>Process flow:
     * <ol>
     *   <li>Validates all fields in the {@link RecordingRequest}</li>
     *   <li>Searches Azure Blob Storage for a matching WAV file using the OPCO, date, and username</li>
     *   <li>Downloads the raw WAV blob content</li>
     *   <li>Converts the WAV data to MP3 format via FFmpeg</li>
     *   <li>Returns the MP3 as a streamable HTTP response with appropriate audio headers</li>
     * </ol>
     *
     * @param request {@link RecordingRequest} containing the username, OPCO, date, and optional metadata filters
     * @return {@link ResponseEntity} containing the MP3 audio bytes with Content-Type {@code audio/mpeg}
     * @throws InvalidRequestException       if the request is null or any required field is missing/invalid
     * @throws RecordingNotFoundException    if no matching recording blob is found in Azure storage
     * @throws RecordingProcessingException  if the blob download or WAV-to-MP3 conversion fails
     */
    public ResponseEntity<ByteArrayResource> getRecordingVpi(RecordingRequest request) {
        logger.info("Retrieving recording for: {}", request.getUsername());

        validateRequest(request);
        RecordingSearchResult blobStatus = findRecordingVPI(request);
        String blobFile = blobStatus.getBlobName();
        byte[] wavData = downloadBlob(resolveAudioBlobName(blobFile));
        byte[] mp3Data = convertWavToMp3(wavData);

        return buildAudioResponse(mp3Data, blobFile);
    }

    /**
     * Bulk-downloads multiple VRS recordings from Azure Blob Storage and packages them into a ZIP archive.
     *
     * <p>Features:
     * <ul>
     *   <li>Processes each recording request independently so a single failure does not abort the batch</li>
     *   <li>Includes a {@code status.json} summary inside the ZIP describing the outcome of every request</li>
     *   <li>Returns HTTP 204 No Content if every recording in the batch fails to resolve</li>
     * </ul>
     *
     * <p>The returned ZIP contains:
     * <ul>
     *   <li>WAV files for every successfully retrieved recording</li>
     *   <li>{@code status.json} with per-recording statuses: SUCCESS, NOT_FOUND, or ERROR</li>
     * </ul>
     *
     * @param requests Non-empty list of {@link RecordingRequest} objects to download
     * @return {@link ResponseEntity} containing the ZIP archive bytes, or 204 No Content if nothing was found
     * @throws InvalidRequestException       if the request list is null or empty
     * @throws RecordingProcessingException  if an unrecoverable error occurs during ZIP stream creation
     */
    public ResponseEntity<byte[]> downloadVpi(List<RecordingRequest> requests) {
        if (requests == null || requests.isEmpty()) {
            throw new InvalidRequestException("Request list cannot be empty");
        }

        int successCount = 0;

        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             ZipOutputStream zos = new ZipOutputStream(baos)) {

            for (RecordingRequest req : requests) {
                RecordingStatus status = addRecordingToZip(req, zos);


                if (STATUS_RECORDING_SUCCESS.equals(status.getStatus())) {
                    successCount++;
                }
            }

            if (successCount == 0) {
                logger.warn("No recordings successfully added to ZIP. Returning no content.");
                return ResponseEntity.noContent().build();
            }

            zos.finish();
            logger.info("ZIP creation successful: {} Files downloaded,", successCount);
            return buildZipResponse(baos.toByteArray());

        } catch (IOException e) {
            throw new RecordingProcessingException("Failed to create ZIP", e);
        }
    }

    /**
     * Searches for VRS recordings across the appropriate OPCO repository using the supplied criteria.
     *
     * <p>Search capabilities:
     * <ul>
     *   <li>Date/time range filtering (UTC-normalised)</li>
     *   <li>OPCO-specific database queries (CMP, RGE, or NYSEG)</li>
     *   <li>Agent/user name matching — names are cleaned and resolved to user IDs before querying</li>
     *   <li>Additional field filters: channel, extension, tags, duration, and call direction</li>
     * </ul>
     *
     * <p>If a name filter is supplied but no matching users are found, an empty page is returned immediately
     * without hitting the capture table.
     *
     * @param from     Inclusive start date/time in UTC
     * @param to       Inclusive end date/time in UTC
     * @param opco     Operating company code — must be one of: RGE, CMP, NYSEG
     * @param filters  Optional {@link VpiFiltersRequest} containing additional field-level filters; may be null
     * @param pageable Spring {@link Pageable} carrying page number, page size, and sort order
     * @return {@link Page} of {@link VpiMetadata} records that match all supplied criteria
     * @throws InvalidRequestException if the OPCO is invalid or its datasource is disabled
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
     * Converts raw WAV audio bytes to MP3 format by piping data through an FFmpeg child process.
     *
     * <p>Conversion specifications:
     * <ul>
     *   <li>Codec: libmp3lame</li>
     *   <li>Bitrate: 128 kbps</li>
     *   <li>Sample rate: 44 100 Hz</li>
     *   <li>Channels: 2 (stereo)</li>
     *   <li>Process timeout: {@value #CONVERSION_TIMEOUT_SECONDS} seconds</li>
     * </ul>
     *
     * <p>stdin, stdout, and stderr are handled asynchronously via {@link CompletableFuture} to prevent
     * pipe-buffer deadlocks. The FFmpeg process is forcibly destroyed in all exit paths via the finally block.
     *
     * @param wavData Raw WAV audio bytes to convert
     * @return MP3-encoded audio bytes
     * @throws InvalidRequestException       if {@code wavData} is null or empty
     * @throws RecordingProcessingException  if FFmpeg exits with a non-zero code, times out, or the JVM is interrupted
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
     * Validates that a {@link VpiSearchRequest} contains all required fields with acceptable values.
     * Checks that fromDate, toDate, and OPCO are present and that the OPCO is one of the allowed values.
     *
     * @param request The VRS search request to validate
     * @throws InvalidRequestException if the request is null or any required field is missing or invalid
     */
    private void validateSearchRequest(VpiSearchRequest request) {
        if (request == null) {
            throw new InvalidRequestException("Search request cannot be null");
        }
        validateRequiredField(request.getFromDate(), "From date");
        validateRequiredField(request.getToDate(), "To date");
        validateRequiredField(request.getOpco(), "OPCO");
        validateOpco(request.getOpco());
    }

    /**
     * Validates that a {@link RecordingRequest} contains all fields required to locate a VRS recording blob.
     * Checks that OPCO and date are both present and that the OPCO is one of the allowed values.
     *
     * @param req The recording retrieval request to validate
     * @throws InvalidRequestException if the request is null or any required field is missing
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
     * Validates an OPCO code and confirms its backing repository is available.
     * The OPCO must be non-blank, match one of the allowed values (RGE, CMP, NYSEG),
     * and its Spring datasource must not be disabled.
     *
     * @param opco The OPCO code to validate
     * @throws InvalidRequestException if the OPCO is blank, unknown, or its datasource bean is null
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
     * Asserts that a string field contains a non-blank value, throwing a descriptive
     * {@link InvalidRequestException} when the field is absent.
     *
     * @param value     The field value to check
     * @param fieldName Human-readable field label used in the exception message
     * @throws InvalidRequestException if {@code value} is null or contains only whitespace
     */
    private void validateRequiredField(String value, String fieldName) {
        if (!StringUtils.hasText(value)) {
            throw new InvalidRequestException(fieldName + " is required");
        }
    }

    /**
     * Checks that the repository for the given OPCO has been wired by Spring.
     * OPCO repositories are optional beans and may be null when a datasource is disabled
     * via application configuration.
     *
     * @param opco The OPCO code whose repository availability should be checked
     * @throws InvalidRequestException if the corresponding repository bean is null (datasource disabled)
     */
    private void assertRepoEnabled(String opco) {
        String upperOpco = opco.toUpperCase();

        if (CMP.equals(upperOpco) && cmpRepo == null) {
            throw new InvalidRequestException("CMP datasource is disabled");
        }
        if (NYSEG.equals(upperOpco) && nysegRepo == null) {
            throw new InvalidRequestException("NYSEG datasource is disabled");
        }
        if (RGE.equals(upperOpco) && rgeRepo == null) {
            throw new InvalidRequestException("RGE datasource is disabled");
        }
    }

    // ========== Date/Time Utility Methods ==========

    /**
     * Parses a date-time string using the VRS standard format {@code yyyy-MM-dd HH:mm:ss}.
     *
     * @param dateStr The date-time string to parse
     * @return Parsed {@link LocalDateTime}
     * @throws InvalidRequestException if the string does not conform to the expected format
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
     * Converts a {@link LocalDateTime} expressed in the America/New_York time zone (EST/EDT)
     * to a UTC {@link OffsetDateTime} for use in database queries.
     * Daylight saving transitions are handled automatically by the zone rules.
     *
     * @param date Local date-time assumed to be in the America/New_York zone
     * @return Equivalent date-time at UTC offset
     */
    public OffsetDateTime convertEstToUtc(LocalDateTime date) {

        ZonedDateTime estZoned = date.atZone(ZoneId.of("America/New_York"));

        ZonedDateTime utcZoned = estZoned.withZoneSameInstant(ZoneOffset.UTC);

        return utcZoned.toOffsetDateTime();
    }

    /**
     * Parses a date-time string using the VRS XML metadata format {@code M/d/yyyy h:mm:ss a}.
     * This format is used in the XML blob files stored in Azure for each OPCO.
     *
     * @param dateStr The XML-format date-time string to parse (e.g., {@code "1/15/2024 3:45:30 PM"})
     * @return Parsed {@link LocalDateTime}
     * @throws InvalidRequestException if the string does not conform to the XML format
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
     * Formats an {@link OffsetDateTime} to the VRS XML start-time string representation
     * ({@code M/d/yyyy h:mm:ss a}, UTC, no sub-second precision).
     * Used when constructing the {@link VpiMetadata} DTO fields {@code startTime} and {@code dateAdded}.
     *
     * @param dateTime The date-time to format; may be at any UTC offset (will be normalised to UTC)
     * @return Formatted XML start-time string, or {@code null} if the input is {@code null}
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
     * Converts a VRS XML start-time string to the file-timestamp format used in Azure blob file names.
     *
     * <p>Example: {@code "1/15/2024 3:45:30 PM"} → {@code "2024-01-15_03-45-30"}
     *
     * <p>The resulting timestamp is matched against the datetime portion of VRS blob file names
     * (characters 5–24 of the file name) when searching Azure Blob Storage for a recording.
     *
     * @param xmlStartTime VRS XML start-time string in {@code M/d/yyyy h:mm:ss a} format
     * @return File-timestamp string in {@code yyyy-MM-dd_HH-mm-ss} format
     * @throws IllegalArgumentException if {@code xmlStartTime} is null, blank, or cannot be parsed
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
     * Builds a Spring {@link Pageable} from the optional {@link PaginationRequest}.
     * Falls back to safe defaults (page 1, size 20, sorted by {@code dateAdded} descending) when
     * the pagination object is null or contains invalid values.
     *
     * @param pagination Optional pagination parameters from the API request; may be null
     * @return {@link Pageable} ready for use in repository queries
     */
    private Pageable createPageable(PaginationRequest pagination) {
        int pageNumber = pagination != null ? pagination.getPageNumber() : MIN_PAGE_NUMBER;
        int requestedPageSize = pagination != null ? pagination.getPageSize() : DEFAULT_PAGE_SIZE;

        int pageSize = requestedPageSize > 0 ? requestedPageSize : DEFAULT_PAGE_SIZE;
        int safePage = Math.max(pageNumber - 1, 0);

        return PageRequest.of(safePage, pageSize, Sort.by("dateAdded").descending());
    }

    /**
     * Assembles a {@link VpiSearchResponse} from a {@link Page} of {@link VpiMetadata} results.
     * Populates both the data list and the pagination metadata (page number, size, totals).
     *
     * @param pageResult Spring {@link Page} returned by the repository search
     * @return Fully populated {@link VpiSearchResponse} ready to serialize to the client
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
     * Sanitises a raw list of name strings by removing null entries, trimming surrounding whitespace,
     * and discarding any entries that are empty after trimming.
     *
     * @param names Raw list of name strings from the filter request; may be null
     * @return Immutable list of non-null, non-empty, trimmed name strings; empty list if input is null
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
     * Normalises a string to lowercase for case-insensitive blob name comparisons.
     * If the value contains a dot, everything from the dot onwards is stripped so that
     * location suffixes such as {@code ". Radio"} or {@code ". Monroe"} do not affect matching.
     *
     * <p>Example: {@code "East Ave UHF 1 Main Ofc. Radio"} → {@code "east ave uhf 1 main ofc"}
     *
     * @param value The raw string to normalise; may be null
     * @return Normalised lowercase string (dot-suffix stripped), or an empty string if the input is null
     */
    private String normalize(String value) {
        if (value == null) return "";
        String lower = value.toLowerCase(Locale.ROOT);
        // If dot present, strip from dot onwards for matching purposes
        // "east ave uhf 1 main ofc. radio" → "east ave uhf 1 main ofc"
        int dotIndex = lower.indexOf('.');
        return dotIndex != -1 ? lower.substring(0, dotIndex).trim() : lower;
    }

    /**
     * Resolves the correct Azure blob name for the audio file associated with a VRS recording.
     *
     * <p>VRS blob file names for some CMP recordings contain a dot inside the customer-name segment
     * (e.g., {@code "...East Ave UHF 4 W. Monroe Radio.wav"}). In those cases the standard
     * {@code .wav} extension must be stripped before the name can be used as a lookup key,
     * because the blob is stored without the trailing {@code .wav}.
     * For all other recordings with a single dot (the extension only) the name is returned as-is.
     *
     * @param blobName Full Azure blob path including the file name
     * @return Resolved blob name: either the original path or the path with the {@code .wav} extension removed
     */
    private String resolveAudioBlobName(String blobName) {
        String fileName = extractFileName(blobName);
        String customerPart = fileName.length() > FILENAME_CUSTOMER_START
                ? fileName.substring(FILENAME_CUSTOMER_START)
                : "";

        long dotCount = customerPart.chars().filter(c -> c == '.').count();

        if (dotCount > 1) {
            // "...East Ave UHF 4 W. Monroe Radio.wav" → strip .wav → "...East Ave UHF 4 W. Monroe Radio"
            return blobName.substring(0, blobName.length() - 4);
        }

        // Normal case — one dot (.wav only) — return as-is
        return blobName;
    }

    /**
     * Returns {@code true} when the supplied string is null or blank after trimming.
     * Used as a guard before performing field-level comparisons in metadata matching.
     *
     * @param value The string to test
     * @return {@code true} if null or blank, {@code false} otherwise
     */
    private boolean isNullOrEmpty(String value) {
        return value == null || value.trim().isEmpty();
    }

    // ========== Recording Search Methods ==========

    /**
     * Locates a VRS recording blob in Azure Blob Storage that matches the supplied {@link RecordingRequest}.
     *
     * <p>Search strategy:
     * <ol>
     *   <li>Derives a day-level blob prefix from the OPCO and recording date</li>
     *   <li>Converts the XML start-time to the file-timestamp format used in blob names</li>
     *   <li>Lists blobs under the prefix and filters by timestamp and customer name</li>
     *   <li>For XML-based OPCOs (NYSEG, RGE) — parses the XML metadata and applies additional
     *       field-level filters (ANI/ALI digits, duration, extension, channel, object ID)</li>
     *   <li>For CMP — searches the {@code Metadata/} sub-folder for XML blobs and also falls back
     *       to a direct WAV blob search when no XML candidates exist</li>
     *   <li>Returns a {@link RecordingSearchResult} pointing to the first matched blob</li>
     * </ol>
     *
     * @param req {@link RecordingRequest} containing OPCO, date, username, and optional metadata filters
     * @return {@link RecordingSearchResult} containing the matched blob path and a multiple-match flag
     * @throws RecordingNotFoundException if no blob matches the request criteria
     */
    private RecordingSearchResult findRecordingVPI(RecordingRequest req) {
        String fileDate = xmlStartTimeToFileTimestamp(req.getDate());
        String prefix = buildDayPrefix(req.getOpco(), parseDateTimeXML(req.getDate()).toLocalDate());
        String normalizedCustomer = normalize(req.getUsername());

        List<String> xmlCandidates = findXmlCandidates(req.getOpco(), prefix, fileDate, normalizedCustomer);

        if (xmlCandidates.isEmpty()) {
            RecordingSearchResult audioFile = new RecordingSearchResult();
            audioFile.setBlobName(CMP.equalsIgnoreCase(req.getOpco())
                    ? findMatchingWavBlobs(prefix + METADATA, fileDate,  normalizedCustomer)
                    : findMatchingWavBlobs( prefix, fileDate, normalizedCustomer));
            return audioFile;
        }

        List<MediaMetadata> matchedMedia = processXmlCandidates(
                xmlCandidates, fileDate, normalizedCustomer, req);

        return buildRecordingResult(matchedMedia, prefix);
    }

    /**
     * Selects the correct XML blob discovery strategy based on the OPCO.
     * CMP recordings store their XML metadata files under a dedicated {@code Metadata/} sub-folder,
     * while NYSEG and RGE XML blobs are located directly under the day-level prefix and must also
     * match the expected timestamp and customer name before being returned.
     *
     * @param opco               Operating company code (CMP, NYSEG, or RGE)
     * @param prefix             Day-level Azure Blob Storage prefix (e.g., {@code "CMP/2024/1/15/"})
     * @param fileDate           File-timestamp string used to filter NYSEG/RGE blob names
     * @param normalizedCustomer Normalised customer/location name used to filter NYSEG/RGE blob names
     * @return List of XML blob paths that are candidates for further metadata parsing
     */
    private List<String> findXmlCandidates(String opco, String prefix,
                                           String fileDate, String normalizedCustomer) {
        return CMP.equalsIgnoreCase(opco)
                ? findCmpXmlBlobs(prefix + METADATA)
                : findMatchingXmlBlobs(prefix, fileDate, normalizedCustomer);
    }


    /**
     * Parses each XML candidate blob, applies timestamp and customer-name filtering for CMP
     * (where a single XML file may contain multiple {@link MediaMetadata} records), and then
     * applies field-level metadata filters from the original request.
     * Only records whose {@code result} field equals {@code "SUCCESS"} are retained.
     *
     * @param xmlCandidates      List of XML blob paths to parse
     * @param fileDate           Expected file-timestamp string for secondary filtering
     * @param normalizedCustomer Normalised customer name for secondary filtering
     * @param req                Original {@link RecordingRequest} supplying optional field-level filters
     * @return Non-empty list of {@link MediaMetadata} records that matched all criteria
     * @throws RecordingNotFoundException if parsing yields no match, with a context-specific message
     *                                    distinguishing between a metadata mismatch and a missing audio file
     */
    private List<MediaMetadata> processXmlCandidates(List<String> xmlCandidates,
                                                     String fileDate,
                                                     String normalizedCustomer,
                                                     RecordingRequest req) {

        AtomicBoolean metadataFoundButNoMatch = new AtomicBoolean(false);

        List<MediaMetadata> matchedMedia = xmlCandidates.stream()

                // Step 1: Parse XML → List<MediaMetadata>
                .flatMap(xmlBlob -> getFilteredMedia(xmlBlob, fileDate, normalizedCustomer).stream())

                // Step 2: Match metadata
                .filter(media -> {
                    boolean matches = matchesMetadata(
                            media,req);

                    if (matches) {
                        metadataFoundButNoMatch.set(true);
                    }

                    return matches && STATUS_RECORDING_SUCCESS.equals(media.getResult());
                })

                .toList();

        validateMatchedMedia(matchedMedia, metadataFoundButNoMatch.get());
        return matchedMedia;
    }

    /**
     * Parses a single XML blob and returns the relevant {@link MediaMetadata} records.
     * When the XML contains more than one record (CMP multi-record XMLs), the list is
     * narrowed down to those whose file name matches the expected timestamp and customer name.
     * For single-record XMLs (NYSEG, RGE) the parsed result is returned as-is.
     *
     * @param xmlBlob            Azure blob path of the XML file to parse
     * @param fileDate           File-timestamp string used for CMP multi-record filtering
     * @param normalizedCustomer Normalised customer name used for CMP multi-record filtering
     * @return Filtered list of {@link MediaMetadata} extracted from the XML blob
     */
    private List<MediaMetadata> getFilteredMedia(String xmlBlob,
                                                 String fileDate,
                                                 String normalizedCustomer) {

        List<MediaMetadata> metaMatch = parseXml(xmlBlob);

        if (metaMatch.size() > 1) {
            return findMatchingMediaCMP(metaMatch, fileDate, normalizedCustomer);
        }

        return metaMatch;
    }

    /**
     * Validates that the matched-media list is non-empty and throws a meaningful
     * {@link RecordingNotFoundException} when it is empty.
     * The {@code metadataFoundButNoMatch} flag distinguishes between two failure modes:
     * <ul>
     *   <li>{@code true} — metadata was found and passed field filters but the audio result was not SUCCESS
     *       (i.e., the recording was not migrated to Azure Blob Storage)</li>
     *   <li>{@code false} — the XML was found but no record matched the timestamp/customer/field filters</li>
     * </ul>
     *
     * @param matchedMedia             List of successfully matched {@link MediaMetadata} records
     * @param metadataFoundButNoMatch  {@code true} if metadata matched but audio result was not SUCCESS
     * @throws RecordingNotFoundException if {@code matchedMedia} is empty
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
     * Constructs a {@link RecordingSearchResult} from the first entry in the matched-media list.
     * If more than one record matched, a warning is logged and the first match is used.
     *
     * @param matchedMedia Non-empty list of matched {@link MediaMetadata} records
     * @param prefix       Day-level Azure Blob Storage prefix used to build the full blob path
     * @return {@link RecordingSearchResult} with the resolved blob path and multiple-match flag set accordingly
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
     * Lists all XML blobs under the CMP {@code Metadata/} sub-folder prefix.
     * CMP stores one or more recording metadata records inside XML files within a dedicated
     * metadata directory rather than alongside the audio files.
     *
     * @param dayPrefix Full Azure Blob Storage prefix including the {@code Metadata/} segment
     * @return List of blob paths whose names end with {@code .xml}
     */
    private List<String> findCmpXmlBlobs(String dayPrefix) {
        List<String> blobs = vpiAzureRepository.listBlobs(dayPrefix);
        return blobs.stream()
                .filter(blob -> blob.toLowerCase(Locale.ROOT).endsWith(".xml"))
                .toList();
    }

    /**
     * Lists XML blobs under the given prefix and filters them by both timestamp and customer name.
     * Used for NYSEG and RGE OPCOs where XML blobs are co-located with audio files and named
     * using the same {@code <prefix><timestamp><customer>.xml} convention.
     *
     * @param prefix             Day-level Azure Blob Storage prefix
     * @param expectedDateTime   File-timestamp string that the blob name must contain
     * @param normalizedCustomer Normalised customer/location name that the blob name must contain
     * @return List of XML blob paths that satisfy both the timestamp and customer-name constraints
     */
    private List<String> findMatchingXmlBlobs(String prefix,
                                              String expectedDateTime,
                                              String normalizedCustomer) {
        List<String> blobs = vpiAzureRepository.listBlobs(prefix);
        List<String> matchedXmls = new ArrayList<>();
        for (String blobName : blobs) {
            if (blobName.endsWith(".xml")
                    && matchesTimestamp(blobName, expectedDateTime)
                    && matchesCustomer(blobName, normalizedCustomer)) {
                matchedXmls.add(blobName);
            }
        }
        return matchedXmls;
    }

    /**
     * Scans WAV blobs under the given prefix and returns the first one that matches
     * both the expected timestamp and the normalised customer name.
     * This is the fallback path used when no XML metadata blob could be located for a recording
     * (e.g., CMP recordings where the XML is absent from the Metadata folder).
     *
     * @param prefix             Azure Blob Storage prefix to list blobs under
     * @param expectedDateTime   File-timestamp string that the blob name must contain
     * @param normalizedCustomer Normalised customer/location name that the blob name must contain
     * @return Full blob path of the first matching WAV file
     * @throws RecordingNotFoundException if no WAV blob under the prefix satisfies the constraints
     */
    private String findMatchingWavBlobs(String prefix,
                                        String expectedDateTime,
                                        String normalizedCustomer) {
        List<String> blobs = vpiAzureRepository.listBlobs(prefix);
        for (String blobName : blobs) {
            if (blobName.endsWith(WAV_EXTENSION)
                    && matchesTimestamp(blobName, expectedDateTime)
                    && matchesCustomer(blobName, normalizedCustomer)) {
                return blobName;
            }
        }
        throw new RecordingNotFoundException("No Recordings found");
    }



    /**
     * Filters a list of CMP {@link MediaMetadata} records down to those whose {@code fileName}
     * matches both the expected file-timestamp and the normalised customer name.
     * CMP XML files can contain multiple recording entries, so this method selects
     * only the entries that correspond to the specific recording being requested.
     *
     * @param metadataList       Full list of {@link MediaMetadata} parsed from a CMP XML blob; may be null or empty
     * @param expectedDateTime   File-timestamp string that each record's file name must contain
     * @param normalizedCustomer Normalised customer/location name that each record's file name must contain
     * @return Filtered list of valid {@link MediaMetadata} records; empty list if input is null or empty
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
     * Validates a single {@link MediaMetadata} file name against the expected timestamp and customer name.
     * Logs a debug message when either condition fails to assist with troubleshooting blob mismatches.
     *
     * @param fileName           The file name from a {@link MediaMetadata} record to validate
     * @param expectedDateTime   File-timestamp string the name must contain
     * @param normalizedCustomer Normalised customer name the name must contain
     * @return {@code true} if the file name satisfies both constraints; {@code false} otherwise
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
     * Downloads an XML blob from Azure Blob Storage and parses it into a list of {@link MediaMetadata} records.
     * The blob content is read entirely into memory before parsing.
     *
     * @param blobName Full Azure blob path of the XML file to parse
     * @return List of {@link MediaMetadata} records extracted from the XML; may be empty
     * @throws RecordingProcessingException if the blob cannot be downloaded or the XML stream cannot be closed
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
     * Delegates XML parsing to {@link XmlMediaParser} and logs summary statistics about the result.
     * Warns when the parsed list contains records that fail the {@link MediaMetadata#isValid()} check,
     * which indicates incomplete or malformed entries in the VRS XML metadata file.
     *
     * @param xmlStream {@link InputStream} of the VRS XML blob content
     * @return List of {@link MediaMetadata} records extracted from the stream
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
     * Checks whether a {@link MediaMetadata} record satisfies all optional field-level filters
     * specified in the {@link RecordingRequest}.
     * Null or empty filter values are treated as wildcards and always match.
     * Returns {@code false} immediately on the first failing check (short-circuit evaluation).
     *
     * <p>Evaluated fields (all optional):
     * <ul>
     *   <li>{@code aniAliDigits} — ANI/ALI digit string</li>
     *   <li>{@code duration} — call duration in seconds</li>
     *   <li>{@code extensionNum} — agent extension number</li>
     *   <li>{@code channelNum} — recording channel number</li>
     *   <li>{@code objectID} — VRS object identifier</li>
     * </ul>
     *
     * @param metadata The {@link MediaMetadata} record to evaluate
     * @param req      The original {@link RecordingRequest} carrying the filter values
     * @return {@code true} if all non-null filter fields match the metadata; {@code false} otherwise
     */
    private boolean matchesMetadata(MediaMetadata metadata,
                                    RecordingRequest req) {

        if (metadata == null || metadata.getFields() == null) {
            return false;
        }

        Map<String, String> fields = metadata.getFields();

        if (!matchesStringField(fields, "aniAliDigits", req.getAniAliDigits())) {
            return false;
        }

        if (!matchesIntegerField(fields, "duration", req.getDuration())) {
            return false;
        }

        if (!matchesStringField(fields, "extensionNum", req.getExtensionNum())) {
            return false;
        }

        if (!matchesIntegerField(fields, "channelNum", req.getChannelNum())) {
            return false;
        }

        if (!matchesStringField(fields, "objectID", req.getObjectId())){
            return false;
        };
        return true;
    }

    /**
     * Evaluates whether a string field in the metadata map matches the expected value.
     * When the expected value is null or blank it is treated as a wildcard and {@code true} is returned.
     * When the actual value is absent from the map, {@code true} is also returned (permissive matching).
     *
     * @param fields        Metadata fields map from a {@link MediaMetadata} record
     * @param fieldName     Key of the field to look up in the map
     * @param expectedValue The value to match against; null or blank acts as a wildcard
     * @return {@code true} if the actual value equals the expected value, or if either is absent/wildcard
     */
    private boolean matchesStringField(Map<String, String> fields, String fieldName, String expectedValue) {
        if (isNullOrEmpty(expectedValue)) {
            return true;
        }
        String actualValue = fields.get(fieldName);
        if (actualValue == null) {
            return true;
        }
        return expectedValue.equals(actualValue);
    }

    /**
     * Evaluates whether an integer field in the metadata map matches the expected value.
     * When the expected value is null it is treated as a wildcard and {@code true} is returned.
     * When the actual value is absent from the map or cannot be parsed as an integer,
     * {@code true} is returned (permissive matching — a parse failure is logged at DEBUG level).
     *
     * @param fields        Metadata fields map from a {@link MediaMetadata} record
     * @param fieldName     Key of the field to look up in the map
     * @param expectedValue The integer value to match against; null acts as a wildcard
     * @return {@code true} if the parsed actual value equals the expected value, or if either is absent/wildcard
     */
    private boolean matchesIntegerField(Map<String, String> fields,
                                        String fieldName,
                                        Integer expectedValue) {
        if (expectedValue == null) {
            return true;
        }
        String actualValue = fields.get(fieldName);
        if (actualValue == null) {
            return true;
        }
        try {
            return expectedValue.equals(Integer.valueOf(actualValue.trim()));
        } catch (NumberFormatException ex) {
            logger.debug("Invalid integer value for field {} : {}", fieldName, actualValue);
            return true;
        }
    }

    /**
     * Checks whether the datetime portion of a blob file name matches the expected file-timestamp string.
     * The datetime is extracted from characters {@value #FILENAME_DATETIME_START}–{@value #FILENAME_DATETIME_END}
     * of the file name.
     *
     * @param blobName Full Azure blob path containing the file name to inspect
     * @param expected File-timestamp string to compare against (e.g., {@code "2024-01-15_03-45-30"})
     * @return {@code true} if the extracted datetime equals {@code expected}; {@code false} if extraction fails
     */
    private boolean matchesTimestamp(String blobName, String expected) {
        return extractDateTime(blobName)
                .map(actual -> actual.equals(expected))
                .orElse(false);
    }

    /**
     * Checks whether the customer/location segment of a blob file name matches the expected normalised name.
     * The customer name is extracted from character {@value #FILENAME_CUSTOMER_START} to the start of
     * the file extension. Both values are normalised (lowercased, dot-suffix stripped) before comparison.
     *
     * @param blobName           Full Azure blob path containing the file name to inspect
     * @param normalizedCustomer Normalised customer/location name to compare against
     * @return {@code true} if the normalised extracted name equals {@code normalizedCustomer};
     *         {@code false} if extraction fails or names differ
     */
    private boolean matchesCustomer(String blobName, String normalizedCustomer) {
        return extractCustomerName(blobName)
                .map(this::normalize)
                .map(name -> name.equals(normalizedCustomer))
                .orElse(false);
    }

    // ========== Blob Path Methods ==========

    /**
     * Builds the day-level Azure Blob Storage prefix for a given OPCO and date.
     * The prefix follows the VRS container path convention: {@code <OPCO>/<year>/<month>/<day>/}.
     *
     * @param opco The OPCO code (RGE, CMP, or NYSEG) — converted to uppercase
     * @param date The recording date whose year, month, and day components form the path segments
     * @return Formatted prefix string (e.g., {@code "RGE/2024/1/15/"})
     */
    private String buildDayPrefix(String opco, LocalDate date) {
        return String.format("%s/%d/%d/%d/",
                opco.toUpperCase(Locale.ROOT),
                date.getYear(),
                date.getMonthValue(),
                date.getDayOfMonth());
    }

    /**
     * Downloads the full content of an Azure blob into a byte array.
     * Wraps any underlying exception in a {@link RecordingProcessingException} with a descriptive message.
     *
     * @param blobName Full Azure blob path to download
     * @return Byte array containing the blob content
     * @throws RecordingProcessingException if the download fails for any reason
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
     * Extracts the datetime segment from a VRS blob file name.
     * VRS file names follow the convention {@code XXXXX<datetime><customer>.<ext>} where
     * the datetime occupies characters {@value #FILENAME_DATETIME_START} to {@value #FILENAME_DATETIME_END}.
     *
     * @param blobName Full Azure blob path
     * @return {@link Optional} containing the datetime substring, or empty if extraction fails
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
     * Extracts the customer/location name segment from a VRS blob file name.
     * The customer name occupies characters from {@value #FILENAME_CUSTOMER_START} to the
     * beginning of the file extension ({@code .xml} or {@code .wav}).
     *
     * @param blobName Full Azure blob path
     * @return {@link Optional} containing the raw customer-name substring,
     *         or empty if extraction fails or the file name is too short
     */
    private Optional<String> extractCustomerName(String blobName) {
        try {
            String fileName = extractFileName(blobName);

            // Find end of customer name by stripping whatever extension is present
            int endIndex;
            String lower = fileName.toLowerCase(Locale.ROOT);
            if (lower.endsWith(".xml")) {
                endIndex = fileName.length() - 4;
            } else if (lower.endsWith(".wav")) {
                endIndex = fileName.length() - 4;
            } else {
                return Optional.empty();
            }

            if (fileName.length() < FILENAME_CUSTOMER_START || endIndex < FILENAME_CUSTOMER_START) {
                return Optional.empty();
            }

            return Optional.of(fileName.substring(FILENAME_CUSTOMER_START, endIndex));

        } catch (Exception ex) {
            logger.debug("Failed to extract customer name from blob: {}", blobName, ex);
            return Optional.empty();
        }
    }

    /**
     * Extracts only the file name portion from a full Azure blob path by trimming everything
     * up to and including the last {@code /} separator.
     * Used as the basis for all subsequent file-name parsing operations.
     *
     * @param blobName Full Azure blob path (e.g., {@code "RGE/2024/1/15/XXXXX2024-01-15_03-45-30Customer.wav"})
     * @return File name only (e.g., {@code "XXXXX2024-01-15_03-45-30Customer.wav"})
     */
    private String audioName(String blobName) {
        return blobName.substring(blobName.lastIndexOf('/') + 1);
    }

    /**
     * Extracts the file name from a full Azure blob path.
     * Equivalent to {@link #audioName(String)} and used internally by timestamp and customer extraction helpers.
     *
     * @param blobName Full Azure blob path
     * @return File name portion after the last {@code /} character
     */
    private String extractFileName(String blobName) {
        return blobName.substring(blobName.lastIndexOf('/') + 1);
    }

    // ========== FFmpeg Conversion Helper Methods ==========

    /**
     * Builds the FFmpeg command-line argument array for WAV-to-MP3 conversion.
     * Reads from {@code pipe:0} (stdin) and writes to {@code pipe:1} (stdout) to avoid
     * temporary file creation. Suppresses the FFmpeg banner and limits log output to warnings.
     *
     * @return String array containing the FFmpeg executable path and all conversion arguments
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
     * Creates and starts the FFmpeg {@link Process} for WAV-to-MP3 conversion.
     * When using the absolute path {@code /usr/bin/ffmpeg} (production environment),
     * the child process environment is sanitised to only expose {@code /usr/bin:/bin}
     * to reduce the attack surface.
     *
     * @return Started {@link Process} instance ready for stdin/stdout piping
     * @throws IOException if the operating system cannot create the child process
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
     * Asynchronously drains the FFmpeg process stderr stream into a single string.
     * Must be consumed concurrently with stdin writing and stdout reading to prevent
     * the stderr pipe buffer from blocking the process.
     *
     * @param process The running FFmpeg {@link Process}
     * @return {@link CompletableFuture} that resolves to the full stderr output (may be empty)
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
     * Asynchronously writes the WAV byte array to the FFmpeg process stdin and flushes the stream.
     * The stream is closed after writing so that FFmpeg receives an EOF signal and begins producing output.
     *
     * @param process The running FFmpeg {@link Process}
     * @param wavData Raw WAV audio bytes to pipe into FFmpeg
     * @return {@link CompletableFuture} that completes when all bytes have been written and the stream is closed
     */
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

    /**
     * Asynchronously reads the FFmpeg process stdout into a byte array containing the converted MP3 data.
     * Reads in {@value #BUFFER_SIZE}-byte chunks until EOF is signalled by FFmpeg after completing conversion.
     *
     * @param process The running FFmpeg {@link Process}
     * @return {@link CompletableFuture} that resolves to the full MP3 byte array once stdout is exhausted
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
     * Checks the FFmpeg process exit code and logs any warnings emitted to stderr.
     * A non-zero exit code indicates that the conversion failed and triggers a
     * {@link RecordingProcessingException} containing the exit code and stderr output.
     * On success, logs the input WAV size and output MP3 size for auditing.
     *
     * @param exitCode   FFmpeg process exit code (0 = success)
     * @param errors     Captured FFmpeg stderr output; logged as a warning when non-empty on success
     * @param inputSize  Size of the input WAV data in bytes (logged on success)
     * @param outputSize Size of the output MP3 data in bytes (logged on success)
     * @throws RecordingProcessingException if {@code exitCode} is non-zero
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
     * Forcibly terminates the FFmpeg child process if it is still running.
     * Called in all exit paths (success, error, and timeout) via the finally block
     * in {@link #convertWavToMp3(byte[])} to prevent zombie processes.
     *
     * @param process The {@link Process} to destroy; no-op if null or already terminated
     */
    private void destroyProcess(Process process) {
        if (process != null && process.isAlive()) {
            process.destroyForcibly();
            logger.debug("FFmpeg process destroyed");
        }
    }

    // ========== Response Building Methods ==========

    /**
     * Builds an HTTP {@link ResponseEntity} for streaming a single VRS recording as MP3 audio.
     * Sets Content-Type to {@code audio/mpeg}, Content-Disposition to {@code inline}, and
     * Content-Length to allow the browser or client to display a progress indicator.
     * The output file name is derived from the original blob name with the extension changed to {@code .mp3}.
     *
     * @param mp3Data          MP3 audio bytes to include in the response body
     * @param originalFilename Original blob file name (used to derive the MP3 download name)
     * @return HTTP 200 OK {@link ResponseEntity} with the MP3 bytes and audio headers
     */
    private ResponseEntity<ByteArrayResource> buildAudioResponse(byte[] mp3Data, String originalFilename) {
        String mp3Filename = audioName(originalFilename);
        mp3Filename = mp3Filename.substring(0, mp3Filename.lastIndexOf('.'))+ MP3_EXTENSION;

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
     * Builds an HTTP {@link ResponseEntity} for downloading a ZIP archive of VRS recordings.
     * Sets Content-Type to {@code application/octet-stream} and Content-Disposition to
     * {@code attachment; filename="recordings.zip"} so the browser prompts a file save dialog.
     *
     * @param zipData Byte array containing the fully assembled ZIP archive
     * @return HTTP 200 OK {@link ResponseEntity} with the ZIP bytes and download headers
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
     * Processes a single {@link RecordingRequest} and writes the matching VRS audio blob as a ZIP entry.
     * Handles each failure mode independently so the bulk download can continue when individual
     * recordings are missing or encounter errors.
     *
     * <p>Outcome codes:
     * <ul>
     *   <li>{@code SUCCESS} — blob found and written to the ZIP stream</li>
     *   <li>{@code NOT_FOUND} — no matching blob in Azure storage</li>
     *   <li>{@code ERROR} — unexpected I/O or processing failure</li>
     * </ul>
     *
     * @param req The individual VRS recording request to process
     * @param zos The open {@link ZipOutputStream} to write the audio entry into
     * @return {@link RecordingStatus} describing the outcome for this request
     */
    private RecordingStatus addRecordingToZip(RecordingRequest req, ZipOutputStream zos) {
        validateRequest(req);
        String blobName = null;

        try {
            blobName = resolveAudioBlobName(findRecordingVPI(req).getBlobName());

            if (blobName.isEmpty()) {
                logger.warn("No matching blob found for user={} date={}", req.getUsername(), req.getDate());
                return createNotFoundStatus(req);
            }

            addBlobToZip(blobName, zos);
            logger.info("Successfully added recording to ZIP: user={} date={} blob={}",
                    req.getUsername(), req.getDate(), blobName);
            return createSuccessStatus(req, blobName);

        } catch (RecordingNotFoundException e) {
            logger.warn("Recording not found for user={} date={}: {}",
                    req.getUsername(), req.getDate(), e.getMessage());
            return createNotFoundStatus(req);

        } catch (IOException e) {
            logger.error("IO error while adding recording to ZIP: user={} date={} blob={}",
                    req.getUsername(), req.getDate(), blobName, e);
            return createErrorStatus(req, blobName, "Failed to write recording to ZIP: " + e.getMessage());

        } catch (Exception e) {
            logger.error("Unexpected error while processing recording: user={} date={} blob={}",
                    req.getUsername(), req.getDate(), blobName, e);
            return createErrorStatus(req, blobName, "Failed to process recording: " + e.getMessage());
        }
    }

    /**
     * Creates a {@link RecordingStatus} representing a not-found outcome for a VRS recording request.
     * Used when no matching audio blob could be located in Azure Blob Storage.
     *
     * @param req The recording request that could not be fulfilled
     * @return {@link RecordingStatus} with status {@code NOT_FOUND} and a descriptive message
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
     * Creates a {@link RecordingStatus} representing a successful ZIP entry addition for a VRS recording.
     *
     * @param req          The recording request that was fulfilled
     * @param zipEntryName The blob path used as the ZIP entry name
     * @return {@link RecordingStatus} with status {@code SUCCESS} and no error message
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
     * Creates a {@link RecordingStatus} representing a processing error for a VRS recording request.
     * Used when an unexpected exception occurs during blob retrieval or ZIP stream writing.
     *
     * @param req          The recording request that encountered an error
     * @param zipEntryName The blob path that was being processed when the error occurred (may be null)
     * @param errorMessage Human-readable description of the error
     * @return {@link RecordingStatus} with status {@code ERROR} and the supplied error message
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
     * Streams a single Azure blob into the ZIP output stream as a new entry.
     * Opens the blob via a streaming API to avoid loading the entire file into memory.
     * Ensures the ZIP entry is closed in the finally block even when an I/O error occurs mid-stream.
     *
     * @param blobName Full Azure blob path of the audio file to add
     * @param zos      The open {@link ZipOutputStream} to write the entry into
     * @throws IOException if reading the blob stream or writing to the ZIP stream fails
     */
    private void addBlobToZip(String blobName, ZipOutputStream zos) throws IOException {
        zos.putNextEntry(new ZipEntry(audioName(blobName)));
        try (InputStream blobStream = vpiAzureRepository.getBlobStream(blobName)) {
            StreamUtils.copy(blobStream, zos);
        } finally {
            zos.closeEntry();
        }
    }

    // ========== Database Search Methods ==========

    /**
     * Dispatches a VRS recording search to the repository of the requested OPCO.
     * Delegates to the appropriate OPCO-specific search method (CMP, NYSEG, or RGE),
     * each of which builds a JPA {@link Specification} and calls the corresponding repository.
     *
     * @param from     Inclusive start date/time in UTC
     * @param to       Inclusive end date/time in UTC
     * @param opco     OPCO code determining which repository is queried
     * @param filters  Optional {@link VpiFiltersRequest} with additional field-level filters; may be null
     * @param userIds  Set of user UUIDs pre-resolved from name filters; empty when no name filter is active
     * @param pageable Spring {@link Pageable} carrying page number, size, and sort order
     * @return {@link Page} of {@link VpiMetadata} records matching all criteria
     * @throws InvalidRequestException if the OPCO is not one of the allowed values
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
            case CMP -> searchCmp(from, to, filters, userIds, pageable);
            case NYSEG -> searchNyseg(from, to, filters, userIds, pageable);
            case RGE -> searchRge(from, to, filters, userIds, pageable);
            default -> throw new InvalidRequestException("Invalid OPCO code: " + opco);
        };
    }

    /**
     * Executes a paginated VRS recording search against the CMP repository.
     * Builds a JPA {@link Specification} via {@link CaptureSpecifications#build} and delegates
     * to the CMP repository, then enriches results with agent names and maps to DTOs.
     *
     * @param from     Inclusive start date/time in UTC
     * @param to       Inclusive end date/time in UTC
     * @param filters  Optional field-level filters; may be null
     * @param userIds  Pre-resolved CMP user IDs from name filters; empty when no name filter is active
     * @param pageable Pagination and sort settings
     * @return Page of enriched {@link VpiMetadata} records for the CMP OPCO
     */
    private Page<VpiMetadata> searchCmp(
            OffsetDateTime from,
            OffsetDateTime to,
            VpiFiltersRequest filters,
            Set<UUID> userIds,
            Pageable pageable) {

        Specification<VpiCaptureCmp> spec = CaptureSpecifications.build(from, to, filters, userIds);
        Page<VpiCaptureCmp> page = cmpRepo.findAll(spec, pageable);
        return enrichAndMap(page, CMP);
    }

    /**
     * Executes a paginated VRS recording search against the NYSEG repository.
     * Builds a JPA {@link Specification} via {@link CaptureSpecifications#build} and delegates
     * to the NYSEG repository, then enriches results with agent names and maps to DTOs.
     *
     * @param from     Inclusive start date/time in UTC
     * @param to       Inclusive end date/time in UTC
     * @param filters  Optional field-level filters; may be null
     * @param userIds  Pre-resolved NYSEG user IDs from name filters; empty when no name filter is active
     * @param pageable Pagination and sort settings
     * @return Page of enriched {@link VpiMetadata} records for the NYSEG OPCO
     */
    private Page<VpiMetadata> searchNyseg(
            OffsetDateTime from,
            OffsetDateTime to,
            VpiFiltersRequest filters,
            Set<UUID> userIds,
            Pageable pageable) {

        Specification<VpiCaptureNyseg> spec = CaptureSpecifications.build(from, to, filters, userIds);
        Page<VpiCaptureNyseg> page = nysegRepo.findAll(spec, pageable);
        return enrichAndMap(page, NYSEG);
    }

    /**
     * Executes a paginated VRS recording search against the RGE repository.
     * Builds a JPA {@link Specification} via {@link CaptureSpecifications#build} and delegates
     * to the RGE repository, then enriches results with agent names and maps to DTOs.
     *
     * @param from     Inclusive start date/time in UTC
     * @param to       Inclusive end date/time in UTC
     * @param filters  Optional field-level filters; may be null
     * @param userIds  Pre-resolved RGE user IDs from name filters; empty when no name filter is active
     * @param pageable Pagination and sort settings
     * @return Page of enriched {@link VpiMetadata} records for the RGE OPCO
     */
    private Page<VpiMetadata> searchRge(
            OffsetDateTime from,
            OffsetDateTime to,
            VpiFiltersRequest filters,
            Set<UUID> userIds,
            Pageable pageable) {

        Specification<VpiCaptureRge> spec = CaptureSpecifications.build(from, to, filters, userIds);
        Page<VpiCaptureRge> page = rgeRepo.findAll(spec, pageable);
        return enrichAndMap(page, RGE);
    }

    // ========== User Management Methods ==========

    /**
     * Queries the OPCO-specific user repository for all user IDs whose full name contains
     * any of the supplied name fragments. Results are used to filter VRS capture records by agent.
     *
     * @param opco  OPCO code determining which user repository is queried
     * @param names Non-empty list of cleaned name fragments to match against
     * @return Set of user UUIDs whose full name matches at least one fragment; empty set if none found
     */
    private Set<UUID> fetchMatchedUserIds(String opco, List<String> names) {
        if (names == null || names.isEmpty()) {
            return Collections.emptySet();
        }

        String upperOpco = opco.toUpperCase();
        String[] namesArray = names.toArray(new String[0]);

        return switch (upperOpco) {
            case CMP -> new HashSet<>(cmpUserRepo.findUserIdsByFullNameContainsAny(namesArray));
            case NYSEG -> new HashSet<>(nysegUserRepo.findUserIdsByFullNameContainsAny(namesArray));
            case RGE -> new HashSet<>(rgeUserRepo.findUserIdsByFullNameContainsAny(namesArray));
            default -> Collections.emptySet();
        };
    }

    /**
     * Fetches the full names of all users identified by the supplied IDs from the OPCO-specific user repository.
     * The resulting map is used to populate the {@code username} field on {@link VpiMetadata} DTOs.
     *
     * @param opco    OPCO code determining which user repository is queried
     * @param userIds Set of user UUIDs to look up; returns an empty map when the set is empty
     * @return Map of user UUID to full name for all found users
     */
    private Map<UUID, String> fetchUserNames(String opco, Set<UUID> userIds) {
        if (userIds.isEmpty()) {
            return Collections.emptyMap();
        }

        String upperOpco = opco.toUpperCase();

        return switch (upperOpco) {
            case CMP -> buildUserNameMap(
                    cmpUserRepo.findByUserIdIn(userIds),
                    VpiUsersCmp::getUserId,
                    VpiUsersCmp::getFullName);
            case NYSEG -> buildUserNameMap(
                    nysegUserRepo.findByUserIdIn(userIds),
                    VpiUsersNyseg::getUserId,
                    VpiUsersNyseg::getFullName);
            case RGE -> buildUserNameMap(
                    rgeUserRepo.findByUserIdIn(userIds),
                    VpiUsersRge::getUserId,
                    VpiUsersRge::getFullName);
            default -> Collections.emptyMap();
        };
    }

    /**
     * Converts a list of OPCO user entities into a {@link Map} keyed by user UUID.
     * Generic helper used by {@link #fetchUserNames} for all three OPCOs to avoid duplication.
     *
     * @param users         List of user entities of any OPCO type
     * @param idExtractor   Function that extracts the UUID from a user entity
     * @param nameExtractor Function that extracts the full name from a user entity
     * @param <T>           The OPCO-specific user entity type (e.g., {@link VpiUsersCmp})
     * @return Map of user UUID to full name; empty map if {@code users} is empty
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
     * Retrieves the full VRS capture record(s) for the given object ID from the specified OPCO repository
     * and converts each to a flat metadata map.
     *
     * @param id   UUID of the VRS recording object to look up
     * @param opco OPCO code determining which repository is queried
     * @return List of metadata maps (typically one entry); empty list if no record is found
     * @throws InvalidRequestException if the OPCO is not one of the allowed values
     */
    private List<Map<String, Object>> getMetadataByOpco(UUID id, String opco) {
        String upperOpco = opco.toUpperCase();

        return switch (upperOpco) {
            case CMP -> metadataFull(cmpRepo.findByObjectId(id));
            case NYSEG -> metadataFull(nysegRepo.findByObjectId(id));
            case RGE -> metadataFull(rgeRepo.findByObjectId(id));
            default -> throw new InvalidRequestException("Invalid OPCO code: " + opco);
        };
    }

    /**
     * Converts a list of {@link VpiCaptureView} entities to a list of flat metadata maps.
     * Each map is built by {@link #buildMetadataMap} and contains all available VRS recording fields.
     *
     * @param recordings List of VRS capture entities (any OPCO); may be empty
     * @return Immutable list of metadata maps, one per recording entity
     */
    private List<Map<String, Object>> metadataFull(List<? extends VpiCaptureView> recordings) {
        return recordings.stream()
                .map(this::buildMetadataMap)
                .toList();
    }

    /**
     * Assembles the complete flat metadata map for a single VRS recording entity.
     * Delegates field grouping to dedicated helper methods to keep the method concise
     * and to make it easy to add or remove field groups independently.
     *
     * @param rec The {@link VpiCaptureView} entity representing one VRS recording
     * @return {@link LinkedHashMap} containing all metadata fields in insertion order
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
     * Adds object and resource identifier fields to the metadata map.
     * Includes: {@code objectId}, {@code dateAdded}, {@code resourceId}, {@code workstationId}, {@code userId}.
     *
     * @param map Map to populate
     * @param rec Source VRS capture entity
     */
    private void addIdentifierFields(Map<String, Object> map, VpiCaptureView rec) {
        map.put("objectId", rec.getObjectId());
        map.put("dateAdded", rec.getDateAdded());
        map.put("resourceId", rec.getResourceId());
        map.put("workstationId", rec.getWorkstationId());
        map.put("userId", rec.getUserId());
    }

    /**
     * Adds call timing fields to the metadata map.
     * Includes: {@code startTime}, {@code gmtOffset}, {@code gmtStartTime}, {@code duration}.
     *
     * @param map Map to populate
     * @param rec Source VRS capture entity
     */
    private void addTimingFields(Map<String, Object> map, VpiCaptureView rec) {
        map.put("startTime", rec.getStartTime());
        map.put("gmtOffset", rec.getGmtOffset());
        map.put("gmtStartTime", rec.getGmtStartTime());
        map.put("duration", rec.getDuration());
    }

    /**
     * Adds trigger, flag, tag, and sensitivity fields to the metadata map.
     * Includes: {@code triggeredByResourceTypeId}, {@code triggeredByObjectId},
     * {@code flagId}, {@code tags}, {@code sensitivityLevel}, {@code clientId}.
     *
     * @param map Map to populate
     * @param rec Source VRS capture entity
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
     * Adds channel, agent, and telephony fields to the metadata map.
     * Includes: {@code channelNum}, {@code channelName}, {@code extensionNum}, {@code agentId},
     * {@code pbxDnis}, {@code anialidigits}, {@code direction}.
     *
     * @param map Map to populate
     * @param rec Source VRS capture entity
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
     * Adds media storage fields to the metadata map.
     * Includes: {@code mediaFileId}, {@code mediaManagerId}, {@code mediaRetention}.
     *
     * @param map Map to populate
     * @param rec Source VRS capture entity
     */
    private void addMediaFields(Map<String, Object> map, VpiCaptureView rec) {
        map.put("mediaFileId", rec.getMediaFileId());
        map.put("mediaManagerId", rec.getMediaManagerId());
        map.put("mediaRetention", rec.getMediaRetention());
    }

    /**
     * Adds call identifier fields to the metadata map.
     * Includes: {@code callId}, {@code previousCallId}, {@code globalCallId}.
     *
     * @param map Map to populate
     * @param rec Source VRS capture entity
     */
    private void addCallIdFields(Map<String, Object> map, VpiCaptureView rec) {
        map.put("callId", rec.getCallId());
        map.put("previousCallId", rec.getPreviousCallId());
        map.put("globalCallId", rec.getGlobalCallId());
    }

    /**
     * Adds service classification and platform reference fields to the metadata map.
     * Includes: {@code classOfService}, {@code classOfServiceDate}, {@code xPlatformRef}.
     *
     * @param map Map to populate
     * @param rec Source VRS capture entity
     */
    private void addServiceFields(Map<String, Object> map, VpiCaptureView rec) {
        map.put("classOfService", rec.getClassOfService());
        map.put("classOfServiceDate", rec.getClassOfServiceDate());
        map.put("xPlatformRef", rec.getXPlatformRef());
    }

    /**
     * Adds transcription status and warehouse reference fields to the metadata map.
     * Includes: {@code transcriptResult}, {@code warehouseObjectKey}, {@code transcriptStatus}.
     *
     * @param map Map to populate
     * @param rec Source VRS capture entity
     */
    private void addTranscriptionFields(Map<String, Object> map, VpiCaptureView rec) {
        map.put("transcriptResult", rec.getTranscriptResult());
        map.put("warehouseObjectKey", rec.getWarehouseObjectKey());
        map.put("transcriptStatus", rec.getTranscriptStatus());
    }

    // ========== Entity Mapping Methods ==========

    /**
     * Enriches a page of OPCO capture entities with agent full names and converts each to a
     * {@link VpiMetadata} DTO for the API response.
     * If the page is empty, an empty page is returned immediately without any database calls.
     *
     * @param page Page of {@link VpiCaptureView} entities returned by the repository
     * @param opco OPCO code used to query the correct user repository for name resolution
     * @return Page of {@link VpiMetadata} DTOs with the {@code username} field populated where available
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
     * Extracts the distinct set of user IDs from a page of VRS capture entities.
     * Null user IDs are filtered out before collecting.
     * The resulting set is used to batch-fetch agent names in a single repository call.
     *
     * @param page Page of {@link VpiCaptureView} entities
     * @return Set of non-null user UUIDs present in the page
     */
    private Set<UUID> extractUserIds(Page<? extends VpiCaptureView> page) {
        return page.getContent().stream()
                .map(VpiCaptureView::getUserId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
    }

    /**
     * Maps a single {@link VpiCaptureView} entity to a {@link VpiMetadata} DTO.
     * Date fields are formatted to XML start-time strings via {@link #toXmlStartTime}.
     * The agent full name is resolved from the pre-fetched {@code userNameMap}; if no
     * matching entry exists, the {@code username} field is left null.
     *
     * @param rec         The VRS capture entity to convert
     * @param opco        OPCO code set on the resulting DTO
     * @param userNameMap Pre-fetched map of user UUID to full name
     * @return Populated {@link VpiMetadata} DTO ready for serialisation
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
