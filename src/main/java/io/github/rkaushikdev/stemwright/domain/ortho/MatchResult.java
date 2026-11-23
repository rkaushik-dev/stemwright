package io.github.rkaushikdev.stemwright.domain.ortho;

import io.github.rkaushikdev.stemwright.domain.text.Normalise;
import java.util.List;
import java.util.Optional;

/**
 * The mark one typed answer gets against one expected form.
 *
 * <p>Keeping {@link MatchVerdict#TOLERATED} distinct from {@link MatchVerdict#EXACT} is the point of
 * the whole orthography layer. A deck that folds accents still wants to be able to say "that was
 * right, and the accent goes on the second syllable", and a mark that threw the information away
 * could not.
 *
 * @param verdict the outcome
 * @param expected the expected form, normalised
 * @param given the typed answer, normalised
 * @param foldsUsed the steps the answer needed in order to pass, empty unless it was tolerated
 * @param distance the cluster distance between the two once folded, zero for a right answer
 * @param miss what the learner most likely got wrong, empty for an exact answer
 * @param similarity how close the two are once folded, from nought to one, one for a right answer
 */
public record MatchResult(
        MatchVerdict verdict,
        String expected,
        String given,
        List<FoldStep> foldsUsed,
        int distance,
        Optional<MissKind> miss,
        double similarity) {

    /** How close a wrong answer has to be before it counts as a near miss. */
    public static final int NEAR_MISS_DISTANCE = 1;

    public MatchResult {
        foldsUsed = List.copyOf(foldsUsed);
    }

    /**
     * Marks one answer at one level. Both sides are normalised first, and the distance is measured
     * after folding, so a difference the level forgives costs nothing. A wrong answer within
     * {@link #NEAR_MISS_DISTANCE}, or one adjacent swap away, is a near miss however long the word.
     */
    public static MatchResult compareForm(String expected, String given, ToleranceLevel level) {
        String cleanExpected = Normalise.normaliseInput(expected);
        String cleanGiven = Normalise.normaliseInput(given);
        String foldedExpected = Folding.applyFolds(cleanExpected, level.folds());
        String foldedGiven = Folding.applyFolds(cleanGiven, level.folds());
        int distance = Distance.clusterDistance(foldedExpected, foldedGiven);
        double closeness = Distance.similarity(foldedExpected, foldedGiven);

        if (cleanExpected.equals(cleanGiven)) {
            return new MatchResult(MatchVerdict.EXACT, cleanExpected, cleanGiven, List.of(), 0, Optional.empty(), 1);
        }

        if (foldedExpected.equals(foldedGiven)) {
            return new MatchResult(
                    MatchVerdict.TOLERATED,
                    cleanExpected,
                    cleanGiven,
                    Folding.foldsThatMattered(cleanExpected, cleanGiven, level.folds()),
                    0,
                    Optional.of(MissKind.classify(cleanExpected, cleanGiven)),
                    1);
        }

        boolean near = distance <= NEAR_MISS_DISTANCE || Distance.isTransposition(foldedExpected, foldedGiven);
        return new MatchResult(
                near ? MatchVerdict.NEAR : MatchVerdict.WRONG,
                cleanExpected,
                cleanGiven,
                List.of(),
                distance,
                Optional.of(MissKind.classify(cleanExpected, cleanGiven)),
                closeness);
    }

    /**
     * Marks against several acceptable forms and keeps the best outcome. A cell with a variant
     * spelling has more than one right answer, and the learner should get the better of the two
     * rather than whichever was listed first. Between two equal verdicts the smaller distance wins,
     * and the earlier form wins a tie. With no forms at all, the answer is marked against the empty
     * form.
     */
    public static MatchResult compareAgainstAny(List<String> expectedForms, String given, ToleranceLevel level) {
        MatchResult best = null;
        for (String expected : expectedForms) {
            MatchResult result = compareForm(expected, given, level);
            if (best == null || rank(result.verdict) > rank(best.verdict)) {
                best = result;
                continue;
            }
            if (rank(result.verdict) == rank(best.verdict) && result.distance < best.distance) {
                best = result;
            }
        }
        return best != null ? best : compareForm("", given, level);
    }

    private static int rank(MatchVerdict verdict) {
        return switch (verdict) {
            case EXACT -> 3;
            case TOLERATED -> 2;
            case NEAR -> 1;
            case WRONG -> 0;
        };
    }

    /** True for the two verdicts that count as right, exact and tolerated. */
    public boolean isCorrect() {
        return verdict == MatchVerdict.EXACT || verdict == MatchVerdict.TOLERATED;
    }

    /** What the learner is told about the mark, such as {@code not quite, the ending is off by 1}. */
    public String describe() {
        return switch (verdict) {
            case EXACT -> "right";
            case TOLERATED ->
                miss.map(kind -> "right, and the " + kind.phrase() + " is not marked at this tolerance")
                        .orElse("right, with a difference the deck ignores");
            case NEAR -> "not quite, the " + miss.map(MissKind::phrase).orElse("form") + " is off by " + distance;
            case WRONG -> "wrong, the expected form was " + expected;
        };
    }
}
