package io.github.rkaushikdev.stemwright.infra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.stemwright.domain.errors.ValidationException;
import io.github.rkaushikdev.stemwright.domain.mastery.Attempt;
import io.github.rkaushikdev.stemwright.domain.mastery.AttemptInput;
import io.github.rkaushikdev.stemwright.domain.mastery.CellStrength;
import io.github.rkaushikdev.stemwright.domain.mastery.Ladder;
import io.github.rkaushikdev.stemwright.domain.mastery.LadderInput;
import io.github.rkaushikdev.stemwright.domain.mastery.MasteryLedger;
import io.github.rkaushikdev.stemwright.domain.ortho.MatchVerdict;
import io.github.rkaushikdev.stemwright.domain.time.CivilDates;
import io.github.rkaushikdev.stemwright.infra.json.Json;
import io.github.rkaushikdev.stemwright.infra.json.JsonArray;
import io.github.rkaushikdev.stemwright.infra.json.JsonObject;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class RecordsTest {

    private static final LocalDate DAY = CivilDates.parseCivilDate("2025-03-03");
    private static final Ladder FLAT =
            Ladder.of(LadderInput.builder("flat", 1, 3, 7).spread(0).build());

    private static AttemptInput.Builder base() {
        return AttemptInput.builder()
                .learnerId("lrn_one")
                .sessionId("sess_aaaaaa")
                .cellId("cell_aaaaaa")
                .deckKey("first-year")
                .on(DAY)
                .sequence(1)
                .given("puellae")
                .expected("puellae")
                .verdict(MatchVerdict.EXACT)
                .toleranceLevel("standard")
                .revisionId("rev_aaaaaa");
    }

    private static JsonObject without(JsonObject row, String key) {
        Map<String, Json> fields = new LinkedHashMap<>(row.fields());
        fields.remove(key);
        return new JsonObject(fields);
    }

    @Nested
    class AttemptCodec {

        @Test
        void roundTripsEveryField() {
            Attempt entry = Attempt.of(base().elapsedMs(900).build());
            assertThat(Records.decodeAttempt(Records.encodeAttempt(entry))).isEqualTo(entry);
        }

        @Test
        void leavesTheElapsedTimeOutWhenThereIsNone() {
            JsonObject encoded = Records.encodeAttempt(Attempt.of(base().build()));
            assertThat(encoded.has("elapsedMs")).isFalse();
            assertThat(Records.decodeAttempt(encoded).elapsedMs()).isEmpty();
        }

        @Test
        void refusesARowThatIsNotAnObject() {
            assertThatThrownBy(() -> Records.decodeAttempt(Json.of("nope")))
                    .hasMessageContaining("attempt is not an object");
        }

        @Test
        void refusesAVerdictThatIsNotOneOfTheFour() {
            JsonObject encoded =
                    Records.encodeAttempt(Attempt.of(base().build())).with("verdict", Json.of("partial"));
            assertThatThrownBy(() -> Records.decodeAttempt(encoded)).hasMessageContaining("is not a verdict: partial");
        }

        @Test
        void refusesARowMissingAField() {
            JsonObject encoded = without(Records.encodeAttempt(Attempt.of(base().build())), "cellId");
            assertThatThrownBy(() -> Records.decodeAttempt(encoded))
                    .hasMessageContaining("attempt.cellId is not a string");
        }

        @Test
        void putsTheRowThroughTheDomainConstructor() {
            JsonObject encoded =
                    Records.encodeAttempt(Attempt.of(base().build())).with("sequence", Json.of(0));
            assertThatThrownBy(() -> Records.decodeAttempt(encoded)).isInstanceOf(ValidationException.class);
        }

        @Test
        void namesThePathItWasGiven() {
            assertThatThrownBy(() -> Records.decodeAttempt(Json.of("nope"), "journal.7"))
                    .hasMessageContaining("journal.7 is not an object");
        }
    }

    @Nested
    class StrengthCodec {

        private final CellStrength seen =
                CellStrength.fresh("cell_a").recordCorrect(FLAT, DAY).recordCorrect(FLAT, DAY);

        @Test
        void roundTripsASeenCell() {
            assertThat(Records.decodeStrength(Records.encodeStrength(seen))).isEqualTo(seen);
        }

        @Test
        void roundTripsACellNobodyHasAnswered() {
            CellStrength fresh = CellStrength.fresh("cell_a");
            assertThat(Records.decodeStrength(Records.encodeStrength(fresh))).isEqualTo(fresh);
        }

        @Test
        void roundTripsASuspendedCell() {
            CellStrength leech = seen.recordLapse(FLAT, DAY).suspend();
            assertThat(Records.decodeStrength(Records.encodeStrength(leech))).isEqualTo(leech);
        }

        @Test
        void leavesTheDatesOutWhenTheyAreAbsent() {
            JsonObject encoded = Records.encodeStrength(CellStrength.fresh("cell_a"));
            assertThat(encoded.has("dueOn")).isFalse();
            assertThat(encoded.has("lastSeenOn")).isFalse();
        }

        @Test
        void refusesANegativeCount() {
            JsonObject encoded = Records.encodeStrength(seen).with("lapses", Json.of(-1));
            assertThatThrownBy(() -> Records.decodeStrength(encoded))
                    .hasMessageContaining("lapses must be a whole number");
        }

        @Test
        void refusesMoreCorrectAnswersThanSightings() {
            JsonObject encoded = Records.encodeStrength(seen).with("correct", Json.of(9));
            assertThatThrownBy(() -> Records.decodeStrength(encoded))
                    .hasMessageContaining("is larger than the number of sightings");
        }

        @Test
        void writesTheLapseRunOnlyWhenTheStreakWouldNotGiveItBack() {
            CellStrength relapsed = seen.recordLapse(FLAT, DAY)
                    .recordLapse(FLAT, DAY)
                    .recordCorrect(FLAT, DAY)
                    .recordLapse(FLAT, DAY);
            assertThat(Records.encodeStrength(seen).get("lapseRun")).isEmpty();
            assertThat(Records.encodeStrength(seen.recordLapse(FLAT, DAY)).get("lapseRun"))
                    .isEmpty();
            assertThat(Records.encodeStrength(relapsed).get("lapseRun")).contains(Json.of(1));
            assertThat(Records.decodeStrength(Records.encodeStrength(relapsed))).isEqualTo(relapsed);
        }

        @Test
        void readsARowWithNoLapseRunTheWayItUsedToBeWorkedOut() {
            JsonObject older =
                    Records.encodeStrength(seen).with("lapses", Json.of(3)).with("streak", Json.of(0));
            assertThat(Records.decodeStrength(older).lapseRun()).isEqualTo(3);
            assertThat(Records.decodeStrength(older.with("streak", Json.of(2))).lapseRun())
                    .isZero();
        }

        @Test
        void refusesALapseRunLongerThanTheLapses() {
            JsonObject encoded = Records.encodeStrength(seen).with("lapseRun", Json.of(2));
            assertThatThrownBy(() -> Records.decodeStrength(encoded))
                    .isInstanceOf(ValidationException.class)
                    .hasMessageContaining("lapseRun");
        }

        @Test
        void refusesAFractionalRung() {
            JsonObject encoded = Records.encodeStrength(seen).with("rung", Json.of(1.5));
            assertThatThrownBy(() -> Records.decodeStrength(encoded)).isInstanceOf(ValidationException.class);
        }
    }

    @Nested
    class LedgerCodec {

        private final MasteryLedger ledger = MasteryLedger.of(
                "lrn_one",
                List.of(CellStrength.fresh("cell_b").recordCorrect(FLAT, DAY), CellStrength.fresh("cell_a")));

        @Test
        void roundTripsEveryCell() {
            MasteryLedger decoded = Records.decodeLedger(Records.encodeLedger(ledger));
            assertThat(decoded.learnerId()).isEqualTo("lrn_one");
            assertThat(decoded.byCell()).hasSize(2);
            assertThat(decoded.strengthFor("cell_b").seen()).isEqualTo(1);
        }

        @Test
        void writesTheCellsInKeyOrderSoTwoSavesCompareEqual() {
            JsonArray cells = Records.encodeLedger(ledger)
                    .get("cells")
                    .flatMap(Json::asArray)
                    .orElseThrow();
            assertThat(cells.items())
                    .extracting(row -> row.get("cellId").flatMap(Json::asString).orElseThrow())
                    .containsExactly("cell_a", "cell_b");
        }

        @Test
        void roundTripsAnEmptyLedger() {
            MasteryLedger empty = MasteryLedger.of("lrn_one", List.of());
            assertThat(Records.decodeLedger(Records.encodeLedger(empty)).byCell())
                    .isEmpty();
        }

        @Test
        void refusesACellsFieldThatIsNotAnArray() {
            JsonObject row = Json.object()
                    .put("learnerId", "lrn_one")
                    .put("cells", "nope")
                    .build();
            assertThatThrownBy(() -> Records.decodeLedger(row)).hasMessageContaining("ledger.cells is not an array");
        }

        @Test
        void namesTheIndexOfTheBadCell() {
            JsonObject row = Json.object()
                    .put("learnerId", "lrn_one")
                    .put("cells", Json.array(JsonObject.EMPTY))
                    .build();
            assertThatThrownBy(() -> Records.decodeLedger(row))
                    .hasMessageContaining("ledger.cells.0.seen is not a finite number");
        }
    }
}
