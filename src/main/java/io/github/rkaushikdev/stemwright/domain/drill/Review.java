package io.github.rkaushikdev.stemwright.domain.drill;

import io.github.rkaushikdev.stemwright.domain.errors.NotFoundException;
import io.github.rkaushikdev.stemwright.domain.features.FeatureBundle;
import io.github.rkaushikdev.stemwright.domain.lexicon.Lexeme;
import io.github.rkaushikdev.stemwright.domain.mastery.Attempt;
import io.github.rkaushikdev.stemwright.domain.mastery.AttemptInput;
import io.github.rkaushikdev.stemwright.domain.mastery.CellStrength;
import io.github.rkaushikdev.stemwright.domain.mastery.CreditReport;
import io.github.rkaushikdev.stemwright.domain.mastery.LeechPolicy;
import io.github.rkaushikdev.stemwright.domain.mastery.MasteryLedger;
import io.github.rkaushikdev.stemwright.domain.ortho.HintLevel;
import io.github.rkaushikdev.stemwright.domain.ortho.MatchResult;
import io.github.rkaushikdev.stemwright.domain.ortho.MatchVerdict;
import io.github.rkaushikdev.stemwright.domain.ortho.ResolvedTolerance;
import io.github.rkaushikdev.stemwright.domain.paradigm.Cell;
import io.github.rkaushikdev.stemwright.domain.paradigm.RealisedCell;
import io.github.rkaushikdev.stemwright.domain.paradigm.ResolvedProfile;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * One answer, all the way through. This is where the layers meet, and the order they meet in is the
 * whole behaviour of the desk:
 *
 * <p>the revision in force decides the expected form and the twin set, the deck and the cell decide
 * the tolerance, the tolerance decides the verdict, the verdict decides the schedule, the schedule
 * and the twin set decide tomorrow's queue, and the lapse count decides whether the cell survives at
 * all.
 *
 * <p>Nothing here reaches for the newest revision. Everything resolves on the day of the answer.
 */
public final class Review {

    private Review() {}

    /**
     * The form the cell takes under the profile revision in force on the day, the variant forms its
     * slot also accepts there, and that revision's id.
     *
     * @throws NotFoundException when the lexicon does not hold the cell's lexeme, or naming the
     *     lexeme's class and the day when no revision of its profile is in force then
     */
    public static ExpectedForm expectedFormFor(DrillContext context, ReviewSettings settings, Cell cell, LocalDate on) {
        Lexeme entry = context.lexicon().requireLexeme(cell.lexemeId());
        ResolvedProfile resolved = context.book().requireProfileFor(context.classes(), entry.classKey(), on);
        FeatureBundle bundle = FeatureBundle.parseKey(context.schema(), cell.bundle());
        RealisedCell realised =
                settings.rules().realise(entry, resolved.revision().profile(), bundle);
        return new ExpectedForm(realised.form(), resolved.revision().id(), realised.variants());
    }

    /**
     * Marks one answer against the main form and every variant the slot accepts, as typed or as read
     * through the language's transliteration table, and works out everything it changes. The attempt
     * records the main form as the one expected, and the verdict as marked even when a hint the
     * learner saw keeps a right answer from climbing. A right answer climbs the cell and
     * passes credit to its twins, a near miss holds it at its rung, and a wrong one lapses it; the
     * leech policy then asks whether the answer carried the cell's lapse count onto one of its
     * firing points. Unless the settings say otherwise, the cell and its twins are buried for the
     * rest of the sitting, whatever the verdict.
     * The ledger and burial set handed in are left as they were.
     */
    public static ReviewResult answer(
            DrillContext context,
            ReviewSettings settings,
            MasteryLedger ledger,
            BurialSet buried,
            ReviewRequest request) {
        Cell cell = request.cell();
        LocalDate on = request.on();
        ExpectedForm expected = expectedFormFor(context, settings, cell, on);

        ResolvedTolerance tolerance = settings.ortho().resolveTolerance(request.tolerance());
        MatchResult match = MatchResult.compareAgainstAny(
                acceptedForms(expected), request.given(), tolerance.level(), settings.transliteration());

        CellStrength before = ledger.strengthFor(cell.id());
        boolean earned = match.isCorrect()
                && request.hinted().filter(Review::givesLettersAway).isEmpty();
        CellStrength after;
        if (earned) {
            after = before.recordCorrect(settings.ladder(), on);
        } else if (match.isCorrect() || match.verdict() == MatchVerdict.NEAR) {
            after = before.recordNearMiss(settings.ladder(), on);
        } else {
            after = before.recordLapse(settings.ladder(), on);
        }

        MasteryLedger next = ledger.withStrength(after);

        LeechPolicy.Applied leech = settings.leech().applyTo(before, after, settings.ladder(), on);
        if (leech.outcome().fired() && leech.strength() != after) {
            next = next.withStrength(leech.strength());
        }

        List<String> twins = BurialSet.twinCellsOf(context, cell, on);
        int twinsCredited = 0;
        if (earned && !twins.isEmpty()) {
            CreditReport credited = settings.credit().propagate(next, settings.ladder(), twins, on);
            next = credited.ledger();
            twinsCredited = credited.movedCount();
        }

        BurialSet nextBuried = settings.buryTwins() ? buried.bury(context, cell, on) : buried;

        AttemptInput.Builder input = AttemptInput.builder()
                .learnerId(request.learnerId())
                .sessionId(request.sessionId())
                .cellId(cell.id())
                .deckKey(request.deckKey())
                .on(on)
                .sequence(request.sequence())
                .given(request.given())
                .expected(expected.form())
                .verdict(match.verdict())
                .toleranceLevel(tolerance.level().key())
                .revisionId(expected.revisionId());
        request.elapsedMs().ifPresent(input::elapsedMs);

        return new ReviewResult(
                Attempt.of(input.build()),
                match,
                next,
                nextBuried,
                expected.form(),
                expected.revisionId(),
                twinsCredited,
                leech.outcome().fired(),
                expected.variants());
    }

    /**
     * True for a rung that shows some of the answer's letters. A right answer typed after one of
     * those is the learner copying rather than recalling, so it is scheduled as a near miss: the
     * cell holds its rung, comes back soon, and passes nothing to its twins. A shape or the kind of
     * miss gives no letters away, and an answer after either is recalled all the same.
     */
    private static boolean givesLettersAway(HintLevel level) {
        return level.compareTo(HintLevel.OPENING) >= 0;
    }

    /** The main form first, so an answer that matches it and a variant equally is marked on it. */
    private static List<String> acceptedForms(ExpectedForm expected) {
        List<String> forms = new ArrayList<>(List.of(expected.form()));
        forms.addAll(expected.variants());
        return forms;
    }

    /**
     * The form an answer to a cell is marked against, and where it came from.
     *
     * @param form the realised form
     * @param revisionId the profile revision it was realised from
     * @param variants the other forms the slot accepts, in the order its endings were written
     */
    public record ExpectedForm(String form, String revisionId, List<String> variants) {

        public ExpectedForm {
            Objects.requireNonNull(form, "form");
            Objects.requireNonNull(revisionId, "revisionId");
            variants = List.copyOf(variants);
        }
    }
}
