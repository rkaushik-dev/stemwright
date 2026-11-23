package io.github.rkaushikdev.stemwright.domain.report;

import io.github.rkaushikdev.stemwright.domain.Grouping;
import io.github.rkaushikdev.stemwright.domain.mastery.Attempt;
import io.github.rkaushikdev.stemwright.domain.mastery.AttemptTally;
import io.github.rkaushikdev.stemwright.domain.text.Collation;
import io.github.rkaushikdev.stemwright.domain.time.CivilDates;
import io.github.rkaushikdev.stemwright.domain.time.DayRange;
import java.time.LocalDate;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * Attempts rolled up the three ways anybody ever asks for. Down the days for a progress chart, down
 * the cells for a weak spot list, and down the decks for a term report.
 *
 * <p>All three read the attempt log rather than the mastery ledger, because the ledger holds where
 * the learner is now and the log holds how they got there. A cell answered wrong four times and then
 * right is strong in the ledger and still worth a teacher's attention in the log.
 */
public final class Rollup {

    private static final int HARDEST_SHOWN = 10;

    /** Accuracies closer than this are the same accuracy, so the cell id decides between them. */
    private static final double SAME_ACCURACY = 1e-9;

    private static final Comparator<Attempt> BY_DAY_THEN_SEQUENCE =
            Comparator.comparing(Attempt::on).thenComparingInt(Attempt::sequence);

    private Rollup() {}

    /**
     * One row for every day of the range, in order, with a zero row for a day with no attempts. The
     * attempts may run from before the range: they are what tells a review from a first sighting,
     * and only the ones inside the range are counted.
     */
    public static List<DayRollup> daily(List<Attempt> attempts, DayRange range) {
        Map<LocalDate, List<Attempt>> byDay = Grouping.groupBy(inRange(attempts, range), Attempt::on);
        Map<String, LocalDate> firstSeen = new HashMap<>();
        attempts.forEach(entry -> firstSeen.merge(entry.cellId(), entry.on(), CivilDates::minCivil));
        return range.eachDay()
                .map(day -> {
                    List<Attempt> forDay = byDay.getOrDefault(day, List.of()).stream()
                            .sorted(BY_DAY_THEN_SEQUENCE)
                            .toList();
                    AttemptTally tallied = AttemptTally.of(forDay);
                    Map<String, Attempt> firstOfDay = new LinkedHashMap<>();
                    forDay.forEach(entry -> firstOfDay.putIfAbsent(entry.cellId(), entry));
                    List<Attempt> reviewed = firstOfDay.values().stream()
                            .filter(entry -> firstSeen.get(entry.cellId()).isBefore(day))
                            .toList();
                    int retained =
                            (int) reviewed.stream().filter(Attempt::wasCorrect).count();
                    return new DayRollup(
                            day, tallied, tallied.accuracy(), firstOfDay.size(), reviewed.size(), retained);
                })
                .toList();
    }

    /** One row for every cell answered, in cell id order as a reader sorts them. */
    public static List<CellRollup> byCell(List<Attempt> attempts) {
        return Grouping.groupBy(attempts, Attempt::cellId).entrySet().stream()
                .map(entry -> {
                    List<Attempt> ordered = entry.getValue().stream()
                            .sorted(BY_DAY_THEN_SEQUENCE)
                            .toList();
                    Attempt last = ordered.getLast();
                    AttemptTally tallied = AttemptTally.of(ordered);
                    return new CellRollup(entry.getKey(), tallied, tallied.accuracy(), last.verdict(), last.on());
                })
                .sorted(Comparator.comparing(CellRollup::cellId, Collation.ORDER))
                .toList();
    }

    /** {@link #hardestCells(List, int)} keeping the ten hardest. */
    public static List<CellRollup> hardestCells(List<CellRollup> rollups) {
        return hardestCells(rollups, HARDEST_SHOWN);
    }

    /**
     * Cells worth showing a teacher: most wrong answers first, ties broken by rate. A cell that was
     * never answered wrong or near is left out, and cells with the same count and the same accuracy
     * fall back to cell id order.
     *
     * @param limit how many rows to keep, the hardest first; a negative limit keeps every row but
     *     that many from the easy end
     */
    public static List<CellRollup> hardestCells(List<CellRollup> rollups, int limit) {
        List<CellRollup> ranked = rollups.stream()
                .filter(row -> row.tally().wrong() + row.tally().near() > 0)
                .sorted(Rollup::hardestFirst)
                .toList();
        int end = limit < 0 ? Math.max(ranked.size() + limit, 0) : Math.min(limit, ranked.size());
        return ranked.subList(0, end);
    }

    /** One row for every deck drilled, in deck key order as a reader sorts them. */
    public static List<DeckRollup> byDeck(List<Attempt> attempts) {
        return Grouping.groupBy(attempts, Attempt::deckKey).entrySet().stream()
                .map(entry -> {
                    AttemptTally tallied = AttemptTally.of(entry.getValue());
                    return new DeckRollup(
                            entry.getKey(), tallied, tallied.accuracy(), distinct(entry.getValue(), Attempt::on));
                })
                .sorted(Comparator.comparing(DeckRollup::deckKey, Collation.ORDER))
                .toList();
    }

    /**
     * Days the learner answered anything, which is what a streak is counted over. Each day appears
     * once, in the order it first appears in the log.
     */
    public static Set<LocalDate> activeDays(List<Attempt> attempts) {
        Set<LocalDate> days = new LinkedHashSet<>();
        attempts.forEach(entry -> days.add(entry.on()));
        return Collections.unmodifiableSet(days);
    }

    /** The earliest day the cell was answered correctly, or nothing when it never was. */
    public static Optional<LocalDate> firstCorrectOn(List<Attempt> attempts, String cellId) {
        return attempts.stream()
                .filter(entry -> entry.cellId().equals(cellId) && entry.wasCorrect())
                .map(Attempt::on)
                .min(Comparator.naturalOrder());
    }

    /** The attempts made on a day inside the range, in log order. */
    public static List<Attempt> inRange(List<Attempt> attempts, DayRange range) {
        return attempts.stream().filter(entry -> range.contains(entry.on())).toList();
    }

    private static int hardestFirst(CellRollup a, CellRollup b) {
        int byWrong = Integer.compare(b.tally().wrong(), a.tally().wrong());
        if (byWrong != 0) {
            return byWrong;
        }
        double byAccuracy = a.accuracy() - b.accuracy();
        return Math.abs(byAccuracy) > SAME_ACCURACY
                ? Double.compare(a.accuracy(), b.accuracy())
                : Collation.compare(a.cellId(), b.cellId());
    }

    private static <K> int distinct(List<Attempt> attempts, Function<Attempt, K> key) {
        return (int) attempts.stream().map(key).distinct().count();
    }
}
