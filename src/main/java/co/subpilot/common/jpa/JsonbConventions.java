package co.subpilot.common.jpa;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import org.hibernate.type.SqlTypes;

/**
 * Marker — Hibernate 6+ supports JSONB natively via @JdbcTypeCode(SqlTypes.JSON).
 * This class documents the convention; entities use the annotation directly
 * rather than a converter. See Event.java / WebhookLog.java for usage pattern.
 */
public final class JsonbConventions {
    public static final int JSON_TYPE = SqlTypes.JSON;
    private JsonbConventions() {}
}