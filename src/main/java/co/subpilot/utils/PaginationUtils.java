package co.subpilot.utils;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class PaginationUtils {

    private PaginationUtils() {
    }

    public static Pageable pageable(
            int page,
            int perPage,
            String sort,
            Set<String> allowedFields,
            Map<String, String> fieldAliases,
            String defaultSortField) {

        return PageRequest.of(page, Math.min(perPage, 100),
                parseSort(sort, allowedFields, fieldAliases, defaultSortField)
        );
    }

    public static Sort parseSort(
            String sort,
            Set<String> allowedFields,
            Map<String, String> fieldAliases,
            String defaultSortField) {

        if (sort == null || sort.isBlank()) {
            return Sort.by(defaultSortField).descending();
        }

        List<Sort.Order> orders = new ArrayList<>();

        for (String clause : sort.split(";")) {

            String[] parts = clause.trim().split(",", 2);

            String requestedField = parts[0].trim();

            if (!allowedFields.contains(requestedField)) {
                continue;
            }

            String entityField =
                    fieldAliases.getOrDefault(requestedField, requestedField);

            boolean desc =
                    parts.length > 1 &&
                    "desc".equalsIgnoreCase(parts[1].trim());

            orders.add(desc
                    ? Sort.Order.desc(entityField)
                    : Sort.Order.asc(entityField));
        }

        return orders.isEmpty()
                ? Sort.by(defaultSortField).descending()
                : Sort.by(orders);
    }
}