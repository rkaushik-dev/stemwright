package io.github.rkaushikdev.stemwright.domain.drill;

import io.github.rkaushikdev.stemwright.domain.mastery.Attempt;
import io.github.rkaushikdev.stemwright.domain.mastery.MasteryLedger;
import io.github.rkaushikdev.stemwright.domain.ortho.MatchResult;
import java.util.List;
import java.util.Objects;

/**
 * Everything one answer changed, for the caller to write back: the attempt for the log, the ledger,
 * and the burial set for the open session.
 *
 * @param attempt the answer as it is to be recorded
 * @param match how the answer was marked
 * @param ledger the ledger with the cell rescheduled and its twins credited
 * @param buried the burial set the session carries on with
 * @param expected the form the answer was marked against
 * @param revisionId the profile revision the expected form came from
 * @param twinsCredited how many twins the answer moved
 * @param leechFired whether the leech policy fired on the cell
 * @param variants the other forms the cell would have accepted, in the order its slot wrote them
 */
public record ReviewResult(
        Attempt attempt,
        MatchResult match,
        MasteryLedger ledger,
        BurialSet buried,
        String expected,
        String revisionId,
        int twinsCredited,
        boolean leechFired,
        List<String> variants) {

    public ReviewResult {
        Objects.requireNonNull(attempt, "attempt");
        Objects.requireNonNull(match, "match");
        Objects.requireNonNull(ledger, "ledger");
        Objects.requireNonNull(buried, "buried");
        Objects.requireNonNull(expected, "expected");
        Objects.requireNonNull(revisionId, "revisionId");
        variants = List.copyOf(variants);
    }
}
