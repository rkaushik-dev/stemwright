package io.github.rkaushikdev.stemwright.app.services;

import io.github.rkaushikdev.stemwright.app.Clock;
import io.github.rkaushikdev.stemwright.app.Stores;
import io.github.rkaushikdev.stemwright.domain.course.Actor;
import io.github.rkaushikdev.stemwright.domain.course.AssignmentProgress;
import io.github.rkaushikdev.stemwright.domain.course.CourseScope;
import io.github.rkaushikdev.stemwright.domain.drill.DrillPolicy;
import io.github.rkaushikdev.stemwright.domain.drill.DrillPolicyPatch;
import io.github.rkaushikdev.stemwright.domain.drill.Membership;
import io.github.rkaushikdev.stemwright.domain.errors.ForbiddenException;
import io.github.rkaushikdev.stemwright.domain.errors.NotFoundException;
import io.github.rkaushikdev.stemwright.domain.errors.ValidationException;
import io.github.rkaushikdev.stemwright.domain.mastery.Attempt;
import io.github.rkaushikdev.stemwright.domain.mastery.Ladder;
import io.github.rkaushikdev.stemwright.domain.mastery.MasteryLedger;
import io.github.rkaushikdev.stemwright.domain.paradigm.Cell;
import io.github.rkaushikdev.stemwright.domain.report.CohortReport;
import io.github.rkaushikdev.stemwright.domain.report.Coverage;
import io.github.rkaushikdev.stemwright.domain.report.CoverageRow;
import io.github.rkaushikdev.stemwright.domain.report.DayRollup;
import io.github.rkaushikdev.stemwright.domain.report.Forecast;
import io.github.rkaushikdev.stemwright.domain.report.Rollup;
import io.github.rkaushikdev.stemwright.domain.time.DayRange;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/**
 * Reporting, gathered in one place so that no route has to know which of the four report modules
 * answers which question. Every method that names a learner checks first, and the check needs a
 * scope, so a report on somebody else is impossible to build by forgetting an argument.
 */
public final class ReportService {

    private final Stores stores;
    private final Clock clock;
    private final DrillService drill;

    /**
     * @param drill the drill service a deck's membership is resolved through
     */
    public ReportService(Stores stores, Clock clock, DrillService drill) {
        this.stores = Objects.requireNonNull(stores, "stores");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.drill = Objects.requireNonNull(drill, "drill");
    }

    /** The learner's report on the deck against the standard ladder and the default drill policy. */
    public LearnerReport learner(
            Actor actor, CourseScope scope, String learnerId, String language, String deckKey, DayRange range) {
        return learner(actor, scope, learnerId, language, deckKey, range, Ladder.STANDARD, DrillPolicyPatch.EMPTY);
    }

    /** The learner's report on the deck against the ladder and the default drill policy. */
    public LearnerReport learner(
            Actor actor,
            CourseScope scope,
            String learnerId,
            String language,
            String deckKey,
            DayRange range,
            Ladder ladder) {
        return learner(actor, scope, learnerId, language, deckKey, range, ladder, DrillPolicyPatch.EMPTY);
    }

    /**
     * Where the learner stands on the deck. The counts and the coverage are read on the first day of
     * the range, against the deck's membership on that day and mastery on the ladder; the forecast
     * covers every day of the range under the default drill policy with the patch applied, which
     * decides the review ceiling and the rest days.
     *
     * @param ladder the ladder mastery is measured against; pass {@link Ladder#STANDARD} to patch the
     *     policy alone
     * @throws ForbiddenException when the actor may not read the learner's work on the course
     * @throws NotFoundException for the language, its profile book or the deck
     * @throws ValidationException when the patched policy is unusable
     */
    public LearnerReport learner(
            Actor actor,
            CourseScope scope,
            String learnerId,
            String language,
            String deckKey,
            DayRange range,
            Ladder ladder,
            DrillPolicyPatch policy) {
        actor.requireReadLearner(learnerId, scope);
        Membership membership = drill.membership(language, deckKey, range.from());
        MasteryLedger ledger = stores.ledgers().get(learnerId).orElseGet(() -> MasteryLedger.empty(learnerId));
        List<Cell> cells = membership.cells();
        List<String> cellIds = cells.stream().map(Cell::id).toList();
        List<CoverageRow> byBundle = Coverage.byBundle(cells, ledger, ladder, range.from());

        return new LearnerReport(
                learnerId,
                deckKey,
                range.from(),
                ledger.counts(cellIds, ladder, range.from()),
                byBundle,
                Coverage.byLexeme(cells, ledger, ladder, range.from()),
                Coverage.weakestRows(byBundle),
                Forecast.daily(cells, ledger, range, DrillPolicy.of(policy)));
    }

    /**
     * What the learner answered over the range, day by day, the cells they found hardest, and how
     * many of the reviews they recalled. The log before the range is read too, because it is what
     * tells a review from a cell met for the first time.
     *
     * @throws ForbiddenException when the actor may not read the learner's work on the course
     */
    public ActivityReport activity(Actor actor, CourseScope scope, String learnerId, DayRange range) {
        actor.requireReadLearner(learnerId, scope);
        List<Attempt> log = stores.attempts().listByLearner(learnerId).stream()
                .filter(entry -> !entry.on().isAfter(range.to()))
                .toList();
        List<DayRollup> days = Rollup.daily(log, range);
        int reviews = days.stream().mapToInt(DayRollup::reviews).sum();
        int retained = days.stream().mapToInt(DayRollup::retained).sum();
        return new ActivityReport(
                learnerId,
                range,
                days,
                Rollup.hardestCells(Rollup.byCell(Rollup.inRange(log, range))),
                reviews == 0 ? 0 : (double) retained / reviews);
    }

    /** The cohort report over the progress rows, dated today. */
    public CohortReport cohort(Actor actor, CourseScope scope, List<AssignmentProgress> rows, String assignmentKey) {
        return cohort(actor, scope, rows, assignmentKey, clock.today());
    }

    /**
     * The cohort report the actor may see over progress rows read elsewhere, dated the day given. It
     * refuses nobody: a row the actor may not read is counted as hidden rather than shown.
     */
    public CohortReport cohort(
            Actor actor, CourseScope scope, List<AssignmentProgress> rows, String assignmentKey, LocalDate on) {
        return CohortReport.of(actor, scope, rows, assignmentKey, on);
    }
}
