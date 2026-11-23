package io.github.rkaushikdev.stemwright.domain.mastery;

import io.github.rkaushikdev.stemwright.domain.text.Collation;
import java.time.LocalDate;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * One learner's whole state, keyed by cell id. Absence is meaningful and it is the common case: a
 * lexicon of four hundred nouns is thirty thousand cells, and a learner in week three has answered
 * perhaps sixty of them. Storing a row for every unanswered cell would be storing the paradigm twice.
 *
 * <p>So {@link #strengthFor} invents a fresh strength rather than failing, and nothing downstream is
 * allowed to tell the difference between a cell that was never stored and one stored in its initial
 * state.
 *
 * <p>A ledger is immutable. Every change returns a new ledger and leaves this one as it was.
 *
 * @param learnerId the learner the ledger belongs to
 * @param byCell the stored strengths keyed by cell id, in the order they were first stored
 */
public record MasteryLedger(String learnerId, Map<String, CellStrength> byCell) {

    private static final Comparator<CellStrength> BY_CELL = Comparator.comparing(CellStrength::cellId, Collation.ORDER);

    public MasteryLedger {
        Objects.requireNonNull(learnerId, "learnerId");
        byCell = Collections.unmodifiableMap(new LinkedHashMap<>(byCell));
    }

    public static MasteryLedger empty(String learnerId) {
        return new MasteryLedger(learnerId, Map.of());
    }

    /** Keys the strengths by cell, a later strength for the same cell replacing an earlier one. */
    public static MasteryLedger of(String learnerId, List<CellStrength> entries) {
        Map<String, CellStrength> byCell = new LinkedHashMap<>();
        for (CellStrength entry : entries) {
            byCell.put(entry.cellId(), entry);
        }
        return new MasteryLedger(learnerId, byCell);
    }

    /** The stored strength, or a fresh one for a cell nobody has answered. */
    public CellStrength strengthFor(String cellId) {
        CellStrength stored = byCell.get(cellId);
        return stored == null ? CellStrength.fresh(cellId) : stored;
    }

    public boolean hasStrength(String cellId) {
        return byCell.containsKey(cellId);
    }

    public MasteryLedger withStrength(CellStrength strength) {
        return withStrengths(List.of(strength));
    }

    public MasteryLedger withStrengths(List<CellStrength> strengths) {
        Map<String, CellStrength> next = new LinkedHashMap<>(byCell);
        for (CellStrength strength : strengths) {
            next.put(strength.cellId(), strength);
        }
        return new MasteryLedger(learnerId, next);
    }

    /** Drops a cell's strength, returning this very ledger when it held none. */
    public MasteryLedger forgetCell(String cellId) {
        if (!byCell.containsKey(cellId)) {
            return this;
        }
        Map<String, CellStrength> next = new LinkedHashMap<>(byCell);
        next.remove(cellId);
        return new MasteryLedger(learnerId, next);
    }

    /**
     * Due cells among the ones asked about, the furthest past their own interval first. The
     * candidate list comes from the caller because what is due depends on which deck is being
     * drilled, not on everything the learner has ever seen.
     *
     * <p>Lateness is measured against the interval the cell was waiting out rather than in days. A
     * cell on a one day interval that is two days late is twice its interval behind and close to
     * being forgotten; one on a hundred day interval that is three days late has barely moved, and
     * a queue ordered by days alone would ask it first. Equal shares fall back to the days overdue,
     * then to the cell id.
     */
    public List<CellStrength> dueCells(List<String> cellIds, LocalDate on) {
        Comparator<CellStrength> furthestBehindFirst = Comparator.comparingDouble(
                        (CellStrength strength) -> strength.overdueShare(on))
                .reversed()
                .thenComparing(Comparator.comparingInt((CellStrength strength) -> strength.daysOverdue(on))
                        .reversed())
                .thenComparing(BY_CELL);
        return strengthsOf(cellIds, strength -> strength.isDue(on), furthestBehindFirst);
    }

    public List<CellStrength> newCells(List<String> cellIds) {
        return strengthsOf(cellIds, CellStrength::isNew, BY_CELL);
    }

    public List<CellStrength> suspendedCells(List<String> cellIds) {
        return strengthsOf(cellIds, CellStrength::suspended, BY_CELL);
    }

    private List<CellStrength> strengthsOf(
            List<String> cellIds, Predicate<CellStrength> keep, Comparator<CellStrength> order) {
        return cellIds.stream()
                .map(this::strengthFor)
                .filter(keep)
                .sorted(order)
                .toList();
    }

    public LedgerCounts counts(List<String> cellIds, Ladder ladder, LocalDate on) {
        int introduced = 0;
        int due = 0;
        int mature = 0;
        int suspended = 0;
        int untouched = 0;

        for (String cellId : cellIds) {
            CellStrength strength = strengthFor(cellId);
            if (strength.suspended()) {
                suspended += 1;
                continue;
            }
            if (strength.isNew()) {
                untouched += 1;
                continue;
            }
            introduced += 1;
            if (strength.isDue(on)) {
                due += 1;
            }
            if (ladder.isAtTop(strength.rung())) {
                mature += 1;
            }
        }

        return new LedgerCounts(cellIds.size(), introduced, due, mature, suspended, untouched);
    }

    /** The next day anything in the list comes round, or nothing if none will. */
    public Optional<LocalDate> nextDueDate(List<String> cellIds) {
        LocalDate soonest = null;
        for (String cellId : cellIds) {
            CellStrength strength = strengthFor(cellId);
            if (strength.suspended() || strength.dueOn().isEmpty()) {
                continue;
            }
            LocalDate due = strength.dueOn().orElseThrow();
            if (soonest == null || due.isBefore(soonest)) {
                soonest = due;
            }
        }
        return Optional.ofNullable(soonest);
    }

    /** How many strengths are stored, which is not how many cells the learner could be asked. */
    public int size() {
        return byCell.size();
    }
}
