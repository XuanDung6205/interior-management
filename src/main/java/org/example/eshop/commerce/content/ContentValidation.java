package org.example.eshop.commerce.content;

import org.example.eshop.commerce.common.CommerceException;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

public final class ContentValidation {
    public static final Set<String> ARTICLE_STATUSES = Set.of("DRAFT", "PUBLISHED", "ARCHIVED");
    private static final Pattern SLUG_PATTERN = Pattern.compile("^[a-z0-9]+(?:-[a-z0-9]+)*$");
    private static final Pattern PLACEMENT_PATTERN = Pattern.compile("^[A-Z0-9_]{1,50}$");

    private ContentValidation() {
    }

    public static void checkPage(int page, int size, String query) {
        if (page < 0 || size < 1 || size > 100 || (query != null && query.length() > 150)) {
            throw CommerceException.badRequest("INVALID_PAGINATION", "Tham số phân trang hoặc từ khóa không hợp lệ.");
        }
    }

    public static String trimToNull(String value) {
        if (value == null)
            return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    public static String requiredText(String value, String field, int maxLen, Map<String, String> errors) {
        String trimmed = trimToNull(value);
        if (trimmed == null) {
            errors.put(field, "Không được để trống.");
            return null;
        }
        if (trimmed.length() > maxLen) {
            errors.put(field, "Tối đa " + maxLen + " ký tự.");
        }
        return trimmed;
    }

    public static String optionalText(String value, String field, int maxLen, Map<String, String> errors) {
        String trimmed = trimToNull(value);
        if (trimmed != null && trimmed.length() > maxLen) {
            errors.put(field, "Tối đa " + maxLen + " ký tự.");
        }
        return trimmed;
    }

    public static String validateUrl(String value, String field, boolean required, Map<String, String> errors) {
        String trimmed = trimToNull(value);
        if (trimmed == null) {
            if (required)
                errors.put(field, "URL không được để trống.");
            return null;
        }
        if (trimmed.length() > 1000) {
            errors.put(field, "URL tối đa 1000 ký tự.");
            return trimmed;
        }
        String lower = trimmed.toLowerCase(Locale.ROOT);
        boolean safe = (lower.startsWith("/") && !lower.startsWith("//"))
                || lower.startsWith("http://")
                || lower.startsWith("https://");
        if (!safe) {
            errors.put(field, "URL phải bắt đầu bằng /, http:// hoặc https://.");
        }
        return trimmed;
    }

    public static String validateSlug(String value, Map<String, String> errors) {
        String trimmed = trimToNull(value);
        if (trimmed == null) {
            errors.put("slug", "Slug không được để trống.");
            return null;
        }
        String normalized = trimmed.toLowerCase(Locale.ROOT);
        if (normalized.length() > 250 || !SLUG_PATTERN.matcher(normalized).matches()) {
            errors.put("slug", "Slug chỉ gồm chữ thường a-z, số 0-9 và dấu gạch nối (-).");
        }
        return normalized;
    }

    public static String validatePlacement(String value, Map<String, String> errors) {
        String trimmed = trimToNull(value);
        String normalized = trimmed == null ? "HOME_HERO" : trimmed.toUpperCase(Locale.ROOT);
        if (!PLACEMENT_PATTERN.matcher(normalized).matches()) {
            errors.put("placement", "Placement chỉ gồm chữ hoa, số và dấu _ (tối đa 50 ký tự).");
        }
        return normalized;
    }

    public static void validateDateRange(Instant startsAt, Instant endsAt, Map<String, String> errors) {
        if (startsAt != null && endsAt != null && !endsAt.isAfter(startsAt)) {
            errors.put("endsAt", "Thời điểm kết thúc phải sau thời điểm bắt đầu.");
        }
    }

    public static void throwIfErrors(Map<String, String> errors) {
        if (!errors.isEmpty()) {
            throw CommerceException.invalid(new LinkedHashMap<>(errors));
        }
    }
}