package com.avangrid.gui.avangrid_backend.service;

import com.avangrid.gui.avangrid_backend.model.Recording;
import jakarta.persistence.criteria.Path;
import org.springframework.data.jpa.domain.Specification;

import java.time.LocalDateTime;
import java.util.List;

import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Predicate;
import java.util.ArrayList;


public final class RecordingSpecifications {

    private RecordingSpecifications() {}



    public static Specification<Recording> dateBetween(LocalDateTime from, LocalDateTime to) {
        return (root, query, cb) -> {
            Path<LocalDateTime> datePath = root.get("dateAdded");

            if (from != null && to != null) {
                return cb.between(datePath, from, to);
            } else if (from != null) {
                return cb.greaterThanOrEqualTo(datePath, from);
            } else if (to != null) {
                return cb.lessThanOrEqualTo(datePath, to);
            }
            return cb.conjunction();
        };
    }


    public static Specification<Recording> containsString(String coloumn, String fileName) {
        return (root, query, cb) -> {
            if (isNotBlank(fileName)) {
                // case-insensitive: lower(column) LIKE %lower(term)%
                return cb.like(
                        cb.lower(root.get(coloumn)),
                        "%" + escapeLike(fileName.toLowerCase()) + "%", '\\'
                );
            }
            return cb.conjunction();
        };
    }

    /* ---------- Contains-any (substring) helpers ---------- */

    /** Generic: column contains ANY of the provided terms (case-insensitive). */
    public static Specification<Recording> containsAnyIgnoreCase(String column, List<String> terms) {
        return (root, query, cb) -> {
            List<String> cleaned = normalizeTerms(terms);
            if (cleaned.isEmpty()) return cb.conjunction();

            Expression<String> field = cb.lower(root.get(column));
            List<Predicate> ors = new ArrayList<>(cleaned.size());
            for (String term : cleaned) {
                // Use LIKE with escape so user input '%' or '_' is safe
                ors.add(cb.like(field, "%" + term + "%", '\\'));
            }
            return cb.or(ors.toArray(new Predicate[0]));
        };
    }

    /* ------------- Field-specific convenience ------------- */

    public static Specification<Recording> extensionNumContainsAny(List<String> values) {
        return containsAnyIgnoreCase("extensionNum", values);
    }

    public static Specification<Recording> fileNameContainsAny(List<String> values) {
        return containsAnyIgnoreCase("fileName", values);
    }

    public static Specification<Recording> objectIdContainsAny(List<String> values) {
        return containsAnyIgnoreCase("objectId", values);
    }

    public static Specification<Recording> channelNumContainsAny(List<String> values) {
        return containsAnyIgnoreCase("channelNum", values);
    }

    public static Specification<Recording> aniAliDigitsContainsAny(List<String> values) {
        return containsAnyIgnoreCase("aniAliDigits", values);
    }

    public static Specification<Recording> nameContainsAny(List<String> values) {
        return containsAnyIgnoreCase("name", values);
    }

    /* ------------------ Utility methods ------------------- */

    private static List<String> normalizeTerms(List<String> raw) {
        if (raw == null) return List.of();
        List<String> out = new ArrayList<>(raw.size());
        for (String s : raw) {
            if (isNotBlank(s)) {
                // lower-case once, escape LIKE wildcards once
                out.add(escapeLike(s.toLowerCase()));
            }
        }
        return out;
    }

    private static boolean isNotBlank(String s) {
        return s != null && !s.trim().isEmpty();
    }

    /**
     * Escape SQL LIKE wildcards so user input is treated literally.
     * We choose '\' as the escape character and pass it to cb.like(..., '\\')
     */
    private static String escapeLike(String s) {
        // order matters: escape backslash first
        return s
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
    }
}
