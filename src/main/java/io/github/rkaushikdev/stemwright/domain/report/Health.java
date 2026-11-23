package io.github.rkaushikdev.stemwright.domain.report;

import io.github.rkaushikdev.stemwright.domain.Grouping;
import io.github.rkaushikdev.stemwright.domain.lexicon.ClassRegistry;
import io.github.rkaushikdev.stemwright.domain.lexicon.Lexeme;
import io.github.rkaushikdev.stemwright.domain.paradigm.ProfileBook;
import io.github.rkaushikdev.stemwright.domain.paradigm.ProfileRevision;
import io.github.rkaushikdev.stemwright.domain.paradigm.RevisionHistory;
import io.github.rkaushikdev.stemwright.domain.text.Numbers;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

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
 * and report another. A deck at a tolerance level the language never declared fails the same way
 * one answer later, so it is reported with the rest.
 */
public final class Health {

    private static final String CLASSES = "classes";
    private static final String PROFILES = "profiles";
    private static final String LEXICON = "lexicon";
    private static final String DECKS = "decks";

    /** A deck whose busiest bundle holds more than this multiple of the mean is uneven. */
    private static final double UNEVEN = 2;

    /** A deck of fewer lexemes than this is worth a note. */
    private static final int FEW_LEXEMES = 5;

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
        checkProfiles(input, findings);
        checkLexemes(input, findings);
        checkDecks(input, findings);
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

    private static void checkProfiles(HealthInput input, List<Finding> findings) {
        for (Map.Entry<String, RevisionHistory> entry :
                input.context().book().histories().entrySet()) {
            String profileKey = entry.getKey();
            List<ProfileRevision> revisions = entry.getValue().revisions();
            if (revisions.isEmpty()) {
                findings.add(new Finding(FindingLevel.ERROR, PROFILES, profileKey + " holds no revisions at all"));
                continue;
            }
            Optional<ProfileRevision> inForce = entry.getValue().revisionInForce(input.on());
            if (inForce.isEmpty()) {
                findings.add(new Finding(
                        FindingLevel.WARNING,
                        PROFILES,
                        profileKey + " has nothing in force on " + input.on() + ", the first starts "
                                + revisions.getFirst().effectiveFrom()));
                continue;
            }
            checkSyncretism(profileKey, inForce.get(), findings);
        }
    }

    /**
     * A pair the revision declares one form and its endings build differently is a warning, because
     * burial hides the second cell once the first is answered and the learner never practises an
     * ending that is not in fact the same. A pair the endings happen to build alike without being
     * declared is left alone: asking it twice costs a question and hides nothing.
     */
    private static void checkSyncretism(String profileKey, ProfileRevision revision, List<Finding> findings) {
        List<String> unbuilt = revision.syncretism().reconcileWith(revision.profile()).stream()
                .filter(pair -> pair.declared() && !pair.produced())
                .map(pair -> pair.first() + " and " + pair.second())
                .toList();
        if (!unbuilt.isEmpty()) {
            findings.add(new Finding(
                    FindingLevel.WARNING,
                    PROFILES,
                    profileKey + " revision " + revision.ordinal() + " declares " + String.join(", ", unbuilt)
                            + " one form, but its endings build them differently"));
        }
    }

    /**
     * A lexeme whose class is not in the registry is a stronger failure than one whose class is
     * simply unbound: the first cannot be graded under any profile, and the second is waiting on an
     * editorial decision that has a place to go.
     */
    private static void checkLexemes(HealthInput input, List<Finding> findings) {
        ProfileBook book = input.context().book();
        ClassRegistry classes = input.context().classes();
        List<Lexeme> entries = input.context().lexicon().allLexemes();

        List<Lexeme> missing = entries.stream()
                .filter(entry -> classes.classOf(entry.classKey()).isEmpty())
                .toList();
        Grouping.countBy(missing, Lexeme::classKey)
                .forEach((classKey, count) -> findings.add(new Finding(
                        FindingLevel.ERROR,
                        LEXICON,
                        count + " lexemes name the class " + classKey + ", which the registry does not hold")));

        long unbound = entries.stream()
                .filter(entry -> classes.classOf(entry.classKey()).isPresent()
                        && book.bindingFor(classes, entry.classKey()).isEmpty())
                .count();
        if (unbound > 0) {
            findings.add(new Finding(
                    FindingLevel.WARNING,
                    LEXICON,
                    unbound + " lexemes cannot be drilled because their class has no paradigm"));
        }

        long orphaned = entries.stream()
                .filter(entry -> book.bindingFor(classes, entry.classKey())
                        .filter(binding -> book.historyFor(binding.profileKey()).isEmpty())
                        .isPresent())
                .count();
        if (orphaned > 0) {
            findings.add(new Finding(
                    FindingLevel.ERROR, LEXICON, orphaned + " lexemes are bound to a profile the book has lost"));
        }

        if (entries.isEmpty()) {
            findings.add(new Finding(FindingLevel.NOTE, LEXICON, "the lexicon is empty"));
        }
    }

    private static void checkDecks(HealthInput input, List<Finding> findings) {
        LocalDate on = input.on();
        for (HealthInput.ResolvedDeck resolved : input.decks()) {
            String deckKey = resolved.deck().key();
            resolved.deck()
                    .toleranceLevel()
                    .filter(level -> input.ortho().levelOf(level).isEmpty())
                    .ifPresent(level -> findings.add(new Finding(
                            FindingLevel.ERROR,
                            DECKS,
                            deckKey + " runs at the level " + level + ", which "
                                    + input.ortho().language()
                                    + " does not declare, so no answer to it can be marked")));
            DeckStats stats = DeckStats.of(resolved.deck(), resolved.membership(), List.of());
            if (stats.cells() == 0) {
                findings.add(new Finding(FindingLevel.ERROR, DECKS, deckKey + " selects nothing on " + on));
                continue;
            }
            double imbalance = stats.bundleImbalance();
            if (imbalance > UNEVEN) {
                findings.add(new Finding(
                        FindingLevel.WARNING,
                        DECKS,
                        deckKey + " is uneven, its busiest bundle holds " + Numbers.toFixed(imbalance, 1)
                                + " times the average"));
            }
            if (stats.lexemes() < FEW_LEXEMES) {
                findings.add(
                        new Finding(FindingLevel.NOTE, DECKS, deckKey + " holds only " + stats.lexemes() + " lexemes"));
            }
        }

        if (input.decks().isEmpty()) {
            findings.add(new Finding(FindingLevel.NOTE, DECKS, "no decks are defined"));
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
