package io.github.rkaushikdev.stemwright.domain.mastery;

import io.github.rkaushikdev.stemwright.domain.time.CivilDates;
import java.time.LocalDate;
import java.util.Objects;
import java.util.Optional;

/**
 * What the desk knows about one learner and one cell. Everything here is derived from answers
 * rather than set by hand, and it is deliberately small: a rung on a ladder, a due date, and enough
 * counting to spot a leech.
 *
 * <p>{@code rung} and {@code dueOn} are separate because they answer different questions. The rung
 * says how well the cell is known, the due date says when to ask again, and a shielded day or a
 * suspension moves the second without touching the first. Collapsing them into one number is how a
 * system ends up punishing a learner for a week away.
 *
 * <p>Every operation returns a new strength. One that changes nothing returns this very instance,
 * which callers rely on to tell a change from no change.
 *
 * @param cellId the cell this strength belongs to
 * @param rung how far up the ladder the cell has climbed
 * @param dueOn when to ask again, absent for a cell that has never been answered
 * @param seen how many times the cell has been answered
 * @param correct how many of those answers were right
 * @param lapses how many of those answers were wrong
 * @param streak how many answers in a row were right, up to the latest
 * @param lapseRun how many lapses since the latest right answer, which a near miss neither adds to
 *     nor clears
 * @param lastSeenOn the day of the latest answer, absent for a cell that has never been answered
 * @param suspended whether the cell is out of the queue until somebody brings it back
 */
public record CellStrength(
        String cellId,
        int rung,
        Optional<LocalDate> dueOn,
        int seen,
        int correct,
        int lapses,
        int streak,
        int lapseRun,
        Optional<LocalDate> lastSeenOn,
        boolean suspended) {

    public CellStrength {
        Objects.requireNonNull(cellId, "cellId");
        Objects.requireNonNull(dueOn, "dueOn");
        Objects.requireNonNull(lastSeenOn, "lastSeenOn");
    }

    /** A cell nobody has answered: unseen, unscheduled and at the bottom rung. */
    public static CellStrength fresh(String cellId) {
        return new CellStrength(cellId, 0, Optional.empty(), 0, 0, 0, 0, 0, Optional.empty(), false);
    }

    public boolean isIntroduced() {
        return seen > 0;
    }

    /** A cell with no due date has never been answered, so it is not due, it is new. */
    public boolean isDue(LocalDate on) {
        return !suspended && dueOn.filter(due -> !due.isAfter(on)).isPresent();
    }

    public boolean isNew() {
        return !suspended && dueOn.isEmpty();
    }

    /** How many days past its due date the cell is on the given day, never below zero. */
    public int daysOverdue(LocalDate on) {
        return dueOn.map(due -> Math.max(0, CivilDates.daysBetween(due, on))).orElse(0);
    }

    /** The share of sightings that were right, zero for a cell nobody has answered. */
    public double accuracy() {
        return seen == 0 ? 0 : (double) correct / seen;
    }

    /**
     * Climbs by the gap the learner actually held the form over, and schedules from the spread
     * interval of the rung it lands on.
     *
     * <p>A cell answered on the day it comes due climbs one rung. One answered late has been held
     * for longer than its interval, so it goes up to the highest rung whose interval that gap covers
     * when that is further than one rung. One answered early, on a later day than it was last seen
     * but before it was due, has been held for less, so it keeps its rung and is never brought
     * forward. The first sighting does not climb, and nor does a second sighting on the same day,
     * which says nothing about how long the form is held.
     */
    public CellStrength recordCorrect(Ladder ladder, LocalDate on) {
        if (!isIntroduced()) {
            return sighted(on, rung, ladder.spreadInterval(rung, cellId), Sighting.RIGHT);
        }
        int gap = lastSeenOn.map(seenOn -> CivilDates.daysBetween(seenOn, on)).orElse(0);
        if (gap > 0 && dueOn.filter(on::isBefore).isPresent()) {
            LocalDate due = CivilDates.maxCivil(
                    dueOn.orElseThrow(), CivilDates.addDays(on, ladder.spreadInterval(rung, cellId)));
            return sighted(on, rung, CivilDates.daysBetween(on, due), Sighting.RIGHT);
        }
        int next = Math.max(ladder.climb(rung), ladder.rungHeldFor(gap));
        return sighted(on, next, ladder.spreadInterval(next, cellId), Sighting.RIGHT);
    }

    /** Drops by the ladder's lapse and comes back after the shortest interval. */
    public CellStrength recordLapse(Ladder ladder, LocalDate on) {
        return sighted(on, ladder.lapse(rung), ladder.intervalAt(0), Sighting.LAPSE);
    }

    /**
     * A near miss neither climbs nor lapses. The cell comes back at the same rung after the shortest
     * interval, which is what a teacher does with an answer that was almost right rather than either
     * rewarding or punishing it.
     */
    public CellStrength recordNearMiss(Ladder ladder, LocalDate on) {
        return sighted(on, rung, ladder.intervalAt(0), Sighting.NEAR);
    }

    /** The cell answered on the day, placed at the rung and due the given number of days later. */
    private CellStrength sighted(LocalDate on, int nextRung, int interval, Sighting sighting) {
        boolean right = sighting == Sighting.RIGHT;
        boolean lapsed = sighting == Sighting.LAPSE;
        return new CellStrength(
                cellId,
                nextRung,
                Optional.of(CivilDates.addDays(on, interval)),
                seen + 1,
                right ? correct + 1 : correct,
                lapsed ? lapses + 1 : lapses,
                right ? streak + 1 : 0,
                right ? 0 : lapsed ? lapseRun + 1 : lapseRun,
                Optional.of(on),
                suspended);
    }

    public CellStrength suspend() {
        return held(rung, dueOn, true);
    }

    /** Brings a suspended cell back, scheduled from the rung it kept; any other cell is left alone. */
    public CellStrength unsuspend(Ladder ladder, LocalDate on) {
        if (!suspended) {
            return this;
        }
        return held(rung, Optional.of(CivilDates.addDays(on, ladder.intervalAt(rung))), false);
    }

    /**
     * Pushes the due date out without touching the rung. Used for a shielded day and for a deferral,
     * and it never pulls a due date forward, because a cell that was already overdue should not be
     * made more overdue by a rest day.
     */
    public CellStrength deferTo(LocalDate on) {
        if (dueOn.filter(due -> due.isBefore(on)).isEmpty()) {
            return this;
        }
        return held(rung, Optional.of(on), suspended);
    }

    /** The same cell placed at another rung and due on another day, with its counts untouched. */
    CellStrength withSchedule(int nextRung, LocalDate due) {
        return held(nextRung, Optional.of(due), suspended);
    }

    /** The same counts and the same latest sighting under another schedule. */
    private CellStrength held(int nextRung, Optional<LocalDate> due, boolean out) {
        return new CellStrength(cellId, nextRung, due, seen, correct, lapses, streak, lapseRun, lastSeenOn, out);
    }

    public boolean isMature(Ladder ladder) {
        return ladder.isAtTop(rung);
    }

    public String describe(Ladder ladder) {
        if (suspended) {
            return "suspended after " + lapses + " lapses";
        }
        // Past the suspension check, a cell without a due date is exactly a new one.
        return dueOn.map(due -> "rung " + rung + " of " + (ladder.rungs().size() - 1) + ", next on " + due)
                .orElse("not introduced yet");
    }

    /** How one answer went, as far as the counts are concerned. */
    private enum Sighting {
        RIGHT,
        NEAR,
        LAPSE
    }
}
