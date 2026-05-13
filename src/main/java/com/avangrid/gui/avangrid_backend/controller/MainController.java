package com.avangrid.gui.avangrid_backend.controller;

import com.avangrid.gui.avangrid_backend.model.dto.request.RecordingRequest;
import com.avangrid.gui.avangrid_backend.model.dto.request.VpiSearchRequest;
import com.avangrid.gui.avangrid_backend.model.dto.response.VpiSearchResponse;
import com.avangrid.gui.avangrid_backend.service.VpiRecordingService;
import com.avangrid.gui.avangrid_backend.service.OpcoService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.Set;

/**
 * Primary REST controller for the VRS (VPI Recording Service) portal.
 *
 * <p>Exposes all public-facing API endpoints under the {@code /api/v1} base path.
 * Handles the following concerns:
 * <ul>
 *   <li>OPCO access resolution for the authenticated user</li>
 *   <li>Paginated VRS recording search across CMP, RGE, and NYSEG OPCOs</li>
 *   <li>Full metadata retrieval for a specific recording</li>
 *   <li>Single recording download (streamed as MP3)</li>
 *   <li>Bulk recording download packaged as a ZIP archive</li>
 * </ul>
 *
 * <p>All request bodies are validated via Bean Validation ({@link Valid}).
 * Authentication is handled by Spring Security; the JWT principal is used where
 * OPCO-level access control is required.
 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
@Validated
@Tag(name = "VPI Recording APIs")
public class MainController {

    /** Service layer for all VRS recording operations (search, download, metadata, conversion). */
    private final VpiRecordingService service;

    /** Service responsible for resolving which OPCOs the authenticated user has access to. */
    private final OpcoService opcoService;

    // -------------------- OPCO ACCESS --------------------

    /**
     * Returns the set of OPCO codes (CMP, RGE, NYSEG) that the currently authenticated user
     * is authorised to access. The OPCO list is derived from the user's JWT claims.
     *
     * @param jwt The JWT of the currently authenticated user, injected by Spring Security
     * @return HTTP 200 with a {@link Set} of OPCO code strings the user may query
     */
    @Operation(summary = "Get opco codes the current user has access to")
    @GetMapping("/opcos")
    public ResponseEntity<Set<String>> getUserOpcos(@AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok(opcoService.resolveOpcoResponse(jwt));
    }

    // -------------------- SEARCH --------------------

    /**
     * Searches VRS recordings across the specified OPCO using the supplied date range,
     * optional field-level filters, and pagination settings.
     *
     * <p>The request body is validated before processing. Date strings must be in
     * {@code yyyy-MM-dd HH:mm:ss} format (EST) and the OPCO must be one of: CMP, RGE, NYSEG.
     *
     * @param request Validated {@link VpiSearchRequest} containing fromDate, toDate, OPCO,
     *                optional filters, and pagination parameters
     * @return HTTP 200 with a {@link VpiSearchResponse} containing the matching recording list
     *         and pagination metadata
     */
    @Operation(summary = "Search VPI recordings")
    @PostMapping(value = "/search", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<VpiSearchResponse> search(
            @Valid @RequestBody VpiSearchRequest request) {

        return ResponseEntity.ok(service.getTableData(request));
    }

    // -------------------- METADATA --------------------

    /**
     * Retrieves the complete metadata record for a single VRS recording identified by
     * its object ID and OPCO.
     *
     * <p>The returned map includes all available fields such as start time, duration,
     * channel, agent, call IDs, media file references, and transcription status.
     *
     * @param id   Non-null UUID of the VRS recording object to look up
     * @param opco Non-blank OPCO code — must be one of: CMP, RGE, NYSEG
     * @return HTTP 200 with a flat {@link Map} of all metadata field names and their values
     */
    @Operation(summary = "Get recording metadata")
    @GetMapping(value = "/metadata", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> getMetadata(
            @RequestParam @NotNull UUID id,
            @RequestParam @NotBlank String opco) {

        return ResponseEntity.ok(service.getMetadata(id, opco));
    }

    // -------------------- SINGLE RECORDING --------------------

    /**
     * Retrieves a single VRS recording from Azure Blob Storage and streams it back as an MP3 file.
     *
     * <p>The service locates the matching WAV blob using the OPCO, date, username, and any optional
     * metadata filters supplied in the request body, converts the WAV to MP3 via FFmpeg, and
     * returns the result as a binary octet-stream response.
     *
     * @param request Validated {@link RecordingRequest} containing OPCO, date, username,
     *                and optional metadata filters (ANI/ALI digits, duration, channel, extension, objectId)
     * @return HTTP 200 with the MP3 audio bytes; Content-Type {@code application/octet-stream}
     */
    @Operation(summary = "Download single VPI recording")
    @PostMapping(value = "/recording", produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public ResponseEntity<ByteArrayResource> getRecording(
            @Valid @RequestBody RecordingRequest request) {

        return service.getRecordingVpi(request);
    }

    // -------------------- BULK DOWNLOAD --------------------

    /**
     * Accepts a list of VRS recording requests and returns all successfully located recordings
     * packaged into a single ZIP archive.
     *
     * <p>Each entry in the list is processed independently so that a missing or failed recording
     * does not abort the rest of the batch. The ZIP also contains a {@code status.json} file
     * summarising the outcome (SUCCESS, NOT_FOUND, or ERROR) for every request.
     * Returns HTTP 400 if the request list is null or empty, and HTTP 204 if no recordings
     * could be resolved for any entry in the list.
     *
     * @param requests Non-null, non-empty list of validated {@link RecordingRequest} objects
     * @return HTTP 200 with a ZIP archive byte array, HTTP 204 if nothing was found,
     *         or HTTP 400 if the request list is null or empty
     */
    @Operation(summary = "Download multiple VPI recordings (ZIP)")
    @PostMapping(value = "/download", produces = "application/zip")
    public ResponseEntity<byte[]> download(
            @Valid @RequestBody List<RecordingRequest> requests) {
        if (requests == null || requests.isEmpty()) {
            return ResponseEntity.badRequest().build();
        }

        return service.downloadVpi(requests);
    }
}
