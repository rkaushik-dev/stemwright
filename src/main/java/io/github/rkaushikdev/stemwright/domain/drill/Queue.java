package io.github.rkaushikdev.stemwright.domain.drill;

import io.github.rkaushikdev.stemwright.domain.features.FeatureDimension;
import io.github.rkaushikdev.stemwright.domain.features.FeatureSchema;
import io.github.rkaushikdev.stemwright.domain.mastery.CellStrength;
import io.github.rkaushikdev.stemwright.domain.mastery.MasteryLedger;
import io.github.rkaushikdev.stemwright.domain.paradigm.Cell;
import io.github.rkaushikdev.stemwright.domain.time.CivilDates;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * The day's questions. Everything before this decided what could be asked; the queue decides what
 * is asked and in what order, and it says why for each cell it left out, because "the queue is short
 * today" is a question every learner asks and a system that cannot answer it gets blamed for losing
 * their work.
 *
 * @param on the day the queue was built for
 * @param deckKey the key of the deck it was built from
 * @param entries the questions, in the order they are asked
 * @param excluded why each cell of the membership that is not asked was left out, by cell id
 */
public record Queue(LocalDate on, String deckKey, List<QueueEntry> entries, Map<String, ExclusionReason> excluded) {

    public Queue {
        Objects.requireNonNull(on, "on");
        Objects.requireNonNull(deckKey, "deckKey");
        entries = List.copyOf(entries);
        excluded = Collections.unmodifiableMap(new LinkedHashMap<>(excluded));
    }

    /** The day's queue for a new sitting, with rows running across the schema's last dimension. */
    public static Queue assemble(
            DrillContext context, Membership membership, DrillPolicy policy, MasteryLedger ledger, LocalDate on) {
        return assemble(context, membership, policy, ledger, on, QueueOptions.DEFAULT);
    }

    /**
     * Reviews are placed before new cells, and within reviews the ones furthest past their own
     * interval go first, as {@link MasteryLedger#dueCells} orders them, so a short review budget is
     * spent on the cells closest to being forgotten. New cells are only introduced once the review
     * budget has had its chance, which is the whole point of having two budgets: a learner who has
     * fallen behind should not be handed more to fall behind on.
     *
     * <p>Every cell of the membership ends up either asked or excluded with one reason. On a rest
     * day every cell is excluded as such and nothing is asked.
     */
    public static Queue assemble(
            DrillContext context,
            Membership membership,
            DrillPolicy policy,
            MasteryLedger ledger,
            LocalDate on,
            QueueOptions options) {
        Map<String, ExclusionReason> excluded = new LinkedHashMap<>();
        Map<String, Cell> byId = Cell.index(membership.cells());

        if (!policy.allowsDay(CivilDates.weekday(on))) {
            for (Cell cell : membership.cells()) {
                excluded.put(cell.id(), ExclusionReason.REST_DAY);
            }
            return new Queue(on, membership.deckKey(), List.of(), excluded);
        }

        List<String> cellIds = membership.cells().stream().map(Cell::id).toList();
        List<QueueEntry> candidates = new ArrayList<>();
        for (CellStrength strength : ledger.dueCells(cellIds, on)) {
            candidates.add(
                    new QueueEntry(byId.get(strength.cellId()), QueueEntry.Kind.REVIEW, strength.daysOverdue(on)));
        }
        for (CellStrength strength : ledger.newCells(cellIds)) {
            candidates.add(new QueueEntry(byId.get(strength.cellId()), QueueEntry.Kind.NEW, 0));
        }

        for (Cell cell : membership.cells()) {
            if (ledger.strengthFor(cell.id()).suspended()) {
                excluded.put(cell.id(), ExclusionReason.SUSPENDED);
            }
        }

        String rowAcross = options.rowAcross().orElseGet(() -> lastDimensionOf(context.schema()));
        Assembly assembly = new Assembly(context.schema(), policy, options.buried(), rowAcross, excluded);
        for (QueueEntry candidate : candidates) {
            if (!excluded.containsKey(candidate.cell().id())) {
                assembly.consider(candidate);
            }
        }

        Set<String> queued = new HashSet<>();
        assembly.entries.forEach(entry -> queued.add(entry.cell().id()));
        for (Cell cell : membership.cells()) {
            if (!excluded.containsKey(cell.id()) && !queued.contains(cell.id())) {
                excluded.put(cell.id(), ExclusionReason.NOT_DUE);
            }
        }

        return new Queue(on, membership.deckKey(), assembly.entries, excluded);
    }

    private static String lastDimensionOf(FeatureSchema schema) {
        List<FeatureDimension> dimensions = schema.dimensions();
        return dimensions.isEmpty() ? "" : dimensions.get(dimensions.size() - 1).key();
    }

    /** How many questions the queue asks. */
    public int length() {
        return entries.size();
    }

    public int reviewCount() {
        return countOf(QueueEntry.Kind.REVIEW);
    }

    public int newCount() {
        return countOf(QueueEntry.Kind.NEW);
    }

    private int countOf(QueueEntry.Kind kind) {
        return (int) entries.stream().filter(entry -> entry.kind() == kind).count();
    }

    /** The ids of the cells left out for the reason, in natural string order. */
    public List<String> excludedFor(ExclusionReason reason) {
        return excluded.entrySet().stream()
                .filter(entry -> entry.getValue() == reason)
                .map(Map.Entry::getKey)
                .sorted()
                .toList();
    }

    /** Why the cell was left out, or nothing when it is asked or was never in the membership. */
    public Optional<ExclusionReason> whyExcluded(String cellId) {
        return Optional.ofNullable(excluded.get(cellId));
    }

    /** The cells asked, in the order they are asked. */
    public List<Cell> cells() {
        return entries.stream().map(QueueEntry::cell).toList();
    }

    /**
     * The running state of one assembly: what has been taken so far, against which the budgets, the
     * lexeme cap and the spacing rule are judged for the next candidate.
     */
    private static final class Assembly {

        private final FeatureSchema schema;
        private final DrillPolicy policy;
        private final BurialSet buried;
        private final String rowAcross;
        private final Map<String, ExclusionReason> excluded;
        private final List<QueueEntry> entries = new ArrayList<>();
        private final List<Cell> asked = new ArrayList<>();
        private final Map<String, Integer> perLexeme = new HashMap<>();
        private int reviewsTaken;
        private int newTaken;

        private Assembly(
                FeatureSchema schema,
                DrillPolicy policy,
                BurialSet buried,
                String rowAcross,
                Map<String, ExclusionReason> excluded) {
            this.schema = schema;
            this.policy = policy;
            this.buried = buried;
            this.rowAcross = rowAcross;
            this.excluded = excluded;
        }

        /** Takes the candidate, or records the first rule that leaves it out. */
        private void consider(QueueEntry candidate) {
            Cell cell = candidate.cell();
            Optional<ExclusionReason> reason = reasonToLeaveOut(candidate);
            if (reason.isPresent()) {
                excluded.put(cell.id(), reason.get());
                return;
            }

            entries.add(candidate);
            asked.add(cell);
            perLexeme.merge(cell.lexemeId(), 1, Integer::sum);
            if (candidate.kind() == QueueEntry.Kind.REVIEW) {
                reviewsTaken += 1;
            } else {
                newTaken += 1;
            }
        }

        private Optional<ExclusionReason> reasonToLeaveOut(QueueEntry candidate) {
            Cell cell = candidate.cell();
            if (buried.isBuried(cell.id())) {
                return Optional.of(ExclusionReason.BURIED);
            }
            if (candidate.kind() == QueueEntry.Kind.REVIEW && reviewsTaken >= policy.reviewsPerDay()) {
                return Optional.of(ExclusionReason.REVIEW_BUDGET);
            }
            if (candidate.kind() == QueueEntry.Kind.NEW && newTaken >= policy.newPerDay()) {
                return Optional.of(ExclusionReason.NEW_BUDGET);
            }
            if (perLexeme.getOrDefault(cell.lexemeId(), 0) >= policy.perLexemeCap()) {
                return Optional.of(ExclusionReason.LEXEME_CAP);
            }
            if (!RowSpacing.respectsSpacing(schema, asked, cell, rowAcross, policy.rowSpacing())) {
                return Optional.of(ExclusionReason.ROW_SPACING);
            }
            return Optional.empty();
        }
    }
}
