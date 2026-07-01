package co.subpilot.subscription;

import co.subpilot.common.exception.InvalidStateTransitionException;
import co.subpilot.subscription.enums.SubscriptionStatus;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Encodes the subscription state machine — 7 states, with the explicit
 * suspended state separating "actively retrying within grace period" from
 * "grace period elapsed, still recoverable but access should be cut off
 * downstream" (see V8 migration for the full rationale).
 *
 * This is the authoritative server-side guard. The frontend UI displays
 * this table at /app/events but does NOT enforce it — we do, here.
 *
 * Transitions:
 *   trialing  -> active     (trial invoice paid / trial ends without cancellation)
 *   trialing  -> cancelled  (subscriber/operator cancels before activation)
 *   active    -> past_due   (renewal charge fails)
 *   past_due  -> active     (retry succeeds / portal self-cure, within grace period)
 *   past_due  -> suspended  (grace_period_days elapses with no recovery)
 *   suspended -> active     (retry succeeds / portal self-cure, after grace period)
 *   suspended -> cancelled  (dunning exhausted, cancel_after_exhaustion = true)
 *   suspended -> expired    (dunning exhausted, cancel_after_exhaustion = false)
 *   active    -> paused     (operator or supported subscriber action)
 *   paused    -> active     (merchant resume)
 *   active    -> cancelled  (operator or subscriber cancellation)
 *   paused    -> cancelled  (cancellation while paused)
 *   active    -> expired    (fixed term ends)
 *
 * cancelled and expired are terminal.
 *
 * Note: past_due -> cancelled / past_due -> expired (the pre-V8 direct
 * transitions) are deliberately NOT retained. Every dunning-exhaustion path
 * now passes through suspended first — see DunningTriggerService, which
 * checks grace period elapse on every scheduler pass before ever reaching
 * exhaustion, so a subscription should never skip straight from past_due
 * to a terminal state under the current logic.
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
                SubscriptionStatus.active, SubscriptionStatus.suspended
        ));
        TRANSITIONS.put(SubscriptionStatus.suspended, EnumSet.of(
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