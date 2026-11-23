package io.github.rkaushikdev.stemwright.domain.mastery;

import io.github.rkaushikdev.stemwright.domain.Identifiers;
import io.github.rkaushikdev.stemwright.domain.errors.ProblemCollector;
import io.github.rkaushikdev.stemwright.domain.text.Numbers;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Intervals come from a ladder of named rungs rather than from a formula. A ladder is something a
 * course can print and argue about, which a decay constant is not, and a department that wants the
 * first review the next morning can say so without anybody deriving what that implies.
 *
 * <p>The rungs are days. Climbing goes up one rung, a lapse drops back by a configured number of
 * rungs rather than to the bottom, because dropping a cell a learner has known for six months back
 * to ten minutes is how a review queue fills up with things nobody has forgotten.
 *
 * <p>Build one through {@link #of(LadderInput)}, which checks it; the canonical constructor only
 * copies what it is given.
 *
 * @param key the ladder key, a slug
 * @param label the printed name
 * @param rungs the days to wait at each rung, strictly increasing
 * @param lapseDrop how many rungs a lapse costs
 * @param spread the fraction of the interval that may be spread either way, nought to one half
 */
public record Ladder(String key, String label, List<Integer> rungs, int lapseDrop, double spread) {

    private static final int DEFAULT_LAPSE_DROP = 2;
    private static final double DEFAULT_SPREAD = 0.1;
    private static final double MAX_SPREAD = 0.5;
    private static final int SHOWN_RUNGS = 4;

    /** How many values six base 36 digits can hold, which turns the start of a digest into a unit. */
    private static final double SIX_DIGIT_RANGE = 2_176_782_336.0;

    public static final Ladder STANDARD = of(LadderInput.builder("standard", 1, 3, 7, 16, 35, 75, 160, 340)
            .label("Standard")
            .build());

    public static final Ladder SLOW = of(LadderInput.builder("slow-start", 1, 2, 4, 8, 15, 30, 60, 120, 240)
            .label("Slow start")
            .lapseDrop(1)
            .spread(0.05)
            .build());

    public Ladder {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(label, "label");
        rungs = List.copyOf(rungs);
    }

    /** Checks a ladder as written and fills in what it left out, reporting every problem at once. */
    public static Ladder of(LadderInput input) {
        ProblemCollector problems = new ProblemCollector();
        String key = Identifiers.asSlug("ladder.key", input.key());
        List<Integer> rungs = input.rungs();

        if (rungs.isEmpty()) {
            problems.add("rungs", "must hold at least one rung");
        }
        for (int index = 0; index < rungs.size(); index += 1) {
            int rung = rungs.get(index);
            if (rung < 1) {
                problems.add("rungs." + index, "must be a whole number of days above zero: " + rung);
                continue;
            }
            if (index > 0 && rung <= rungs.get(index - 1)) {
                problems.add("rungs." + index, "must be longer than the rung before it: " + rung);
            }
        }

        int lapseDrop = input.lapseDrop().orElse(DEFAULT_LAPSE_DROP);
        if (lapseDrop < 1) {
            problems.add("lapseDrop", "must be a whole number above zero: " + lapseDrop);
        }

        double spread = input.spread().orElse(DEFAULT_SPREAD);
        if (!Double.isFinite(spread) || spread < 0 || spread > MAX_SPREAD) {
            problems.add("spread", "must be between zero and one half: " + Numbers.text(spread));
        }

        problems.throwIfAny("ladder " + key + " is not usable");
        return new Ladder(key, input.label().orElse(key), rungs, lapseDrop, spread);
    }

    public int rungCount() {
        return rungs.size();
    }

    public int topRung() {
        return rungs.size() - 1;
    }

    /** Holds a rung inside the ladder, so no arithmetic on a rung can step off either end. */
    public int clampRung(int rung) {
        return clamp(rung);
    }

    /**
     * Clamps in {@code long} arithmetic, so that climbing from the largest {@code int} or lapsing from
     * the smallest stays at the end it was heading for rather than wrapping round to the other one.
     */
    private int clamp(long rung) {
        return (int) Math.max(0, Math.min(topRung(), rung));
    }

    public int intervalAt(int rung) {
        return rungs.get(clampRung(rung));
    }

    /** The highest rung whose interval is no longer than the days given, nought below the first. */
    int rungHeldFor(int days) {
        int held = 0;
        for (int index = 0; index < rungs.size(); index += 1) {
            if (rungs.get(index) <= days) {
                held = index;
            }
        }
        return held;
    }

    public int climb(int rung) {
        return clamp(rung + 1L);
    }

    public int lapse(int rung) {
        return clamp((long) rung - lapseDrop);
    }

    public boolean isAtTop(int rung) {
        return clampRung(rung) == topRung();
    }

    /**
     * Spreads an interval deterministically from a seed so two cells that climbed together do not
     * come due together forever. The same seed always gives the same offset, which keeps a queue
     * reproducible for a report and for a test.
     */
    public int spreadInterval(int rung, String seed) {
        int base = intervalAt(rung);
        if (spread == 0 || base <= 1) {
            return base;
        }
        String hash = Identifiers.digest(key, Integer.toString(rung), seed);
        double unit = Long.parseLong(hash.substring(0, 6), 36) / SIX_DIGIT_RANGE;
        double swing = base * spread;
        long offset = Numbers.round((unit * 2 - 1) * swing);
        return (int) Math.max(1, base + offset);
    }

    /** The first rungs and the last one, as a course would print the ladder for a learner. */
    public String describe() {
        String shown = rungs.subList(0, Math.min(SHOWN_RUNGS, rungs.size())).stream()
                .map(String::valueOf)
                .collect(Collectors.joining(", "));
        String tail = rungs.size() > SHOWN_RUNGS ? ", up to " + rungs.get(rungs.size() - 1) : "";
        return label + " waits " + shown + tail + " days and drops " + lapseDrop + " rungs on a lapse";
    }
}
