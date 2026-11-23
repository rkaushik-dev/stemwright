package io.github.rkaushikdev.stemwright.domain.report;

import java.time.LocalDate;
import java.util.Objects;

/**
 * One day of a forecast: how many reviews fall due on it as the ledger stands, how many earlier
 * days left waiting, and how many of the two the day can actually ask.
 *
 * @param on the study day
 * @param due how many cells fall due that day, the first day also holding everything already overdue
 * @param overCeiling whether the day is a drilling day and its due and carried reviews together are
 *     more than the policy allows in a day
 * @param resting whether the policy rests on that weekday, so the reviews spill onto the next day
 * @param carried how many reviews earlier days of the range could not ask, by resting or by running
 *     into the ceiling
 * @param asked how many reviews the day asks, its due and carried reviews up to the ceiling, and none
 *     on a rest day
 */
public record ForecastDay(LocalDate on, int due, boolean overCeiling, boolean resting, int carried, int asked) {

    public ForecastDay {
        Objects.requireNonNull(on, "on");
    }
}
