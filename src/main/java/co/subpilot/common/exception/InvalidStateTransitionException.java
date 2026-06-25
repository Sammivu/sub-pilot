package co.subpilot.common.exception;

public class InvalidStateTransitionException extends RuntimeException {
    public InvalidStateTransitionException(String from, String to) {
        super("Cannot transition subscription from '" + from + "' to '" + to + "'.");
    }
}
