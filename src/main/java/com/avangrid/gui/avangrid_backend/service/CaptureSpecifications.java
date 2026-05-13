package com.avangrid.gui.avangrid_backend.service;

import com.avangrid.gui.avangrid_backend.model.dto.request.VpiFiltersRequest;
import org.springframework.data.jpa.domain.Specification;

import jakarta.persistence.criteria.*;
import java.time.OffsetDateTime;
import java.util.*;

/**
 * Utility class providing reusable JPA {@link Specification} fragments for querying
 * VRS (VPI Recording Service) capture records across all three OPCOs (CMP, RGE, NYSEG).
 *
 * <p>Each static factory method returns a generic {@code Specification<T>} so the same
 * predicate logic can be applied to the OPCO-specific entity types
 * ({@code VpiCaptureCmp}, {@code VpiCaptureNyseg}, {@code VpiCaptureRge})
 * without duplication.
 *
 * <p>All specifications treat a null or empty filter value as a wildcard — the predicate
 * degrades to {@link CriteriaBuilder#conjunction()} (SQL {@code 1=1}) so the filter is
 * simply ignored rather than excluding all rows.
 *
 * <p>This class is not instantiable; use the static factory methods directly.
 */
public final class CaptureSpecifications {

    /** Prevents instantiation of this utility class. */
    private CaptureSpecifications() {}

    /* ===========================================================
       DATE RANGE (OffsetDateTime)
    =========================================================== */

    /**
     * Builds a date-range predicate on the given {@link OffsetDateTime} field.
     *
     * <p>The comparison is performed on the full timestamp value (date <em>and</em> time),
     * so two recordings on the same calendar day but at different times will be ordered and
     * filtered correctly. Both bounds are inclusive.
     *
     * <p>Partial bounds are supported:
     * <ul>
     *   <li>Both null → {@code conjunction()} (no restriction)</li>
     *   <li>Only {@code from} → {@code >= from}</li>
     *   <li>Only {@code to} → {@code <= to}</li>
     *   <li>Both supplied → {@code BETWEEN from AND to}</li>
     * </ul>
     *
     * @param field Name of the {@link OffsetDateTime} entity field to filter on (e.g., {@code "startTime"})
     * @param from  Inclusive lower bound in UTC; may be null
     * @param to    Inclusive upper bound in UTC; may be null
     * @param <T>   OPCO-specific capture entity type
     * @return A {@link Specification} that applies the appropriate date-range predicate
     */
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

    /**
     * Builds an exact-match IN predicate on the given UUID field.
     * Null UUIDs are stripped from the list before the predicate is constructed.
     * If the cleaned list is empty, a {@code conjunction()} is returned (no restriction).
     *
     * @param field     Name of the UUID entity field to filter on (e.g., {@code "objectId"})
     * @param objectIds List of UUIDs to match against; null or empty list acts as a wildcard
     * @param <T>       OPCO-specific capture entity type
     * @return A {@link Specification} that restricts results to rows whose field value is in the list
     */
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

    /**
     * Builds an exact-match predicate on the boolean {@code direction} field
     * ({@code true} = inbound, {@code false} = outbound in VRS terminology).
     * If {@code direction} is null, a {@code conjunction()} is returned (no restriction).
     *
     * @param field     Name of the boolean entity field to filter on (e.g., {@code "direction"})
     * @param direction Expected boolean value; null acts as a wildcard
     * @param <T>       OPCO-specific capture entity type
     * @return A {@link Specification} that restricts results to rows matching the direction flag
     */
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

    /**
     * Builds a case-insensitive LIKE predicate that matches rows where the field contains
     * <em>any</em> of the supplied string values as a substring.
     *
     * <p>The predicate additionally guards against null column values with an explicit
     * {@code IS NOT NULL} check, preventing null entries from accidentally matching
     * a {@code %value%} pattern on some database dialects.
     *
     * <p>Null, blank, or whitespace-only entries in {@code values} are stripped before
     * building predicates. If the cleaned list is empty, a {@code conjunction()} is returned
     * (no restriction).
     *
     * @param field  Name of the string entity field to search (e.g., {@code "extensionNum"}, {@code "anialidigits"})
     * @param values List of substrings to match against; null or empty list acts as a wildcard
     * @param <T>    OPCO-specific capture entity type
     * @return A {@link Specification} that applies {@code IS NOT NULL AND (LOWER(field) LIKE %v1% OR LOWER(field) LIKE %v2% ...)}
     */
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

    /**
     * Builds a LIKE predicate for the integer {@code channelNum} field by casting it to
     * a string at the criteria level before applying substring matching.
     *
     * <p>This is intentionally different from {@link #containsAny} because {@code channelNum}
     * is stored as an integer in the database and cannot be passed directly to a LIKE expression.
     * The cast-to-string approach allows partial numeric matching
     * (e.g., filtering by {@code "1"} matches channels 1, 10, 11, 12, etc.).
     *
     * <p>No {@code IS NOT NULL} guard is applied here because casting a null integer to string
     * typically yields {@code NULL} rather than an empty string on most database dialects,
     * and a {@code LIKE} against {@code NULL} evaluates to {@code UNKNOWN} (not matched).
     *
     * <p>If the cleaned list is empty, a {@code conjunction()} is returned (no restriction).
     *
     * @param field  Name of the integer entity field to search (e.g., {@code "channelNum"})
     * @param values List of numeric substrings to match against; null or empty list acts as a wildcard
     * @param <T>    OPCO-specific capture entity type
     * @return A {@link Specification} that applies {@code CAST(field AS string) LIKE %v1% OR ...}
     */
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

    /**
     * Builds an IN predicate on the {@code userId} field that restricts results to recordings
     * belonging to any of the pre-resolved agent user IDs.
     *
     * <p>This predicate is applied when the caller supplies a name filter in the search request.
     * The name filter is first resolved to a set of UUIDs by querying the OPCO user table
     * (see {@code VpiRecordingService#fetchMatchedUserIds}), and the resulting set is passed here.
     *
     * <p>If {@code userIds} is null or empty, a {@code conjunction()} is returned (no restriction),
     * which allows the search to proceed without a user filter when no name was specified.
     *
     * @param field   Name of the UUID entity field to filter on (e.g., {@code "userId"})
     * @param userIds Set of pre-resolved user UUIDs to match against; null or empty acts as a wildcard
     * @param <T>     OPCO-specific capture entity type
     * @return A {@link Specification} that restricts results to recordings owned by users in the set
     */
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

    /**
     * Assembles the complete JPA {@link Specification} for a VRS recording search by composing
     * all individual predicate fragments into a single, chainable specification.
     *
     * <p>Composition order:
     * <ol>
     *   <li>Date range on {@code startTime} (always applied)</li>
     *   <li>User ID IN filter (applied only when {@code matchedUserIds} is non-empty)</li>
     *   <li>Object ID exact-match filter</li>
     *   <li>Call direction boolean filter</li>
     *   <li>Extension number substring filter</li>
     *   <li>Channel number substring filter (integer cast to string)</li>
     *   <li>ANI/ALI digits substring filter</li>
     *   <li>Agent ID substring filter</li>
     *   <li>ORDER BY {@code startTime} ASC (full timestamp — date and time) applied last</li>
     * </ol>
     *
     * <p>All filter predicates treat null or empty values as wildcards, so passing a null
     * {@code filters} object applies only the mandatory date range and returns results
     * ordered by start time.
     *
     * <p>No table joins are performed; all fields are on the root capture entity.
     *
     * @param from           Inclusive start date-time in UTC for the {@code startTime} range filter
     * @param to             Inclusive end date-time in UTC for the {@code startTime} range filter
     * @param filters        Optional {@link VpiFiltersRequest} carrying field-level filter values;
     *                       may be null (all field filters skipped)
     * @param matchedUserIds Pre-resolved set of user UUIDs from a name filter;
     *                       empty set means no user filter is applied
     * @param <T>            OPCO-specific capture entity type (CMP, NYSEG, or RGE)
     * @return Fully composed {@link Specification} ready to pass to any OPCO repository's
     *         {@code findAll(Specification, Pageable)} method
     */
    public static <T> Specification<T> build(
            OffsetDateTime from,
            OffsetDateTime to,
            VpiFiltersRequest filters,
            Set<UUID> matchedUserIds

    ) {
        Specification<T> spec =
                Specification.where(dateBetween("startTime", from, to));

        if (filters == null) {
            return addOrderByStartTime(spec);
        }

        if (matchedUserIds != null && !matchedUserIds.isEmpty()) {
            spec = spec.and(userIdsIn("userId", matchedUserIds));
        }

        spec = spec
                .and(objectIdsExactAny("objectId", filters.getObjectIDs()))
                .and(directionExact("direction", filters.getDirection()))
                .and(containsAny("extensionNum", filters.getExtensionNum()))
                .and(channelNumContainsAny("channelNum", filters.getChannelNum()))
                .and(containsAny("anialidigits", filters.getAniAliDigits()))
                .and(containsAny("agentId", filters.getAgentID()));

        return addOrderByStartTime(spec);
    }

    // ---------------------------------------------------------------
    // ORDER BY startTime ASC
    // ---------------------------------------------------------------

    /**
     * Wraps an existing {@link Specification} with an ORDER BY {@code startTime} ASC clause.
     *
     * <p>Ordering is applied only on the main {@link CriteriaQuery} and is intentionally
     * skipped for count queries (result type {@code Long} or {@code long}) that Spring Data JPA
     * issues automatically when calculating {@code Page} totals. Applying {@code ORDER BY} to a
     * count query is both unnecessary and rejected by some database dialects.
     *
     * <p>Because this method wraps the fully composed {@code spec} as the outermost lambda,
     * the {@code query.orderBy()} call is the last one applied to the {@link CriteriaQuery},
     * ensuring it is not overwritten by any inner predicate composition.
     *
     * <p><strong>Note on ordering precision:</strong> {@code startTime} is an {@link OffsetDateTime}
     * column, so the sort is performed on the complete timestamp value — date <em>and</em> time —
     * not just the date component. Two recordings on the same calendar day at different times
     * are ordered correctly by their exact time of day.
     *
     * @param spec The fully composed {@link Specification} to wrap with ordering
     * @param <T>  OPCO-specific capture entity type
     * @return A new {@link Specification} that applies the inner predicate and adds ORDER BY {@code startTime} ASC
     */
    private static <T> Specification<T> addOrderByStartTime(Specification<T> spec) {
        return (root, query, cb) -> {
            // Only apply orderBy on the main query, not subqueries (count queries)
            if (query != null && query.getResultType() != Long.class
                    && query.getResultType() != long.class) {
                query.orderBy(cb.asc(root.get("startTime")));
            }
            return spec.toPredicate(root, query, cb);
        };
    }

    /* ===========================================================
       CLEANERS
    =========================================================== */

    /**
     * Sanitises a raw list of string filter values by removing null entries,
     * trimming whitespace, discarding blank strings, and converting to lowercase
     * for case-insensitive predicate construction.
     *
     * @param input Raw list of filter strings from the API request; may be null
     * @return Cleaned list ready for use in LIKE predicates; empty list if input is null or all blank
     */
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

    /**
     * Sanitises a raw list of UUID filter values by removing null entries.
     * Used by {@link #objectIdsExactAny} before constructing the IN predicate.
     *
     * @param input Raw list of UUIDs from the API request; may be null
     * @return Cleaned list with nulls removed; empty list if input is null or all-null
     */
    private static List<UUID> cleanUuidList(List<UUID> input) {
        if (input == null) return Collections.emptyList();

        List<UUID> cleaned = new ArrayList<>();
        for (UUID value : input) {
            if (value != null) cleaned.add(value);
        }
        return cleaned;
    }
}
