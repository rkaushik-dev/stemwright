package io.github.rkaushikdev.stemwright.domain.ortho;

import io.github.rkaushikdev.stemwright.domain.errors.ValidationException;
import io.github.rkaushikdev.stemwright.domain.text.Normalise;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * Folds whole written forms by a set of {@link FoldStep}s.
 *
 * <p>Steps always run in {@link #ORDER}, whatever order the caller listed them in. Two decks asking
 * for the same set therefore fold identically, which they would not if the list order were honoured.
 * A step listed twice runs once.
 */
public final class Folding {

    /**
     * The order steps run in: punctuation, capitals, final letters, ligatures, consonantal letters,
     * length marks, accents, spacing.
     */
    public static final List<FoldStep> ORDER = List.of(FoldStep.values());

    private Folding() {}

    /** Normalises the text, then applies each step asked for, in {@link #ORDER}. */
    public static String applyFolds(String text, Collection<FoldStep> steps) {
        String out = Normalise.normaliseInput(text);
        for (FoldStep step : inOrder(steps)) {
            out = step.apply(out);
        }
        return out;
    }

    /** The labels of the steps asked for, in {@link #ORDER}. */
    public static List<String> describeFolds(Collection<FoldStep> steps) {
        return inOrder(steps).stream().map(FoldStep::label).toList();
    }

    /**
     * Reads a stored fold list: every key must name a step, and the result is in {@link #ORDER}
     * without duplicates.
     *
     * @throws ValidationException on the {@code folds} field at the first key that names no step
     */
    public static List<FoldStep> normaliseFoldList(List<String> keys) {
        List<FoldStep> steps = new ArrayList<>(keys.size());
        for (String key : keys) {
            steps.add(FoldStep.fromKey(key)
                    .orElseThrow(() -> ValidationException.field("folds", "is not a fold step: " + key)));
        }
        return inOrder(steps);
    }

    /** True when the two forms are the same once folded. */
    public static boolean foldedEqual(String a, String b, Collection<FoldStep> steps) {
        return applyFolds(a, steps).equals(applyFolds(b, steps));
    }

    /**
     * The subset of the given steps that actually changes this pair: each step without which the
     * answer would no longer match. Used to tell a learner what they got away with rather than only
     * that they were right. Empty when the answer does not match even with every step.
     */
    public static List<FoldStep> foldsThatMattered(String expected, String answer, Collection<FoldStep> steps) {
        if (!foldedEqual(expected, answer, steps)) {
            return List.of();
        }
        List<FoldStep> listed = inOrder(steps);
        List<FoldStep> mattered = new ArrayList<>();
        for (FoldStep step : listed) {
            List<FoldStep> without =
                    listed.stream().filter(candidate -> candidate != step).toList();
            if (!foldedEqual(expected, answer, without)) {
                mattered.add(step);
            }
        }
        return List.copyOf(mattered);
    }

    private static List<FoldStep> inOrder(Collection<FoldStep> steps) {
        Set<FoldStep> wanted = Set.copyOf(steps);
        return ORDER.stream().filter(wanted::contains).toList();
    }
}
