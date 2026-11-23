package io.github.rkaushikdev.stemwright.app.services;

import io.github.rkaushikdev.stemwright.domain.report.CellRollup;
import io.github.rkaushikdev.stemwright.domain.report.DayRollup;
import io.github.rkaushikdev.stemwright.domain.time.DayRange;
import java.util.List;
import java.util.Objects;

/**
 * What one learner did over a range of days, read from the attempt log rather than the ledger: a
 * row for each day, the cells worth a teacher's attention, and how much of what came back for review
 * was remembered.
 *
 * @param learnerId the learner reported on
 * @param range the days reported on, both ends included
 * @param days one row for every day of the range, in order, a zero row for a day with no answers
 * @param hardest at most ten of the cells answered wrong or near over the range, the hardest first
 * @param retention the share of the range's reviews the learner recalled at the first answer of the
 *     day, nought when the range holds no review; first sightings and same day relearning are not
 *     reviews, which is why this is not the accuracy
 */
public record ActivityReport(
        String learnerId, DayRange range, List<DayRollup> days, List<CellRollup> hardest, double retention) {

    public ActivityReport {
        Objects.requireNonNull(learnerId, "learnerId");
        Objects.requireNonNull(range, "range");
        days = List.copyOf(days);
        hardest = List.copyOf(hardest);
    }
}
