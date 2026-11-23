package io.github.rkaushikdev.stemwright.domain.drill;

import io.github.rkaushikdev.stemwright.domain.mastery.AttemptTally;
import io.github.rkaushikdev.stemwright.domain.text.Numbers;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * One sitting counted up, as {@link Session#summarise()} gives it.
 *
 * @param sessionId the id of the session
 * @param learnerId the learner who sat it
 * @param deckKey the deck that was drilled
 * @param on the study day of the sitting
 * @param tally how the answers were marked
 * @param accuracy the share of answers that were right, nought for a sitting with none
 * @param distinctCells how many different cells were answered
 * @param medianElapsedMs the median time an answer took, absent when none was timed
 * @param closed whether the sitting is over
 * @param firstTryAccuracy the share of the cells answered whose first answer of the sitting was
 *     right, nought for a sitting with none; answers given after relearning a cell do not lift it
 * @param unresolved the cells whose last answer of the sitting was wrong or near, in natural string
 *     order, which is what the learner should look at again before leaving
 */
public record SessionSummary(
        String sessionId,
        String learnerId,
        String deckKey,
        LocalDate on,
        AttemptTally tally,
        double accuracy,
        int distinctCells,
        Optional<Double> medianElapsedMs,
        boolean closed,
        double firstTryAccuracy,
        List<String> unresolved) {

    private static final int PERCENT = 100;

    public SessionSummary {
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(learnerId, "learnerId");
        Objects.requireNonNull(deckKey, "deckKey");
        Objects.requireNonNull(on, "on");
        Objects.requireNonNull(tally, "tally");
        Objects.requireNonNull(medianElapsedMs, "medianElapsedMs");
        unresolved = List.copyOf(unresolved);
    }

    /**
     * The sitting read out for the learner, such as {@code 3 answers across 2 cells on 2025-03-03,
     * 67 percent right, 50 percent at the first try, 1 still to go over}. The first try share is left
     * out when it is the same as the overall one, and the cells to go over when there are none.
     */
    public String describe() {
        if (tally.total() == 0) {
            return "no answers on " + on;
        }
        long percent = Numbers.round(accuracy * PERCENT);
        long firstTry = Numbers.round(firstTryAccuracy * PERCENT);
        String text = tally.total() + " answers across " + distinctCells + " cells on " + on + ", " + percent
                + " percent right";
        if (firstTry != percent) {
            text += ", " + firstTry + " percent at the first try";
        }
        if (!unresolved.isEmpty()) {
            text += ", " + unresolved.size() + " still to go over";
        }
        return text;
    }
}
