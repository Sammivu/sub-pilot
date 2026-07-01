package co.subpilot.common.exception;

/**
 * Thrown when a call to the real Nomba API fails — network error, non-2xx
 * response, or malformed response body. Caught at the call site in
 * NombaGatewayImpl and translated into a failed ChargeResponse/etc rather
 * than propagating, so a Nomba outage degrades gracefully instead of
 * crashing the billing engine.
 */
public class NombaApiException extends RuntimeException {

    public NombaApiException(String message) {
        super(message);
    }

    public NombaApiException(String message, Throwable cause) {
        super(message, cause);
    }
}