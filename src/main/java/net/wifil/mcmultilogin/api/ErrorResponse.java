package net.wifil.mcmultilogin.api;

/**
 * Represents the JSON error body returned by MC-MultiLogin-service when
 * {@code detail=true} and a login attempt is rejected (HTTP 403).
 *
 * <pre>
 * {
 *   "error": "ForbiddenOperationException",
 *   "errorMessage": "...",
 *   "cause": "DUPLICATE_NAME",
 *   "availableId": "Steve_2"   // only for DUPLICATE_NAME
 * }
 * </pre>
 */
public class ErrorResponse {

    public static final String CAUSE_DUPLICATE_NAME = "DUPLICATE_NAME";

    private String error;
    private String errorMessage;
    private String cause;
    private String availableId;

    public String getError() {
        return error;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public String getCause() {
        return cause;
    }

    public String getAvailableId() {
        return availableId;
    }

    public boolean isDuplicateName() {
        return CAUSE_DUPLICATE_NAME.equals(cause);
    }
}
