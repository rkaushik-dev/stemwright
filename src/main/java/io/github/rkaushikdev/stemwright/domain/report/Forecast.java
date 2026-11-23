package io.github.rkaushikdev.stemwright.domain.report;

import io.github.rkaushikdev.stemwright.domain.drill.DrillPolicy;
import io.github.rkaushikdev.stemwright.domain.mastery.CellStrength;
import io.github.rkaushikdev.stemwright.domain.mastery.Ladder;
import io.github.rkaushikdev.stemwright.domain.mastery.MasteryLedger;
import io.github.rkaushikdev.stemwright.domain.paradigm.Cell;
import io.github.rkaushikdev.stemwright.domain.time.CivilDates;
import io.github.rkaushikdev.stemwright.domain.time.DayRange;
import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * How much work is coming. A forecast is not a plan: it reads the due dates already written down
 * and says how many fall on each day, without pretending to know whether the learner will get them
 * right.
 *
 * <p>The optimistic projection does pretend, and says so in its name. It assumes every review is
 * answered correctly on the day it falls due and schedules it the way a right answer is scheduled,
 * which gives the lightest possible future and is useful only as a floor.
 */
public final class Forecast {

    private static final Comparator<ForecastDay> BUSIEST_FIRST = Comparator.comparingInt(
                    (ForecastDay day) -> day.due() + day.carried())
            .reversed()
            .thenComparing(ForecastDay::on);

    private Forecast() {}

    /**
     * One row for every day of the range, in order, counting the cells that fall due on it. A cell
     * already overdue when the range starts is counted on its first day, and a suspended cell or one
     * nobody has answered is not counted at all.
     *
     * <p>Reviews a day cannot ask are carried to the next one: everything due on a rest day, and on
     * a drilling day whatever is past the review ceiling. A Monday after a resting weekend therefore
     * shows the weekend's reviews waiting beside its own, which is the morning the learner actually
     * has.
     */
    public static List<ForecastDay> daily(List<Cell> cells, MasteryLedger ledger, DayRange range, DrillPolicy policy) {
        Map<LocalDate, Integer> counts = new HashMap<>();
        for (Cell cell : cells) {
            CellStrength strength = ledger.strengthFor(cell.id());
            if (strength.suspended()) {
                continue;
            }
            strength.dueOn().ifPresent(due -> counts.merge(CivilDates.maxCivil(due, range.from()), 1, Integer::sum));
        }

        List<ForecastDay> days = new ArrayList<>();
        int waiting = 0;
        for (LocalDate day : range.days()) {
            int due = counts.getOrDefault(day, 0);
            boolean resting = !policy.allowsDay(CivilDates.weekday(day));
            int load = waiting + due;
            int asked = resting ? 0 : Math.min(load, policy.reviewsPerDay());
            days.add(new ForecastDay(day, due, !resting && load > policy.reviewsPerDay(), resting, waiting, asked));
            waiting = load - asked;
        }
        return List.copyOf(days);
    }

    /**
     * The day with the most waiting, its own reviews and the ones carried onto it together, the
     * earliest of several equally busy; nothing for no days.
     */
    public static Optional<ForecastDay> busiestDay(List<ForecastDay> days) {
        return days.stream().min(BUSIEST_FIRST);
    }

    public static int totalDue(List<ForecastDay> days) {
        return days.stream().mapToInt(ForecastDay::due).sum();
    }

    public static List<ForecastDay> daysOverCeiling(List<ForecastDay> days) {
        return days.stream().filter(ForecastDay::overCeiling).toList();
    }

    /** Reviews due on a day the policy rests, which {@link #daily} carries onto the next drilling day. */
    public static int spilledOnRestDays(List<ForecastDay> days) {
        return days.stream()
                .filter(ForecastDay::resting)
                .mapToInt(ForecastDay::due)
                .sum();
    }

    /**
     * Walks forward assuming every answer is right, introducing new cells at the policy budget in the
     * order given. Each review is answered on the first day it is due and scheduled exactly as a
     * right answer is, by {@link CellStrength#recordCorrect}, so a cell already overdue when the
     * range starts climbs by the gap it was held over and the curve takes its shape from the rungs
     * rather than from a decay model. A rest day does nothing at all, and a suspended cell takes no
     * part.
     */
    public static List<ProjectionDay> optimisticProjection(
            List<Cell> cells, MasteryLedger ledger, Ladder ladder, DrillPolicy policy, DayRange range) {
        List<CellStrength> pending = new ArrayList<>();
        ArrayDeque<String> unseen = new ArrayDeque<>();

        for (Cell cell : cells) {
            CellStrength strength = ledger.strengthFor(cell.id());
            if (strength.suspended()) {
                continue;
            }
            if (strength.isNew()) {
                unseen.add(cell.id());
            } else {
                pending.add(strength);
            }
        }

        List<ProjectionDay> days = new ArrayList<>();
        for (LocalDate day : range.days()) {
            if (!policy.allowsDay(CivilDates.weekday(day))) {
                days.add(new ProjectionDay(day, 0, 0));
                continue;
            }

            int reviews = 0;
            for (int index = 0; index < pending.size(); index += 1) {
                CellStrength strength = pending.get(index);
                if (strength.isDue(day)) {
                    reviews += 1;
                    pending.set(index, strength.recordCorrect(ladder, day));
                }
            }

            int introductions = Math.min(policy.newPerDay(), unseen.size());
            for (int index = 0; index < introductions; index += 1) {
                pending.add(CellStrength.fresh(unseen.removeFirst()).recordCorrect(ladder, day));
            }

            days.add(new ProjectionDay(day, reviews, introductions));
        }

        return List.copyOf(days);
    }

    /**
     * How many days pass before the first day with nothing to review and nothing to introduce, the
     * whole span when no day is quiet.
     */
    public static int daysToClearBacklog(List<ProjectionDay> days) {
        for (int index = 0; index < days.size(); index += 1) {
            ProjectionDay day = days.get(index);
            if (day.introductions() == 0 && day.reviews() == 0) {
                return index;
            }
        }
        return days.size();
    }
}
