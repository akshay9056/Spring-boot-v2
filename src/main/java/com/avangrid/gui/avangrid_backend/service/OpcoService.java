package com.avangrid.gui.avangrid_backend.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Service
public class OpcoService {

    private static final Set<String> ALL_OPCOS = Set.of("NYSEG", "CMP", "RGE");
    private static final Set<String> VALID_OPCOS = Set.of("NYSEG", "CMP", "RGE");
    private static final String ADMIN_ROLE = "ADMIN";

    /**
     * Resolves opco codes from JWT "roles" claim.
     * No config, no Graph API — role values are the opco codes directly.
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
     * Used by /user/opcos endpoint — returns "ADMIN" label for frontend.
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
     * Returns true if the user's JWT roles grant access to the requested opco.
     */
    public boolean hasOpcoAccess(Jwt jwt, String requestedOpco) {
        if (requestedOpco == null || requestedOpco.isBlank()) {
            return false;
        }
        return resolveOpcos(jwt).contains(requestedOpco.toUpperCase());
    }
}