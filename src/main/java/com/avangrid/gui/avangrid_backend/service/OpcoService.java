package com.avangrid.gui.avangrid_backend.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Service responsible for resolving OPCO (operating company) access rights
 * for authenticated users in the VRS (VPI Recording Service) portal.
 *
 * <p>OPCO access is derived entirely from the {@code roles} claim present in the
 * user's Azure AD JWT token — no external Graph API calls or database lookups are
 * performed. Role values in the token are expected to be either the OPCO code itself
 * (e.g., {@code "CMP"}, {@code "RGE"}, {@code "NYSEG"}) or the special
 * {@code "ADMIN"} role which grants access to all three OPCOs.
 *
 * <p>Supported OPCOs: CMP (Central Maine Power), RGE (Rochester Gas and Electric),
 * NYSEG (New York State Electric and Gas).
 */
@Slf4j
@Service
public class OpcoService {

    /**
     * Complete set of all OPCOs supported by the VRS portal.
     * Returned as-is when the authenticated user holds the {@code ADMIN} role.
     */
    private static final Set<String> ALL_OPCOS = Set.of("NYSEG", "CMP", "RGE");

    /**
     * Set of recognised OPCO role values used to filter out any unrelated
     * application roles that may be present in the JWT {@code roles} claim.
     */
    private static final Set<String> VALID_OPCOS = Set.of("NYSEG", "CMP", "RGE");

    /**
     * JWT role value that represents a VRS administrator.
     * Users with this role are granted access to all OPCOs.
     */
    private static final String ADMIN_ROLE = "ADMIN";

    /**
     * Resolves the set of OPCO codes the authenticated user is authorised to access,
     * derived directly from the {@code roles} claim in their JWT token.
     *
     * <p>Resolution rules:
     * <ul>
     *   <li>If the {@code roles} claim is absent or empty → empty set (no access)</li>
     *   <li>If {@code roles} contains {@code "ADMIN"} → all three OPCOs (CMP, RGE, NYSEG)</li>
     *   <li>Otherwise → only the role values that match a known OPCO code; all other
     *       application roles present in the token are silently ignored</li>
     * </ul>
     *
     * <p>This method is used internally for access-control checks (e.g., {@link #hasOpcoAccess}).
     * For the API response sent to the frontend, use {@link #resolveOpcoResponse} instead.
     *
     * @param jwt The JWT of the currently authenticated user
     * @return Immutable set of OPCO codes the user may access; never null, may be empty
     */
    public Set<String> resolveOpcos(Jwt jwt) {
        List<String> roles = jwt.getClaimAsStringList("roles");

        if (roles == null || roles.isEmpty()) {
            log.warn("JWT for subject '{}' has no roles claim", jwt.getSubject());
            return Set.of();
        }

        // Admin role → full access to all opcos
        if (roles.contains(ADMIN_ROLE)) {
            log.debug("Subject '{}' is admin — granting all opcos", jwt.getSubject());
            return ALL_OPCOS;
        }

        // Filter only valid opco roles — ignore any other app roles
        return roles.stream()
                .filter(VALID_OPCOS::contains)
                .collect(Collectors.toSet());
    }

    /**
     * Resolves the OPCO access set in the format expected by the VRS frontend,
     * returned by the {@code GET /api/v1/opcos} endpoint.
     *
     * <p>Differs from {@link #resolveOpcos} in one respect: when the user holds the
     * {@code ADMIN} role the method returns {@code {"ADMIN"}} rather than the full OPCO list.
     * This allows the frontend to display an "Admin" label and handle all-OPCO access
     * as a distinct UI state without enumerating individual OPCOs.
     *
     * <p>Resolution rules:
     * <ul>
     *   <li>If the {@code roles} claim is absent or empty → empty set</li>
     *   <li>If {@code roles} contains {@code "ADMIN"} → {@code {"ADMIN"}}</li>
     *   <li>Otherwise → the subset of role values that match a known OPCO code</li>
     * </ul>
     *
     * @param jwt The JWT of the currently authenticated user
     * @return Immutable set containing either OPCO codes or the single {@code "ADMIN"} label;
     *         never null, may be empty
     */
    public Set<String> resolveOpcoResponse(Jwt jwt) {
        List<String> roles = jwt.getClaimAsStringList("roles");

        if (roles == null || roles.isEmpty()) {
            return Set.of();
        }

        if (roles.contains(ADMIN_ROLE)) {
            return Set.of("ADMIN");
        }

        return roles.stream()
                .filter(VALID_OPCOS::contains)
                .collect(Collectors.toSet());
    }

    /**
     * Checks whether the authenticated user is authorised to access the requested OPCO.
     *
     * <p>The check is case-insensitive on the {@code requestedOpco} side — the value is
     * uppercased before comparison so callers may pass mixed-case strings without issue.
     * Returns {@code false} immediately if {@code requestedOpco} is null or blank.
     *
     * @param jwt           The JWT of the currently authenticated user
     * @param requestedOpco The OPCO code being requested (e.g., {@code "cmp"}, {@code "RGE"})
     * @return {@code true} if the user's resolved OPCO set contains the requested code;
     *         {@code false} if the code is null, blank, or not in the user's allowed set
     */
    public boolean hasOpcoAccess(Jwt jwt, String requestedOpco) {
        if (requestedOpco == null || requestedOpco.isBlank()) {
            return false;
        }
        return resolveOpcos(jwt).contains(requestedOpco.toUpperCase());
    }
}
