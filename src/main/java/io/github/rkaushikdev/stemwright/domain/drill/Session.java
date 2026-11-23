package io.github.rkaushikdev.stemwright.domain.drill;

import io.github.rkaushikdev.stemwright.domain.IdKind;
import io.github.rkaushikdev.stemwright.domain.Identifiers;
import io.github.rkaushikdev.stemwright.domain.errors.ConflictException;
import io.github.rkaushikdev.stemwright.domain.errors.ValidationException;
import io.github.rkaushikdev.stemwright.domain.mastery.Attempt;
import io.github.rkaushikdev.stemwright.domain.mastery.AttemptTally;
import io.github.rkaushikdev.stemwright.domain.ortho.MatchVerdict;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A session is one sitting. It exists because burial is a session scoped fact: the twins of an
 * answered cell stay out of the way until the learner closes the tab, and nothing longer lived
 * should carry that.
 *
 * <p>A session is append only while it is open and frozen once closed. Reopening would let an
 * answer be recorded against a burial set that has moved on, which is the one way the twin rule can
 * be made to contradict itself.
 *
 * <p>Start one through {@link #open}; the canonical constructor only copies what it is given.
 *
 * @param id the derived session id, see {@link #idFor(String, String, LocalDate, int)}
 * @param learnerId the learner sitting it
 * @param deckKey the deck being drilled, a slug
 * @param on the study day of the sitting
 * @param attempts every answer recorded, in sequence order
 * @param buried the cells buried so far in the sitting
 * @param closed whether the sitting is over
 */
public record Session(
        String id,
        String learnerId,
        String deckKey,
        LocalDate on,
        List<Attempt> attempts,
        BurialSet buried,
        boolean closed) {

    public Session {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(learnerId, "learnerId");
        Objects.requireNonNull(deckKey, "deckKey");
        Objects.requireNonNull(on, "on");
        attempts = List.copyOf(attempts);
        Objects.requireNonNull(buried, "buried");
    }

    /** The id of the learner's first sitting of the deck on the day. */
    public static String idFor(String learnerId, String deckKey, LocalDate on) {
        return idFor(learnerId, deckKey, on, 1);
    }

    /**
     * The id is derived from the learner, the deck, the day and an ordinal, so the first sitting of
     * a day always has the same id and a second one on the same day is told apart by its ordinal.
     */
    public static String idFor(String learnerId, String deckKey, LocalDate on, int ordinal) {
        return Identifiers.derivedId(IdKind.SESSION, learnerId, deckKey, on.toString(), Integer.toString(ordinal));
    }

    /** The learner's first sitting of the deck on the day, open and empty. */
    public static Session open(String learnerId, String deckKey, LocalDate on) {
        return open(learnerId, deckKey, on, 1);
    }

    /**
     * A sitting, open and empty and burying nothing.
     *
     * @throws ValidationException on {@code deckKey} when the deck key is not a slug
     */
    public static Session open(String learnerId, String deckKey, LocalDate on, int ordinal) {
        String key = Identifiers.asSlug("deckKey", deckKey);
        return new Session(idFor(learnerId, key, on, ordinal), learnerId, key, on, List.of(), BurialSet.NONE, false);
    }

    /** The sequence number the next answer must carry. */
    public int nextSequence() {
        return attempts.size() + 1;
    }

    /**
     * A copy with the answer appended and the burial set it left behind.
     *
     * @throws ConflictException when the session is closed
     * @throws ValidationException when the answer belongs to another session or its sequence number
     *     does not follow on
     */
    public Session recordAttempt(Attempt entry, BurialSet nextBuried) {
        if (closed) {
            throw new ConflictException("session " + id + " is closed");
        }
        if (!entry.sessionId().equals(id)) {
            throw ValidationException.field("attempt.sessionId", "belongs to another session: " + entry.sessionId());
        }
        if (entry.sequence() != nextSequence()) {
            throw ValidationException.field(
                    "attempt.sequence", "is out of order, expected " + nextSequence() + " and got " + entry.sequence());
        }
        List<Attempt> grown = new ArrayList<>(attempts);
        grown.add(entry);
        return new Session(id, learnerId, deckKey, on, grown, nextBuried, closed);
    }

    /** A closed copy, or this very session when it is already closed. */
    public Session close() {
        return closed ? this : new Session(id, learnerId, deckKey, on, attempts, buried, true);
    }

    /** How many answers the sitting holds. */
    public int length() {
        return attempts.size();
    }

    /** Every cell answered, once each, in the order first answered. */
    public List<String> cellsSeen() {
        return attempts.stream().map(Attempt::cellId).distinct().toList();
    }

    /** Every answer given to the cell, in sequence order. */
    public List<Attempt> attemptsForCell(String cellId) {
        return attempts.stream().filter(entry -> entry.cellId().equals(cellId)).toList();
    }

    /**
     * The sitting counted up: the verdicts, the accuracy, the distinct cells, the median time, the
     * share of cells right at the first try and the cells still unresolved.
     */
    public SessionSummary summarise() {
        AttemptTally tallied = AttemptTally.of(attempts);
        List<String> seen = cellsSeen();
        long rightFirst = seen.stream()
                .filter(cellId -> attemptsForCell(cellId).getFirst().wasCorrect())
                .count();
        return new SessionSummary(
                id,
                learnerId,
                deckKey,
                on,
                tallied,
                tallied.accuracy(),
                seen.size(),
                Attempt.medianElapsed(attempts),
                closed,
                seen.isEmpty() ? 0 : (double) rightFirst / seen.size(),
                unresolvedCells());
    }

    /**
     * Cells the learner got wrong and did not later get right in the same sitting, in natural
     * string order. A near miss left standing counts as unresolved.
     */
    public List<String> unresolvedCells() {
        Map<String, Attempt> lastVerdict = new LinkedHashMap<>();
        for (Attempt entry : attempts) {
            lastVerdict.put(entry.cellId(), entry);
        }
        return lastVerdict.entrySet().stream()
                .filter(last -> last.getValue().verdict() == MatchVerdict.WRONG
                        || last.getValue().verdict() == MatchVerdict.NEAR)
                .map(Map.Entry::getKey)
                .sorted()
                .toList();
    }
}
