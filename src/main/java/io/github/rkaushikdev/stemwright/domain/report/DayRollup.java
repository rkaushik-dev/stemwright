package io.github.rkaushikdev.stemwright.domain.report;

import io.github.rkaushikdev.stemwright.domain.mastery.AttemptTally;
import java.time.LocalDate;
import java.util.Objects;

/**
 * One day of a learner's attempts, the row a progress chart plots.
 *
 * @param on the study day
 * @param tally how that day's attempts were marked
 * @param accuracy the share of them that were correct, zero on a day with none
 * @param distinctCells how many different cells were answered, however many times each
 * @param reviews how many of the day's cells had been answered on an earlier day, which is what a
 *     review is: a cell met for the first time, or relearnt the same day, tests nothing about recall
 * @param retained how many of those reviewed cells were right at their first answer of the day
 */
public record DayRollup(
        LocalDate on, AttemptTally tally, double accuracy, int distinctCells, int reviews, int retained) {

    public DayRollup {
        Objects.requireNonNull(on, "on");
        Objects.requireNonNull(tally, "tally");
    }
}
