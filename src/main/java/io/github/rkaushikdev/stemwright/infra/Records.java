package io.github.rkaushikdev.stemwright.infra;

import io.github.rkaushikdev.stemwright.domain.errors.ValidationException;
import io.github.rkaushikdev.stemwright.domain.mastery.Attempt;
import io.github.rkaushikdev.stemwright.domain.mastery.AttemptInput;
import io.github.rkaushikdev.stemwright.domain.mastery.CellStrength;
import io.github.rkaushikdev.stemwright.domain.mastery.MasteryLedger;
import io.github.rkaushikdev.stemwright.domain.text.Collation;
import io.github.rkaushikdev.stemwright.domain.text.Numbers;
import io.github.rkaushikdev.stemwright.infra.json.Json;
import io.github.rkaushikdev.stemwright.infra.json.JsonObject;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Record codecs for the two things written on every answer, and for the ledger that holds one of
 * them per cell.
 *
 * <p>An attempt is rebuilt through {@link Attempt#of} rather than put straight into its record, so a
 * row that a bug wrote badly is refused on the way in rather than on the way out. A strength is
 * rebuilt by hand, because it has no checking factory: it is derived state and anything it could
 * validate would already have been checked when the attempt that produced it was written. Its counts
 * are still checked here, because the record itself checks nothing.
 *
 * <p>Every decoder names the path it was handed in what it refuses, so a row read out of a journal
 * line can be reported as {@code journal.7.cellId} rather than as {@code attempt.cellId}.
 */
public final class Records {

    private static final Comparator<CellStrength> BY_CELL = Comparator.comparing(CellStrength::cellId, Collation.ORDER);

    private Records() {}

    /** Every field of the attempt but its derived id, and its elapsed time only when it was timed. */
    public static JsonObject encodeAttempt(Attempt entry) {
        return Json.object()
                .put("learnerId", entry.learnerId())
                .put("sessionId", entry.sessionId())
                .put("cellId", entry.cellId())
                .put("deckKey", entry.deckKey())
                .put("on", entry.on().toString())
                .put("sequence", entry.sequence())
                .put("given", entry.given())
                .put("expected", entry.expected())
                .put("verdict", entry.verdict().key())
                .put("toleranceLevel", entry.toleranceLevel())
                .put("revisionId", entry.revisionId())
                .putIfPresent("elapsedMs", entry.elapsedMs().map(Json::of))
                .build();
    }

    /** {@link #decodeAttempt(Json, String)} reporting failures under {@code attempt}. */
    public static Attempt decodeAttempt(Json value) {
        return decodeAttempt(value, "attempt");
    }

    /**
     * Reads an attempt back and puts it through {@link Attempt#of}.
     *
     * <p>A field of the wrong shape and a verdict that is not one of the four are refused under
     * {@code path}; what {@link Attempt#of} refuses is reported as it reports it. A sequence an
     * {@code int} cannot carry, a fraction or one past {@link Integer#MAX_VALUE}, goes in as zero,
     * which {@link Attempt#of} refuses at the same point among its checks as any other bad sequence,
     * and the refusal is then restated for the number the row held: {@code sequence must be a positive
     * whole number: 1.5}.
     *
     * @throws ValidationException when the row is not a usable attempt
     */
    public static Attempt decodeAttempt(Json value, String path) {
        JsonObject row = Codec.requireObject(path, value);
        String verdict = Codec.readString(row, path, "verdict");
        if (!Attempt.isMatchVerdict(verdict)) {
            throw ValidationException.field(path + ".verdict", "is not a verdict: " + verdict);
        }
        Optional<Double> elapsedMs = Codec.readOptionalNumber(row, path, "elapsedMs");

        AttemptInput.Builder input = AttemptInput.builder()
                .learnerId(Codec.readString(row, path, "learnerId"))
                .sessionId(Codec.readString(row, path, "sessionId"))
                .cellId(Codec.readString(row, path, "cellId"))
                .deckKey(Codec.readString(row, path, "deckKey"))
                .on(Codec.readDate(row, path, "on"));
        double sequence = Codec.readNumber(row, path, "sequence");
        input.given(Codec.readString(row, path, "given"))
                .expected(Codec.readString(row, path, "expected"))
                .verdict(verdict)
                .toleranceLevel(Codec.readString(row, path, "toleranceLevel"))
                .revisionId(Codec.readString(row, path, "revisionId"));
        elapsedMs.ifPresent(input::elapsedMs);

        if (Numbers.fitsInt(sequence)) {
            return Attempt.of(input.sequence((int) sequence).build());
        }
        try {
            return Attempt.of(input.sequence(0).build());
        } catch (ValidationException error) {
            throw error.restating("sequence", sequenceProblem(sequence));
        }
    }

    /**
     * Every field of the strength, its dates only when it has them, and its lapse run only when the
     * streak and the lapses would not give it back. A row written before the run was kept therefore
     * re-encodes to the bytes it was read from.
     */
    public static JsonObject encodeStrength(CellStrength strength) {
        return Json.object()
                .put("cellId", strength.cellId())
                .put("rung", strength.rung())
                .put("seen", strength.seen())
                .put("correct", strength.correct())
                .put("lapses", strength.lapses())
                .put("streak", strength.streak())
                .putIfPresent(
                        "lapseRun",
                        Optional.of(strength.lapseRun())
                                .filter(run -> run != legacyLapseRun(strength.streak(), strength.lapses()))
                                .map(run -> Json.of((long) run)))
                .put("suspended", strength.suspended())
                .putIfPresent("dueOn", strength.dueOn().map(Records::date))
                .putIfPresent("lastSeenOn", strength.lastSeenOn().map(Records::date))
                .build();
    }

    /** {@link #decodeStrength(Json, String)} reporting failures under {@code strength}. */
    public static CellStrength decodeStrength(Json value) {
        return decodeStrength(value, "strength");
    }

    /**
     * Reads a strength back, refusing a count that is not a whole number at or above zero, more
     * correct answers than sightings and a lapse run longer than the lapses, each under {@code path}.
     * A row with no lapse run takes the one its streak and lapses imply.
     *
     * @throws ValidationException when the row is not a usable strength
     */
    public static CellStrength decodeStrength(Json value, String path) {
        JsonObject row = Codec.requireObject(path, value);
        int seen = readCount(row, path, "seen");
        int correct = readCount(row, path, "correct");
        if (correct > seen) {
            throw ValidationException.field(path + ".correct", "is larger than the number of sightings");
        }
        String cellId = Codec.readString(row, path, "cellId");
        int rung = readCount(row, path, "rung");
        Optional<LocalDate> dueOn = Codec.readOptionalDate(row, path, "dueOn");
        int lapses = readCount(row, path, "lapses");
        int streak = readCount(row, path, "streak");
        int lapseRun =
                row.get("lapseRun").isPresent() ? readCount(row, path, "lapseRun") : legacyLapseRun(streak, lapses);
        if (lapseRun > lapses) {
            throw ValidationException.field(path + ".lapseRun", "is longer than the number of lapses");
        }
        Optional<LocalDate> lastSeenOn = Codec.readOptionalDate(row, path, "lastSeenOn");
        boolean suspended = Codec.readBoolean(row, path, "suspended");
        return new CellStrength(cellId, rung, dueOn, seen, correct, lapses, streak, lapseRun, lastSeenOn, suspended);
    }

    /**
     * The lapse run a row is read as when it does not carry one, which is how the run was worked out
     * before it was kept: a streak above nought means the latest answer was right and the run is
     * over, and otherwise every lapse is taken to be in it.
     */
    private static int legacyLapseRun(int streak, int lapses) {
        return streak > 0 ? 0 : lapses;
    }

    /**
     * The learner and every stored strength, the cells in the order a reader sorts their ids, so two
     * saves of the same ledger compare equal.
     */
    public static JsonObject encodeLedger(MasteryLedger ledger) {
        return Json.object()
                .put("learnerId", ledger.learnerId())
                .put(
                        "cells",
                        Json.array(ledger.byCell().values().stream()
                                .sorted(BY_CELL)
                                .map(Records::encodeStrength)
                                .toList()))
                .build();
    }

    /** {@link #decodeLedger(Json, String)} reporting failures under {@code ledger}. */
    public static MasteryLedger decodeLedger(Json value) {
        return decodeLedger(value, "ledger");
    }

    /**
     * Reads a ledger back, each cell under {@code path.cells.<index>}.
     *
     * @throws ValidationException when the row or any of its cells is not usable
     */
    public static MasteryLedger decodeLedger(Json value, String path) {
        JsonObject row = Codec.requireObject(path, value);
        List<Json> cells = Codec.readArray(row, path, "cells");
        String learnerId = Codec.readString(row, path, "learnerId");
        List<CellStrength> strengths = new ArrayList<>(cells.size());
        for (int index = 0; index < cells.size(); index += 1) {
            strengths.add(decodeStrength(cells.get(index), path + ".cells." + index));
        }
        return MasteryLedger.of(learnerId, strengths);
    }

    private static Json date(LocalDate day) {
        return Json.of(day.toString());
    }

    /** A count: a whole number at or above zero, and one an {@code int} can hold. */
    private static int readCount(JsonObject row, String path, String key) {
        double value = Codec.readNumber(row, path, key);
        if (!Numbers.isInteger(value) || value < 0) {
            throw ValidationException.field(
                    path + "." + key, "must be a whole number at or above zero: " + Numbers.format(value));
        }
        if (value > Integer.MAX_VALUE) {
            throw ValidationException.field(path + "." + key, tooLarge(value));
        }
        return (int) value;
    }

    /** What is wrong with a finite sequence an {@code int} cannot carry. */
    private static String sequenceProblem(double value) {
        return Numbers.isInteger(value) && value > Integer.MAX_VALUE
                ? tooLarge(value)
                : "must be a positive whole number: " + Numbers.format(value);
    }

    private static String tooLarge(double value) {
        return "must be at most " + Integer.MAX_VALUE + ": " + Numbers.format(value);
    }
}
