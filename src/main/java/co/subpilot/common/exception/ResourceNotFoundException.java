package co.subpilot.common.exception;

// ── Resource not found (404) ──────────────────────────────────────────────────
public class ResourceNotFoundException extends RuntimeException {
    private final String code;

    public ResourceNotFoundException(String resourceType, String id) {
        super(resourceType + " with id " + id + " was not found.");
        this.code = resourceType.toLowerCase().replace(" ", "_") + "_not_found";
    }

    public ResourceNotFoundException(String code, String resourceType, String id) {
        super(resourceType + " with id " + id + " was not found.");
        this.code = code;
    }

    public String getCode() { return code; }
}
