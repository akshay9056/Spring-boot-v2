package com.avangrid.gui.avangrid_backend.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Service
public class OpcoService {

    // Key = opco code (NYSEG/CMP/RGE), Value = Azure AD group object ID
    private final Map<String, String> opcoGroups;

    // Inverted at startup: group ID → opco code (avoids re-computing per request)
    private final Map<String, String> groupIdToOpco;

    private final String adminGroupId;

    private static final Set<String> ADMIN = Set.of("ADMIN");

    private static final Set<String> ALL_OPCOS = Set.of("NYSEG", "CMP", "RGE");

    public OpcoService(
            @Value("#{${app.opco-groups}}") Map<String, String> opcoGroups,
            @Value("${app.admin-group-id}") String adminGroupId) {
        this.opcoGroups = opcoGroups;
        this.adminGroupId = adminGroupId;
        // Invert once at bean creation — groupId → opcoCode
        this.groupIdToOpco = opcoGroups.entrySet().stream()
                .collect(Collectors.toMap(
                        Map.Entry::getValue,  // group ID → key
                        Map.Entry::getKey     // opco code → value
                ));
    }

    /**
     * Resolves which opco codes the JWT user has access to.
     * Admin group members automatically get all three opcos.
     */
    public Set<String> resolveOpcos(Jwt jwt) {
        List<String> tokenGroups = jwt.getClaimAsStringList("groups");

        if (tokenGroups == null || tokenGroups.isEmpty()) {
            log.warn("JWT for subject '{}' has no groups claim", jwt.getSubject());
            return Set.of();
        }

        // Admin group → full access
        if (tokenGroups.contains(adminGroupId)) {
            log.debug("Subject '{}' is admin — granting all opcos", jwt.getSubject());
            return ALL_OPCOS;
        }

        // Map whichever group IDs are present to their opco codes
        return tokenGroups.stream()
                .filter(groupIdToOpco::containsKey)
                .map(groupIdToOpco::get)
                .collect(Collectors.toSet());
    }

    /**
     * Returns true if the user's JWT grants access to the requested opco.
     */
    public boolean hasOpcoAccess(Jwt jwt, String requestedOpco) {
        if (requestedOpco == null || requestedOpco.isBlank()) {
            return false;
        }
        return resolveOpcos(jwt).contains(requestedOpco.toUpperCase());
    }


    public Set<String> resolveOpcoResponse(Jwt jwt) {
        List<String> tokenGroups = jwt.getClaimAsStringList("groups");

        if (tokenGroups == null || tokenGroups.isEmpty()) {
            return Set.of();
        }

        // Return "ADMIN" label to frontend for UI handling
        if (tokenGroups.contains(adminGroupId)) {
            return Set.of("ADMIN");
        }

        return tokenGroups.stream()
                .filter(groupIdToOpco::containsKey)
                .map(groupIdToOpco::get)
                .collect(Collectors.toSet());
    }
}