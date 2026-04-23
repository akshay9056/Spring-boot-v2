package com.avangrid.gui.avangrid_backend.service;

import com.avangrid.gui.avangrid_backend.model.dto.request.VpiFiltersRequest;
import org.springframework.data.jpa.domain.Specification;

import jakarta.persistence.criteria.*;
import java.time.OffsetDateTime;
import java.util.*;

public final class CaptureSpecifications {

    private CaptureSpecifications() {}

    /* ===========================================================
       DATE RANGE (OffsetDateTime)
    =========================================================== */

    public static <T> Specification<T> dateBetween(
            String field,
            OffsetDateTime from,
            OffsetDateTime to
    ) {
        return (root, query, cb) -> {
            if (from == null && to == null) {
                return cb.conjunction();
            }

            Path<OffsetDateTime> path = root.get(field);

            if (from != null && to != null) {
                return cb.between(path, from, to);
            }
            if (from != null) {
                return cb.greaterThanOrEqualTo(path, from);
            }
            return cb.lessThanOrEqualTo(path, to);
        };
    }

    /* ===========================================================
       OBJECT ID (UUID IN)
    =========================================================== */

    public static <T> Specification<T> objectIdsExactAny(
            String field,
            List<UUID> objectIds
    ) {
        return (root, query, cb) -> {
            List<UUID> cleaned = cleanUuidList(objectIds);
            if (cleaned.isEmpty()) {
                return cb.conjunction();
            }

            CriteriaBuilder.In<UUID> in = cb.in(root.get(field));
            cleaned.forEach(in::value);
            return in;
        };
    }

    /* ===========================================================
       DIRECTION (BOOLEAN)
    =========================================================== */

    public static <T> Specification<T> directionExact(
            String field,
            Boolean direction
    ) {
        return (root, query, cb) -> {
            if (direction == null) {
                return cb.conjunction();
            }
            return cb.equal(root.get(field), direction);
        };
    }

    /* ===========================================================
       STRING CONTAINS (LIKE)
    =========================================================== */

    public static <T> Specification<T> containsAny(
            String field,
            List<String> values
    ) {
        return (root, query, cb) -> {
            List<String> cleaned = cleanStringList(values);
            if (cleaned.isEmpty()) {
                return cb.conjunction();
            }

            Expression<String> column = cb.lower(root.get(field));

            List<Predicate> predicates = new ArrayList<>();
            for (String value : cleaned) {
                predicates.add(cb.like(column, "%" + value + "%"));
            }

            return cb.and(
                    cb.isNotNull(root.get(field)), // ⭐ IMPORTANT
                    cb.or(predicates.toArray(new Predicate[0]))
            );
        };
    }


    /* ===========================================================
       INTEGER CONTAINS (channelNum)
    =========================================================== */

    public static <T> Specification<T> channelNumContainsAny(
            String field,
            List<String> values
    ) {
        return (root, query, cb) -> {
            List<String> cleaned = cleanStringList(values);
            if (cleaned.isEmpty()) {
                return cb.conjunction();
            }

            Expression<String> column = root.get(field).as(String.class);
            List<Predicate> predicates = new ArrayList<>();

            for (String value : cleaned) {
                predicates.add(cb.like(column, "%" + value + "%"));
            }

            return cb.or(predicates.toArray(new Predicate[0]));
        };
    }

    public static <T> Specification<T> userIdsIn(
            String field,
            Set<UUID> userIds) {

        return (root, query, cb) -> {
            if (userIds == null || userIds.isEmpty()) {
                return cb.conjunction();
            }
            CriteriaBuilder.In<UUID> in = cb.in(root.get(field));
            userIds.forEach(in::value);
            return in;
        };
    }


    /* ===========================================================
       BUILDER (NO JOINS)
    =========================================================== */

    public static <T> Specification<T> build(
            OffsetDateTime from,
            OffsetDateTime to,
            VpiFiltersRequest filters,
            Set<UUID> matchedUserIds

    ) {
        Specification<T> spec =
                Specification.where(dateBetween("dateAdded", from, to));

        if (filters == null) {
            return spec;
        }

        if (matchedUserIds != null && !matchedUserIds.isEmpty()) {
            spec = spec.and(userIdsIn("userId", matchedUserIds));
        }

        return spec
                .and(objectIdsExactAny("objectId", filters.getObjectIDs()))
                .and(directionExact("direction", filters.getDirection()))
                .and(containsAny("extensionNum", filters.getExtensionNum()))
                .and(channelNumContainsAny("channelNum", filters.getChannelNum()))
                .and(containsAny("anialidigits", filters.getAniAliDigits()))
                .and(containsAny("agentId", filters.getAgentID()));


    }

    /* ===========================================================
       CLEANERS
    =========================================================== */

    private static List<String> cleanStringList(List<String> input) {
        if (input == null) return Collections.emptyList();

        List<String> cleaned = new ArrayList<>();
        for (String value : input) {
            if (value != null && !value.trim().isEmpty()) {
                cleaned.add(value.trim().toLowerCase());
            }
        }
        return cleaned;
    }

    private static List<UUID> cleanUuidList(List<UUID> input) {
        if (input == null) return Collections.emptyList();

        List<UUID> cleaned = new ArrayList<>();
        for (UUID value : input) {
            if (value != null) cleaned.add(value);
        }
        return cleaned;
    }
}
