package io.github.rkaushikdev.stemwright.app.services;

import io.github.rkaushikdev.stemwright.domain.drill.Session;
import io.github.rkaushikdev.stemwright.domain.ortho.Hint;
import io.github.rkaushikdev.stemwright.domain.ortho.HintLevel;
import io.github.rkaushikdev.stemwright.domain.ortho.MatchResult;
import io.github.rkaushikdev.stemwright.domain.ortho.MatchVerdict;
import java.util.Optional;

/**
 * Which rung of the hint ladder a wrong answer earns. {@link Hint} says what each rung shows and
 * leaves when to show it to the desk, and this is the desk's answer.
 *
 * <p>The ladder is climbed by missing the same cell again in the same sitting: the first miss is
 * shown the shape, the second the kind of miss, the third the opening and the fourth the form. A
 * near miss starts one rung up, because a learner that close already knows the shape of the word.
 * Misses on another day do not count, since the sitting is where the form is being worked out.
 */
final class HintRung {

    private static final HintLevel[] LADDER = HintLevel.values();

    private HintRung() {}

    /**
     * The hint for an answer to the cell, read against the session as it stood before the answer
     * was recorded, and nothing for a right answer.
     */
    static Optional<Hint> forAnswer(Session before, String cellId, MatchResult match) {
        if (match.isCorrect()) {
            return Optional.empty();
        }
        long earlierMisses = before.attemptsForCell(cellId).stream()
                .filter(attempt -> !attempt.wasCorrect())
                .count();
        long rung = earlierMisses + (match.verdict() == MatchVerdict.NEAR ? 1 : 0);
        return Optional.of(Hint.of(match, LADDER[(int) Math.min(rung, LADDER.length - 1)]));
    }
}
