package io.github.rkaushikdev.stemwright.domain.report;

import io.github.rkaushikdev.stemwright.domain.Grouping;
import io.github.rkaushikdev.stemwright.domain.lexicon.ClassRegistry;
import io.github.rkaushikdev.stemwright.domain.paradigm.ProfileBook;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * The editorial state of one language, read on a day.
 *
 * <p>Everything here is a question the drill layer answers silently. A class with no paradigm bound
 * produces a learner whose queue is empty; a profile whose first revision starts next Monday
 * produces the same empty queue for a different reason; a deck that selects nothing produces it for
 * a third. From the learner's side all three look identical, so this exists to tell an editor which
 * one they have before a class sits down to a blank screen.
 *
 * <p>It takes the day because every one of those answers depends on it, and it takes the
 * memberships already resolved rather than resolving them itself, so a caller cannot check one day
 * and report another.
 */
public final class Health {

    private static final String CLASSES = "classes";

    private Health() {}

    /**
     * Every check, worst first, and within a level in the order the areas were written: classes,
     * profiles, the lexicon, then the decks. Ordering by level rather than by area is deliberate: an
     * operator reading a long report reads the top of it, and what they need at the top is whatever
     * stops a class drilling today.
     */
    public static List<Finding> check(HealthInput input) {
        List<Finding> findings = new ArrayList<>();
        checkClasses(input, findings);
        findings.addAll(ProfileHealth.check(input));
        findings.addAll(LexiconHealth.check(input));
        findings.addAll(DeckHealth.check(input));
        findings.sort(Comparator.comparing(Finding::level));
        return List.copyOf(findings);
    }

    private static void checkClasses(HealthInput input, List<Finding> findings) {
        ProfileBook book = input.context().book();
        ClassRegistry classes = input.context().classes();

        for (String classKey : book.unboundClasses(classes)) {
            findings.add(new Finding(
                    FindingLevel.ERROR, CLASSES, classKey + " has no paradigm bound to it or to anything above it"));
        }
        for (String classKey : book.shadowedBindings(classes)) {
            findings.add(new Finding(
                    FindingLevel.WARNING,
                    CLASSES,
                    "the binding on " + classKey + " is shadowed and can never be reached"));
        }
    }

    /** How many findings there are of each level. */
    public static FindingCounts countLevels(List<Finding> findings) {
        Map<FindingLevel, Integer> counted = Grouping.countBy(findings, Finding::level);
        return new FindingCounts(
                counted.getOrDefault(FindingLevel.ERROR, 0),
                counted.getOrDefault(FindingLevel.WARNING, 0),
                counted.getOrDefault(FindingLevel.NOTE, 0));
    }

    /** True when nothing found would stop a class drilling today. */
    public static boolean isHealthy(List<Finding> findings) {
        return countLevels(findings).errors() == 0;
    }

    /**
     * The findings counted in one line, such as {@code latin has 0 errors, 1 warnings and 0 notes},
     * or {@code latin has nothing to report} when there are none.
     */
    public static String describe(List<Finding> findings, String language) {
        if (findings.isEmpty()) {
            return language + " has nothing to report";
        }
        FindingCounts counts = countLevels(findings);
        return language + " has " + counts.errors() + " errors, " + counts.warnings() + " warnings and "
                + counts.notes() + " notes";
    }
}
