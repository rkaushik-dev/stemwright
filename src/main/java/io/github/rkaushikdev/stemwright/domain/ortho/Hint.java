package io.github.rkaushikdev.stemwright.domain.ortho;

import io.github.rkaushikdev.stemwright.domain.errors.ValidationException;
import io.github.rkaushikdev.stemwright.domain.text.Normalise;
import java.util.Arrays;
import java.util.List;

/**
 * What to show a learner who got it wrong.
 *
 * <p>Hints are graded rather than all or nothing, because the useful hint is almost never the
 * answer. A learner who typed the right stem and the wrong ending needs to be told it is the ending;
 * one who typed a different word needs to be told the length; and only somebody who has asked three
 * times needs the form itself.
 *
 * <p>Nothing here decides when to show a hint. That is a teaching decision and it belongs to the
 * deck.
 *
 * @param level the rung of the ladder the hint is from
 * @param text what the learner is shown
 * @param revealed how many clusters of the answer the text gives away
 */
public record Hint(HintLevel level, String text, int revealed) {

    private static final String MARKER = ".";

    /** {@link #shapeOf(String, String)} with a dot for each cluster. */
    public static String shapeOf(String expected) {
        return shapeOf(expected, MARKER);
    }

    /** A skeleton of the form: one marker per cluster, with nothing given away. */
    public static String shapeOf(String expected, String marker) {
        return marker.repeat(Normalise.markClusters(expected).size());
    }

    /** {@link #openingOf(String, String, String)} with a dot for each hidden cluster. */
    public static String openingOf(String expected, String given) {
        return openingOf(expected, given, MARKER);
    }

    /**
     * Reveals the shared opening and hides the rest, though never the last cluster. A learner who
     * typed nothing gets the first cluster, because a skeleton with no letters at all is a hint only
     * in the sense that it fills a box on a screen.
     *
     * @throws IllegalArgumentException for an empty expected form, which has no opening to give
     */
    public static String openingOf(String expected, String given, String marker) {
        List<String> clusters = Normalise.markClusters(expected);
        if (clusters.isEmpty()) {
            throw new IllegalArgumentException("an empty form has no opening to give");
        }
        int shared = Math.max(1, Math.min(Distance.sharedPrefixLength(expected, given), clusters.size() - 1));
        return String.join("", clusters.subList(0, shared)) + marker.repeat(clusters.size() - shared);
    }

    private static int revealedIn(String text) {
        return (int) Normalise.markClusters(text).stream()
                .filter(cluster -> !cluster.equals(MARKER))
                .count();
    }

    /**
     * The hint at one rung of the ladder for a wrong answer.
     *
     * @throws ValidationException on the {@code hint} field when the answer was right
     */
    public static Hint of(MatchResult result, HintLevel level) {
        if (result.isCorrect()) {
            throw ValidationException.field("hint", "was asked for on an answer that was already right");
        }

        List<String> clusters = Normalise.markClusters(result.expected());

        return switch (level) {
            case SHAPE -> new Hint(level, clusters.size() + " letters: " + shapeOf(result.expected()), 0);
            case OPENING -> {
                String text = openingOf(result.expected(), result.given());
                yield new Hint(level, text, revealedIn(text));
            }
            case KIND -> {
                int at = Distance.firstDivergence(result.expected(), result.given())
                        .map(divergence -> divergence.index() + 1)
                        .orElse(clusters.size());
                String miss = result.miss().map(MissKind::phrase).orElse("form");
                yield new Hint(level, "the " + miss + " is wrong, from letter " + at, 0);
            }
            case REVEAL -> new Hint(level, result.expected(), clusters.size());
        };
    }

    /** The whole ladder of hints for one wrong answer, in order. */
    public static List<Hint> ladder(MatchResult result) {
        return Arrays.stream(HintLevel.values()).map(level -> of(result, level)).toList();
    }

    /** True when the hint gives away every cluster of the expected form. */
    public boolean revealsAnswer(String expected) {
        return revealed >= Normalise.markClusters(expected).size();
    }

    /** The hint as one line, such as {@code shape: 9 letters: .........}. */
    public String describe() {
        return level.key() + ": " + text;
    }
}
