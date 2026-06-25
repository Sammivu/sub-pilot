package co.subpilot.subscription;

import co.subpilot.common.exception.InvalidStateTransitionException;
import co.subpilot.subscription.enums.SubscriptionStatus;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Encodes the subscription state machine exactly as specified in the
 * frontend's BACKEND_HANDOFF.md — 11 valid transitions, 6 states.
 *
 * This is the authoritative server-side guard. The frontend UI displays
 * this table at /app/events but does NOT enforce it — we do, here.
 *
 * Transitions:
 *   trialing  -> active     (trial invoice paid / trial ends without cancellation)
 *   trialing  -> cancelled  (subscriber/operator cancels before activation)
 *   active    -> past_due   (renewal charge fails)
 *   past_due  -> active     (retry succeeds / portal self-cure)
 *   active    -> paused     (operator or supported subscriber action)
 *   paused    -> active     (merchant resume)
 *   active    -> cancelled  (operator or subscriber cancellation)
 *   past_due  -> cancelled  (dunning exhausted, cancel_after_exhaustion = true)
 *   past_due  -> expired    (dunning exhausted, cancel_after_exhaustion = false)
 *   paused    -> cancelled  (cancellation while paused)
 *   active    -> expired    (fixed term ends)
 *
 * cancelled and expired are terminal.
 */
public final class SubscriptionStateMachine {

    private static final Map<SubscriptionStatus, Set<SubscriptionStatus>> TRANSITIONS = new EnumMap<>(SubscriptionStatus.class);

    static {
        TRANSITIONS.put(SubscriptionStatus.trialing, EnumSet.of(
                SubscriptionStatus.active, SubscriptionStatus.cancelled
        ));
        TRANSITIONS.put(SubscriptionStatus.active, EnumSet.of(
                SubscriptionStatus.past_due, SubscriptionStatus.paused,
                SubscriptionStatus.cancelled, SubscriptionStatus.expired
        ));
        TRANSITIONS.put(SubscriptionStatus.past_due, EnumSet.of(
                SubscriptionStatus.active, SubscriptionStatus.cancelled, SubscriptionStatus.expired
        ));
        TRANSITIONS.put(SubscriptionStatus.paused, EnumSet.of(
                SubscriptionStatus.active, SubscriptionStatus.cancelled
        ));
        TRANSITIONS.put(SubscriptionStatus.cancelled, EnumSet.noneOf(SubscriptionStatus.class));
        TRANSITIONS.put(SubscriptionStatus.expired, EnumSet.noneOf(SubscriptionStatus.class));
    }

    private SubscriptionStateMachine() {}

    public static boolean canTransition(SubscriptionStatus from, SubscriptionStatus to) {
        return TRANSITIONS.getOrDefault(from, Set.of()).contains(to);
    }

    /**
     * Validates and throws if the transition is not allowed.
     * Call this before mutating subscription.status anywhere in the codebase.
     */
    public static void assertCanTransition(SubscriptionStatus from, SubscriptionStatus to) {
        if (from == to) return; // idempotent no-op transitions are fine (e.g. active -> active on renewal)
        if (!canTransition(from, to)) {
            throw new InvalidStateTransitionException(from.name(), to.name());
        }
    }

    public static boolean isTerminal(SubscriptionStatus status) {
        return TRANSITIONS.getOrDefault(status, Set.of()).isEmpty();
    }
}