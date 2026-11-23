package io.github.rkaushikdev.stemwright.domain.report;

import io.github.rkaushikdev.stemwright.domain.Grouping;
import io.github.rkaushikdev.stemwright.domain.lexicon.ClassRegistry;
import io.github.rkaushikdev.stemwright.domain.lexicon.Lexeme;
import io.github.rkaushikdev.stemwright.domain.lexicon.Stem;
import io.github.rkaushikdev.stemwright.domain.paradigm.ProfileBook;
import io.github.rkaushikdev.stemwright.domain.paradigm.ResolvedProfile;
import io.github.rkaushikdev.stemwright.domain.text.Collation;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * The health checks over the lexicon, which {@link Health#check} runs after the classes and the
 * profiles. Every finding is in the {@code lexicon} area.
 *
 * <p>A lexeme whose class is not in the registry is a stronger failure than one whose class is
 * simply unbound: the first cannot be graded under any profile, and the second is waiting on an
 * editorial decision that has a place to go.
 *
 * <p>A lexeme that lacks a stem its profile builds on is the quietest failure of all. Realisation
 * falls back to the base stem rather than failing, so a verb with no perfect stem is marked against
 * a perfect built on the present, and the learner who types the right form is told it is wrong.
 */
final class LexiconHealth {

    private static final String LEXICON = "lexicon";

    /** How many lexemes a finding names before it says how many more there are. */
    private static final int NAMED = 3;

    private LexiconHealth() {}

    static List<Finding> check(HealthInput input) {
        List<Finding> findings = new ArrayList<>();
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

        checkStemRoles(input, entries, findings);

        if (entries.isEmpty()) {
            findings.add(new Finding(FindingLevel.NOTE, LEXICON, "the lexicon is empty"));
        }
        return List.copyOf(findings);
    }

    /**
     * Each lexeme against the profile in force for its class on the day, naming the stem roles the
     * profile builds on that the lexeme does not have. Only the first few lexemes are named, by
     * citation form as a reader sorts them, and the rest are counted.
     */
    private static void checkStemRoles(HealthInput input, List<Lexeme> entries, List<Finding> findings) {
        List<String> lacking = new ArrayList<>();
        for (Lexeme entry : entries.stream()
                .sorted((a, b) -> Collation.compare(a.citationForm(), b.citationForm()))
                .toList()) {
            Optional<ResolvedProfile> resolved =
                    input.context().book().profileFor(input.context().classes(), entry.classKey(), input.on());
            if (resolved.isEmpty()) {
                continue;
            }
            List<String> absent = resolved.get().revision().profile().stemRolesUsed().stream()
                    .filter(role -> !role.equals(Stem.DEFAULT_ROLE))
                    .filter(role -> entry.stems().find(role).isEmpty())
                    .toList();
            if (!absent.isEmpty()) {
                lacking.add(entry.citationForm() + " (" + String.join(", ", absent) + ")");
            }
        }
        if (lacking.isEmpty()) {
            return;
        }
        String named = lacking.stream().limit(NAMED).collect(Collectors.joining(", "));
        String more = lacking.size() > NAMED ? " and " + (lacking.size() - NAMED) + " more" : "";
        findings.add(new Finding(
                FindingLevel.WARNING,
                LEXICON,
                lacking.size() + " lexemes lack a stem their profile builds on and are marked against forms"
                        + " built on the base stem: " + named + more));
    }
}
