package co.subpilot.internal.audit.dto;

public class InternalAuditDtos {

    public record AuditLogResponse(
            String auditId, String actorAdminId, String actorEmail, String targetType, String targetId,
            String actionType, Object oldValue, Object newValue, String reason, String createdAt
    ) {
        public static AuditLogResponse from(co.subpilot.internal.audit.entity.InternalAuditLog a,
                                             com.fasterxml.jackson.databind.ObjectMapper mapper) {
            return new AuditLogResponse(
                    a.getId(), a.getActorAdminId(), a.getActorEmail(), a.getTargetType(), a.getTargetId(),
                    a.getActionType(), parseOrNull(a.getOldValue(), mapper), parseOrNull(a.getNewValue(), mapper),
                    a.getReason(), a.getCreatedAt() != null ? a.getCreatedAt().toString() : null
            );
        }

        private static Object parseOrNull(String json, com.fasterxml.jackson.databind.ObjectMapper mapper) {
            if (json == null) return null;
            try {
                return mapper.readValue(json, Object.class);
            } catch (Exception e) {
                return json;
            }
        }
    }
}