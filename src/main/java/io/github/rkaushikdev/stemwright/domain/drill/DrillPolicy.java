package io.github.rkaushikdev.stemwright.domain.drill;

import io.github.rkaushikdev.stemwright.domain.Identifiers;
import io.github.rkaushikdev.stemwright.domain.errors.ProblemCollector;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Everything about how much drilling happens and what is allowed to appear beside what. Policies are
 * layered: the language has a default, a deck may override parts of it, and an assignment may
 * override parts again. Each layer is a {@link DrillPolicyPatch} rather than a replacement, so a deck
 * that only wants to change the new cell budget does not silently reset the leech threshold.
 *
 * <p>Build one through {@link #of(DrillPolicyPatch)} or {@link #applyPatch(DrillPolicyPatch)},
 * which check the result; the canonical constructor only copies what it is given.
 *
 * @param newPerDay cells never seen before that may be introduced in one day
 * @param reviewsPerDay cells due for review that may be asked in one day
 * @param perLexemeCap how many cells of one lexeme may appear in a single session
 * @param rowSpacing how many other questions must separate two cells of the same paradigm row
 * @param buryTwins whether answering a cell buries its syncretic twins for the rest of the day
 * @param leechThreshold lapses before a cell is suspended as a leech
 * @param ladderKey which interval ladder the strength model steps along
 * @param restDays weekdays with no drilling at all, as iso numbers where monday is one, sorted
 */
public record DrillPolicy(
        int newPerDay,
        int reviewsPerDay,
        int perLexemeCap,
        int rowSpacing,
        boolean buryTwins,
        int leechThreshold,
        String ladderKey,
        List<Integer> restDays) {

    public static final DrillPolicy DEFAULT = new DrillPolicy(10, 60, 3, 2, true, 6, "standard", List.of());

    private static final int MAX_NEW_PER_DAY = 500;
    private static final int MAX_REVIEWS_PER_DAY = 2000;
    private static final int MAX_PER_LEXEME_CAP = 200;
    private static final int MAX_ROW_SPACING = 50;
    private static final int MAX_LEECH_THRESHOLD = 100;
    private static final int DAYS_IN_WEEK = 7;

    public DrillPolicy {
        Objects.requireNonNull(ladderKey, "ladderKey");
        restDays = List.copyOf(restDays);
    }

    /** The default policy with a patch applied. */
    public static DrillPolicy of(DrillPolicyPatch patch) {
        return DEFAULT.applyPatch(patch);
    }

    /**
     * Applies a patch and validates the result rather than the patch. A partial override can only be
     * judged against what it is overriding: a {@code perLexemeCap} of nine is fine on its own and
     * wrong beside a {@code newPerDay} of four.
     *
     * @throws io.github.rkaushikdev.stemwright.domain.errors.ValidationException listing every count
     *     out of range, a leech threshold of nought among them, and every unusable rest day, or
     *     naming a ladder key that is not a slug
     */
    public DrillPolicy applyPatch(DrillPolicyPatch patch) {
        ProblemCollector problems = new ProblemCollector();
        DrillPolicy merged = new DrillPolicy(
                patch.newPerDay().orElse(newPerDay),
                patch.reviewsPerDay().orElse(reviewsPerDay),
                patch.perLexemeCap().orElse(perLexemeCap),
                patch.rowSpacing().orElse(rowSpacing),
                patch.buryTwins().orElse(buryTwins),
                patch.leechThreshold().orElse(leechThreshold),
                patch.ladderKey()
                        .map(key -> Identifiers.asSlug("ladderKey", key))
                        .orElse(ladderKey),
                patch.restDays().orElse(restDays).stream().distinct().sorted().toList());

        checkCount(problems, "newPerDay", merged.newPerDay, 0, MAX_NEW_PER_DAY);
        checkCount(problems, "reviewsPerDay", merged.reviewsPerDay, 0, MAX_REVIEWS_PER_DAY);
        checkCount(problems, "perLexemeCap", merged.perLexemeCap, 0, MAX_PER_LEXEME_CAP);
        checkCount(problems, "rowSpacing", merged.rowSpacing, 0, MAX_ROW_SPACING);
        // A threshold of nought would make a leech of every cell before it was ever answered.
        checkCount(problems, "leechThreshold", merged.leechThreshold, 1, MAX_LEECH_THRESHOLD);

        for (int day : merged.restDays) {
            if (day < 1 || day > DAYS_IN_WEEK) {
                problems.add("restDays", "is not an iso weekday: " + day);
            }
        }
        if (merged.restDays.size() == DAYS_IN_WEEK) {
            problems.add("restDays", "would rest every day of the week");
        }
        if (merged.newPerDay == 0 && merged.reviewsPerDay == 0) {
            problems.add("newPerDay", "is zero and so is reviewsPerDay, which asks for no drilling at all");
        }

        problems.throwIfAny("drill policy is not usable");
        return merged;
    }

    private static void checkCount(ProblemCollector problems, String path, int value, int min, int max) {
        if (value < min) {
            problems.add(path, min == 0 ? "must not be negative: " + value : "must be at least " + min + ": " + value);
            return;
        }
        if (value > max) {
            problems.add(path, "must be at most " + max + ": " + value);
        }
    }

    /** Whether drilling happens on the given iso weekday, monday being one. */
    public boolean allowsDay(int isoWeekday) {
        return !restDays.contains(isoWeekday);
    }

    /** The most cells one day can ask, new and review together. */
    public int dailyCeiling() {
        return newPerDay + reviewsPerDay;
    }

    public String describe() {
        List<String> parts = new ArrayList<>();
        parts.add("up to " + newPerDay + " new and " + reviewsPerDay + " review cells a day");
        parts.add("at most " + perLexemeCap + " cells of one word in a session");
        if (rowSpacing > 0) {
            parts.add(rowSpacing + " questions between cells of the same row");
        }
        if (buryTwins) {
            parts.add("twins buried after an answer");
        }
        if (!restDays.isEmpty()) {
            String days = restDays.stream().map(String::valueOf).collect(Collectors.joining(" and "));
            parts.add("resting on weekdays " + days);
        }
        return String.join(", ", parts);
    }

    /**
     * True when this policy asks for at least as much work as {@code base}: no smaller budget for new
     * cells or for reviews, and no more rest days.
     */
    public boolean isAtLeastAsDemandingAs(DrillPolicy base) {
        return newPerDay >= base.newPerDay
                && reviewsPerDay >= base.reviewsPerDay
                && restDays.size() <= base.restDays.size();
    }
}
