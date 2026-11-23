package io.github.rkaushikdev.stemwright.domain.ortho;

import io.github.rkaushikdev.stemwright.domain.text.Normalise;
import io.github.rkaushikdev.stemwright.domain.text.Text;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.function.BiPredicate;

/** What the learner most likely got wrong, for the hint rather than the mark. */
public enum MissKind {
    ACCENT("accent", "accent"),
    CAPITAL("capital", "capital"),
    ENDING("ending", "ending"),
    STEM("stem", "stem"),
    ORDER("order", "order"),
    SPACING("spacing", "spacing"),
    LENGTH("length", "vowel length"),
    OTHER("other", "other");

    /**
     * The differences that have a single cause, asked in this order. A missing macron is asked about
     * before the accents, because stripping every mark would also explain it and a Latin learner
     * who left out a long mark has not got an accent wrong: Latin has no written accent to get.
     */
    private static final List<Cause> SINGLE_CAUSES = List.of(
            new Cause(SPACING, (left, right) -> withoutSpaces(left).equals(withoutSpaces(right))),
            new Cause(CAPITAL, (left, right) -> Text.lower(left).equals(Text.lower(right))),
            new Cause(
                    LENGTH,
                    (left, right) -> FoldStep.LENGTH_MARKS.apply(left).equals(FoldStep.LENGTH_MARKS.apply(right))),
            new Cause(ACCENT, (left, right) -> Normalise.stripMarks(left).equals(Normalise.stripMarks(right))),
            new Cause(ORDER, Distance::isTransposition));

    private final String key;
    private final String phrase;

    MissKind(String key, String phrase) {
        this.key = key;
        this.phrase = phrase;
    }

    /** The word a hint and the http api carry, such as {@code ending}. */
    public String key() {
        return key;
    }

    /** What a sentence calls the miss, such as {@code vowel length}. */
    String phrase() {
        return phrase;
    }

    public static Optional<MissKind> fromKey(String key) {
        return Arrays.stream(values()).filter(kind -> kind.key.equals(key)).findFirst();
    }

    /**
     * Names the likeliest miss, asking in turn whether the two forms differ only in spacing, only in
     * capitals, only in vowel length marks, only in accents, or only by one adjacent swap. Failing
     * all of those, a miss that keeps the first half of the shorter form is the ending and any other
     * is the stem. Two forms that are the same once normalised are {@link #OTHER}.
     */
    public static MissKind classify(String expected, String given) {
        String left = Normalise.normaliseInput(expected);
        String right = Normalise.normaliseInput(given);
        if (left.equals(right)) {
            return OTHER;
        }
        for (Cause cause : SINGLE_CAUSES) {
            if (cause.explains().test(left, right)) {
                return cause.kind();
            }
        }

        int shared = Distance.sharedPrefixLength(left, right);
        int stemLength = Math.ceilDiv(Math.min(left.length(), right.length()), 2);
        return shared >= stemLength ? ENDING : STEM;
    }

    private static String withoutSpaces(String text) {
        return Text.SPACE_RUN.matcher(text).replaceAll("");
    }

    /**
     * One difference with a single cause.
     *
     * @param kind what the miss is called when the test holds
     * @param explains whether the two normalised forms differ by that cause alone
     */
    private record Cause(MissKind kind, BiPredicate<String, String> explains) {}
}
