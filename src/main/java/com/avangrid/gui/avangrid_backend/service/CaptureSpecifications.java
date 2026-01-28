package com.avangrid.gui.avangrid_backend.service;

import com.avangrid.gui.avangrid_backend.model.VpiFiltersRequest;
import org.springframework.data.jpa.domain.Specification;

import jakarta.persistence.criteria.*;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

public final class CaptureSpecifications {

    private CaptureSpecifications() {
    }

    /* ===========================================================
       DATE RANGE
    =========================================================== */

    public static <T> Specification<T> dateBetween(
            String field,
            LocalDateTime from,
            LocalDateTime to
    ) {
        return (root, query, cb) -> {
            if (from == null && to == null) {
                return cb.conjunction();
            }

            Path<LocalDateTime> path = root.get(field);

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
       EXACT MATCH : OBJECT IDS (UUID)
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

            CriteriaBuilder.In<UUID> inClause = cb.in(root.get(field));
            cleaned.forEach(inClause::value);

            return inClause;
        };
    }

    /* ===========================================================
       EXACT MATCH : DIRECTION (0 or 1)
    =========================================================== */

    public static <T> Specification<T> directionExact(
            String field,
            Integer direction
    ) {
        return (root, query, cb) -> {
            if (direction == null || (direction != 0 && direction != 1)) {
                return cb.conjunction();
            }
            return cb.equal(root.get(field), direction);
        };
    }

    /* ===========================================================
       SUBSTRING MATCH : STRING COLUMNS
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

            return cb.or(predicates.toArray(new Predicate[0]));
        };
    }

    /* ===========================================================
       SUBSTRING MATCH : INTEGER COLUMN (channelNum)
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

    /* ===========================================================
       FULLNAME JOIN : user.fullname
    =========================================================== */

    public static <T> Specification<T> fullnameContainsAny(
            List<String> values
    ) {
        return (root, query, cb) -> {
            List<String> cleaned = cleanStringList(values);
            if (cleaned.isEmpty()) {
                return cb.conjunction();
            }

            Join<T, ?> userJoin = root.join("user", JoinType.LEFT);
            Expression<String> fullnameCol = cb.lower(userJoin.get("fullname"));

            List<Predicate> predicates = new ArrayList<>();
            for (String value : cleaned) {
                predicates.add(cb.like(fullnameCol, "%" + value + "%"));
            }

            return cb.or(predicates.toArray(new Predicate[0]));
        };
    }


    private static List<String> cleanStringList(List<String> input) {
        if (input == null) {
            return Collections.emptyList();
        }

        List<String> cleaned = new ArrayList<>();
        for (String value : input) {
            if (value != null) {
                String trimmed = value.trim();
                if (!trimmed.isEmpty()) {
                    cleaned.add(trimmed.toLowerCase());
                }
            }
        }
        return cleaned;
    }

    private static List<UUID> cleanUuidList(List<UUID> input) {
        if (input == null) {
            return Collections.emptyList();
        }

        List<UUID> cleaned = new ArrayList<>();
        for (UUID value : input) {
            if (value != null) {
                cleaned.add(value);
            }
        }
        return cleaned;
    }

    public static <T> Specification<T> build(
            LocalDateTime from,
            LocalDateTime to,
            VpiFiltersRequest filters
    ) {
        Specification<T> spec = Specification.where(
                CaptureSpecifications.dateBetween("dateAdded", from, to)
        );

        if (filters == null) {
            return spec;
        }

        return spec
                .and(CaptureSpecifications.objectIdsExactAny(
                        "objectId", filters.getObjectIDs()))
                .and(CaptureSpecifications.directionExact(
                        "direction", filters.getDirection()))
                .and(CaptureSpecifications.containsAny(
                        "extensionNum", filters.getExtensionNum()))
                .and(CaptureSpecifications.channelNumContainsAny(
                        "channelNum", filters.getChannelNum()))
                .and(CaptureSpecifications.containsAny(
                        "anialidigits", filters.getAniAliDigits()))
                .and(CaptureSpecifications.fullnameContainsAny(
                        filters.getName()));
    }

}
