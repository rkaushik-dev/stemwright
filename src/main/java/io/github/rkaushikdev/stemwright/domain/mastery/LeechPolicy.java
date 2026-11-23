package io.github.rkaushikdev.stemwright.domain.mastery;

import io.github.rkaushikdev.stemwright.domain.errors.ValidationException;
import io.github.rkaushikdev.stemwright.domain.text.Collation;
import io.github.rkaushikdev.stemwright.domain.time.CivilDates;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * A leech is a cell that keeps coming back wrong. Left alone it fills the review queue and crowds
 * out everything the learner could actually be learning, so past a threshold something has to
 * happen to it. What happens is the policy's {@link LeechAction}.
 *
 * <p>Build one through {@link #builder()}, which starts from {@link #DEFAULT} and checks the result;
 * the canonical constructor only copies what it is given.
 *
 * @param threshold how many lapses make a leech
 * @param action what happens to a leech
 * @param consecutiveOnly whether to count only lapses since the last correct answer rather than all
 * @param demotionRest how many days a demoted cell waits before coming back
 */
public record LeechPolicy(int threshold, LeechAction action, boolean consecutiveOnly, int demotionRest) {

    public static final LeechPolicy DEFAULT = new LeechPolicy(6, LeechAction.SUSPEND, false, 3);

    private static final Comparator<CellStrength> WORST_FIRST = Comparator.comparingInt(CellStrength::lapses)
            .reversed()
            .thenComparing(CellStrength::cellId, Collation.ORDER);

    public LeechPolicy {
        Objects.requireNonNull(action, "action");
    }

    /** A builder holding the default policy, for a caller that wants to change only some of it. */
    public static Builder builder() {
        return new Builder(DEFAULT);
    }

    /**
     * Every lapse the cell has had, or under consecutive counting only those since its latest right
     * answer. A near miss in between neither breaks the run nor adds to it.
     */
    public int lapseCount(CellStrength strength) {
        return consecutiveOnly ? strength.lapseRun() : strength.lapses();
    }

    /** True at or past the threshold. A cell that is already suspended is never a leech again. */
    public boolean isLeech(CellStrength strength) {
        if (strength.suspended()) {
            return false;
        }
        return lapseCount(strength) >= threshold;
    }

    /**
     * Acts on one cell as it stands, which is what a sweep over a ledger does. The strength that
     * comes back is this very instance whenever the policy left it alone, which is the case for a
     * cell that is not a leech and for the tag action.
     */
    public Applied applyTo(CellStrength strength, Ladder ladder, LocalDate on) {
        int lapses = lapseCount(strength);
        if (!isLeech(strength)) {
            return new Applied(strength, new LeechOutcome(strength.cellId(), false, Optional.empty(), lapses));
        }
        return act(strength, ladder, on, lapses);
    }

    /**
     * Acts on a cell one answer has just moved from {@code before} to {@code after}. The policy fires
     * only when that answer carried the lapse count onto the threshold, or onto one of the points
     * every {@link #repeatEvery()} lapses past it. A right answer never fires it, and a cell that was
     * brought back from suspension, demoted or flagged is given that much rope before it is caught
     * again rather than being caught on its very next lapse. The strength that comes back is {@code
     * after} itself whenever the policy did not fire or changed nothing.
     */
    public Applied applyTo(CellStrength before, CellStrength after, Ladder ladder, LocalDate on) {
        int lapses = lapseCount(after);
        boolean fires = !after.suspended() && lapses > lapseCount(before) && isFiringPoint(lapses);
        if (!fires) {
            return new Applied(after, new LeechOutcome(after.cellId(), false, Optional.empty(), lapses));
        }
        return act(after, ladder, on, lapses);
    }

    /** How many lapses past the threshold the policy acts again: half the threshold, rounded up. */
    int repeatEvery() {
        return Math.ceilDiv(threshold, 2);
    }

    private boolean isFiringPoint(int lapses) {
        return lapses >= threshold && (lapses - threshold) % repeatEvery() == 0;
    }

    private Applied act(CellStrength strength, Ladder ladder, LocalDate on, int lapses) {
        LeechOutcome outcome = new LeechOutcome(strength.cellId(), true, Optional.of(action), lapses);
        return switch (action) {
            case SUSPEND -> new Applied(strength.suspend(), outcome);
            case DEMOTE -> {
                int rung = ladder.clampRung(0);
                LocalDate due = CivilDates.addDays(on, Math.max(demotionRest, ladder.intervalAt(rung)));
                yield new Applied(strength.withSchedule(rung, due), outcome);
            }
            case TAG -> new Applied(strength, outcome);
        };
    }

    /** The leeches among the cells asked about, the most lapsed first. */
    public List<CellStrength> leechesIn(MasteryLedger ledger, List<String> cellIds) {
        return cellIds.stream()
                .map(ledger::strengthFor)
                .filter(this::isLeech)
                .sorted(WORST_FIRST)
                .toList();
    }

    /**
     * Acts on every leech among the cells asked about and reports each one. The ledger that comes
     * back is this very ledger when the policy changed nothing in it.
     */
    public Sweep sweep(MasteryLedger ledger, List<String> cellIds, Ladder ladder, LocalDate on) {
        MasteryLedger next = ledger;
        List<LeechOutcome> outcomes = new ArrayList<>();
        for (CellStrength strength : leechesIn(ledger, cellIds)) {
            Applied applied = applyTo(strength, ladder, on);
            outcomes.add(applied.outcome());
            if (applied.strength() != strength) {
                next = next.withStrength(applied.strength());
            }
        }
        return new Sweep(next, outcomes);
    }

    public String describe() {
        String counting = consecutiveOnly ? "consecutive lapses" : "lapses";
        String after = threshold + " " + counting + " and again every " + repeatEvery() + " after";
        return switch (action) {
            case SUSPEND -> "suspend a cell after " + after;
            case DEMOTE -> "send a cell back to the bottom rung after " + after;
            case TAG -> "flag a cell for review after " + after + ", changing nothing";
        };
    }

    /**
     * What a policy did to one cell.
     *
     * @param strength the cell's strength afterwards
     * @param outcome what the policy found and did
     */
    public record Applied(CellStrength strength, LeechOutcome outcome) {

        public Applied {
            Objects.requireNonNull(strength, "strength");
            Objects.requireNonNull(outcome, "outcome");
        }
    }

    /**
     * What a policy did across a list of cells.
     *
     * @param ledger the ledger afterwards
     * @param outcomes one outcome for each leech, the most lapsed first
     */
    public record Sweep(MasteryLedger ledger, List<LeechOutcome> outcomes) {

        public Sweep {
            Objects.requireNonNull(ledger, "ledger");
            outcomes = List.copyOf(outcomes);
        }
    }

    /** Builds a {@link LeechPolicy} from another one, checking the result when it is built. */
    public static final class Builder {

        private int threshold;
        private LeechAction action;
        private boolean consecutiveOnly;
        private int demotionRest;

        private Builder(LeechPolicy from) {
            threshold = from.threshold;
            action = from.action;
            consecutiveOnly = from.consecutiveOnly;
            demotionRest = from.demotionRest;
        }

        public Builder threshold(int value) {
            threshold = value;
            return this;
        }

        public Builder action(LeechAction value) {
            action = Objects.requireNonNull(value, "action");
            return this;
        }

        public Builder consecutiveOnly(boolean value) {
            consecutiveOnly = value;
            return this;
        }

        public Builder demotionRest(int value) {
            demotionRest = value;
            return this;
        }

        /**
         * Checks the policy and builds it, stopping at the first field that is wrong.
         *
         * @throws ValidationException for a threshold below one or a negative demotion rest
         */
        public LeechPolicy build() {
            if (threshold < 1) {
                throw ValidationException.field("threshold", "must be a whole number above zero: " + threshold);
            }
            if (demotionRest < 0) {
                throw ValidationException.field("demotionRest", "must not be negative: " + demotionRest);
            }
            return new LeechPolicy(threshold, action, consecutiveOnly, demotionRest);
        }
    }
}
