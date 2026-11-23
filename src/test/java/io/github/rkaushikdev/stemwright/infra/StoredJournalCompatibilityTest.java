package io.github.rkaushikdev.stemwright.infra;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.rkaushikdev.stemwright.app.Clock;
import io.github.rkaushikdev.stemwright.app.SteppableClock;
import io.github.rkaushikdev.stemwright.app.Stores;
import io.github.rkaushikdev.stemwright.domain.mastery.Attempt;
import io.github.rkaushikdev.stemwright.domain.mastery.AttemptInput;
import io.github.rkaushikdev.stemwright.domain.mastery.CellStrength;
import io.github.rkaushikdev.stemwright.domain.mastery.Ladder;
import io.github.rkaushikdev.stemwright.domain.mastery.LadderInput;
import io.github.rkaushikdev.stemwright.domain.mastery.MasteryLedger;
import io.github.rkaushikdev.stemwright.domain.ortho.MatchVerdict;
import io.github.rkaushikdev.stemwright.domain.time.CivilDates;
import io.github.rkaushikdev.stemwright.domain.time.Instants;
import io.github.rkaushikdev.stemwright.infra.json.Json;
import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A data directory already on disk has to load unchanged, and what is written now has to be byte
 * for byte what is already stored, because the journal format is fixed.
 *
 * <p>The fixture under {@code src/test/resources/journal} is such a directory, written by hand from
 * the format rather than by this code: {@code journal.ndjson} holds nine entries as {@link
 * FileJournal} appends them, each line the compact json of seq, at, kind and payload and each
 * ended with a line feed, the payloads in the key order of {@code encodeAttempt} and {@code
 * encodeLedger}, and the stamps as ISO 8601 instants in utc with three millisecond digits; {@code
 * snapshots/000000000007.json} is what {@code compact} writes at sequence seven, the json indented
 * by two with no line end after it. The answers they record are the ones below, two learners over
 * three days on a flat ladder, one Greek answer untimed and two Latin ones appended together.
 */
class StoredJournalCompatibilityTest {

    private static final Ladder FLAT =
            Ladder.of(LadderInput.builder("flat", 1, 3, 7).spread(0).build());
    private static final LocalDate MONDAY = CivilDates.parseCivilDate("2025-03-03");
    private static final LocalDate TUESDAY = CivilDates.parseCivilDate("2025-03-04");
    private static final LocalDate THURSDAY = CivilDates.parseCivilDate("2025-03-06");
    private static final Instant SNAPSHOT_AT = Instant.parse("2025-03-05T02:00:00Z");
    private static final String SNAPSHOT_NAME = "000000000007.json";

    private static final Attempt FIRST = Attempt.of(latin("cell_aaaaaa", 1, "puellae", "puellae", MatchVerdict.EXACT)
            .elapsedMs(1840)
            .build());
    private static final Attempt SECOND = Attempt.of(latin("cell_bbbbbb", 2, "puellis", "puellarum", MatchVerdict.WRONG)
            .elapsedMs(912.5)
            .build());
    private static final Attempt THIRD = Attempt.of(latin("cell_cccccc", 3, "puella", "puellā", MatchVerdict.TOLERATED)
            .elapsedMs(2075)
            .build());
    private static final Attempt GREEK = Attempt.of(AttemptInput.builder()
            .learnerId("lrn_two")
            .sessionId("sess_bbbbbb")
            .cellId("cell_dddddd")
            .deckKey("greek-verbs")
            .on(TUESDAY)
            .sequence(1)
            .given("λύομεν")
            .expected("λύομεν")
            .verdict(MatchVerdict.EXACT)
            .toleranceLevel("strict")
            .revisionId("rev_bbbbbb")
            .build());
    private static final Attempt RETURN = Attempt.of(latin("cell_aaaaaa", 1, "puellae", "puellae", MatchVerdict.EXACT)
            .sessionId("sess_cccccc")
            .on(THURSDAY)
            .elapsedMs(1203.75)
            .build());

    private static final CellStrength A_MONDAY =
            CellStrength.fresh("cell_aaaaaa").recordCorrect(FLAT, MONDAY);
    private static final CellStrength B_MONDAY =
            CellStrength.fresh("cell_bbbbbb").recordLapse(FLAT, MONDAY);
    private static final CellStrength C_MONDAY =
            CellStrength.fresh("cell_cccccc").recordCorrect(FLAT, MONDAY);
    private static final CellStrength D_TUESDAY =
            CellStrength.fresh("cell_dddddd").recordCorrect(FLAT, TUESDAY);
    private static final CellStrength E_UNSEEN = CellStrength.fresh("cell_eeeeee");
    private static final CellStrength A_THURSDAY = A_MONDAY.recordCorrect(FLAT, THURSDAY);
    private static final CellStrength B_SUSPENDED = B_MONDAY.suspend();

    private static AttemptInput.Builder latin(
            String cellId, int sequence, String given, String expected, MatchVerdict verdict) {
        return AttemptInput.builder()
                .learnerId("lrn_one")
                .sessionId("sess_aaaaaa")
                .cellId(cellId)
                .deckKey("first-year")
                .on(MONDAY)
                .sequence(sequence)
                .given(given)
                .expected(expected)
                .verdict(verdict)
                .toleranceLevel("standard")
                .revisionId("rev_aaaaaa");
    }

    private static Path fixture(String relative) {
        URL url = Objects.requireNonNull(
                StoredJournalCompatibilityTest.class.getResource("/journal/" + relative), "fixture " + relative);
        try {
            return Path.of(url.toURI());
        } catch (URISyntaxException error) {
            throw new IllegalStateException(error);
        }
    }

    private static byte[] journalBytes() throws IOException {
        return Files.readAllBytes(fixture("journal.ndjson"));
    }

    private static byte[] snapshotBytes() throws IOException {
        return Files.readAllBytes(fixture("snapshots/" + SNAPSHOT_NAME));
    }

    /** The fixture's journal, and its snapshot too when asked, copied into a fresh data directory. */
    private static DataLayout dataDirectory(Path root, boolean withSnapshot) throws IOException {
        DataLayout layout = DataLayout.of(root);
        Files.write(layout.journalPath(), journalBytes());
        if (withSnapshot) {
            Files.createDirectories(layout.snapshotDir());
            Files.write(layout.snapshotDir().resolve(SNAPSHOT_NAME), snapshotBytes());
        }
        return layout;
    }

    @Test
    void replaysAStoredJournalIntoTheStateItRecorded(@TempDir Path root) throws IOException {
        Journal journal = FileJournal.open(dataDirectory(root, false));
        Stores stores = MemoryStores.create();

        ReplayReport report = Replay.apply(journal, stores);

        assertThat(report).isEqualTo(new ReplayReport(9, 0, 9));
        assertThat(stores.attempts().listByLearner("lrn_one")).containsExactly(FIRST, SECOND, THIRD, RETURN);
        assertThat(stores.attempts().listByLearner("lrn_two")).containsExactly(GREEK);
        assertThat(stores.attempts().listByLearner("lrn_two").get(0).elapsedMs())
                .isEmpty();
        assertThat(stores.ledgers().get("lrn_one"))
                .contains(MasteryLedger.of("lrn_one", List.of(A_THURSDAY, B_SUSPENDED, C_MONDAY)));
        assertThat(stores.ledgers().get("lrn_two")).contains(MasteryLedger.of("lrn_two", List.of(D_TUESDAY, E_UNSEEN)));
        assertThat(stores.ledgers().get("lrn_one").orElseThrow().strengthFor("cell_aaaaaa"))
                .isEqualTo(new CellStrength(
                        "cell_aaaaaa",
                        1,
                        Optional.of(CivilDates.parseCivilDate("2025-03-09")),
                        2,
                        2,
                        0,
                        2,
                        0,
                        Optional.of(THURSDAY),
                        false));
    }

    @Test
    void reEncodesEveryReplayedRecordToTheBytesItWasReadFrom() throws IOException {
        byte[] stored = journalBytes();
        List<JournalEntry> entries = Journal.decode(new String(stored, StandardCharsets.UTF_8));
        assertThat(entries).hasSize(9);

        List<JournalEntry> reEncoded = new ArrayList<>();
        for (JournalEntry entry : entries) {
            String path = "journal." + entry.seq();
            Json payload =
                    switch (entry.kind()) {
                        case ATTEMPT_APPENDED -> Records.encodeAttempt(Records.decodeAttempt(entry.payload(), path));
                        case LEDGER_PUT -> Records.encodeLedger(Records.decodeLedger(entry.payload(), path));
                        default -> throw new AssertionError("the fixture holds only attempts and ledgers: " + entry);
                    };
            assertThat(Instants.toIsoString(Instant.parse(entry.at()))).isEqualTo(entry.at());
            reEncoded.add(new JournalEntry(entry.seq(), entry.at(), entry.kind(), payload));
        }

        assertThat(Journal.encode(reEncoded).getBytes(StandardCharsets.UTF_8)).isEqualTo(stored);
    }

    @Test
    void writesTheStoredBytesForTheSameAnswers(@TempDir Path root) throws IOException {
        DataLayout layout = DataLayout.of(root);
        SteppableClock clock = Clock.steppable(Instant.parse("2025-03-03T09:00:00Z"), MONDAY);
        Stores stores = JournalledStores.of(MemoryStores.create(), FileJournal.open(layout), clock)
                .stores();

        stores.attempts().append(List.of(FIRST));
        clock.set(Instant.parse("2025-03-03T09:00:00.004Z"), MONDAY);
        stores.ledgers().put(MasteryLedger.of("lrn_one", List.of(A_MONDAY)));
        clock.set(Instant.parse("2025-03-03T09:00:41.250Z"), MONDAY);
        stores.attempts().append(List.of(SECOND, THIRD));
        clock.set(Instant.parse("2025-03-03T09:00:41.262Z"), MONDAY);
        stores.ledgers().put(MasteryLedger.of("lrn_one", List.of(A_MONDAY, C_MONDAY, B_MONDAY)));
        clock.set(Instant.parse("2025-03-04T18:30:05.120Z"), TUESDAY);
        stores.attempts().append(List.of(GREEK));
        clock.set(Instant.parse("2025-03-04T18:30:05.131Z"), TUESDAY);
        stores.ledgers().put(MasteryLedger.of("lrn_two", List.of(E_UNSEEN, D_TUESDAY)));
        clock.set(Instant.parse("2025-03-06T07:45:00Z"), THURSDAY);
        stores.attempts().append(List.of(RETURN));
        clock.set(Instant.parse("2025-03-06T07:45:00.009Z"), THURSDAY);
        stores.ledgers()
                .put(stores.ledgers()
                        .get("lrn_one")
                        .orElseThrow()
                        .withStrength(A_THURSDAY)
                        .withStrength(B_SUSPENDED));

        assertThat(Files.readAllBytes(layout.journalPath())).isEqualTo(journalBytes());
    }

    @Test
    void bootstrapsFromAStoredSnapshotToTheStateTheWholeJournalGives(
            @TempDir Path withSnapshot, @TempDir Path journalOnly) throws IOException {
        DataLayout fast = dataDirectory(withSnapshot, true);
        Stores fromSnapshot = MemoryStores.create();
        BootstrapReport shortcut = Snapshot.bootstrap(fast, FileJournal.open(fast), fromSnapshot);

        DataLayout slow = dataDirectory(journalOnly, false);
        Stores fromJournal = MemoryStores.create();
        BootstrapReport longWay = Snapshot.bootstrap(slow, FileJournal.open(slow), fromJournal);

        assertThat(shortcut).isEqualTo(new BootstrapReport(2, 0, 9, Optional.of(7L)));
        assertThat(longWay).isEqualTo(new BootstrapReport(9, 0, 9, Optional.empty()));
        for (String learnerId : List.of("lrn_one", "lrn_two")) {
            assertThat(fromSnapshot.attempts().listByLearner(learnerId))
                    .isEqualTo(fromJournal.attempts().listByLearner(learnerId))
                    .isNotEmpty();
            assertThat(fromSnapshot.ledgers().get(learnerId))
                    .isEqualTo(fromJournal.ledgers().get(learnerId))
                    .isPresent();
        }
    }

    @Test
    void writesTheStoredSnapshotByteForByte(@TempDir Path root) throws IOException {
        List<JournalEntry> entries = Journal.decode(new String(journalBytes(), StandardCharsets.UTF_8));
        Stores stores = MemoryStores.create();
        Replay.apply(entries.subList(0, 7), stores);

        Snapshot built = Snapshot.build(stores, List.of("lrn_two", "lrn_one"), 7, SNAPSHOT_AT);
        Path written = Snapshot.write(DataLayout.of(root), built);

        assertThat(written.getFileName()).hasToString(SNAPSHOT_NAME);
        assertThat(Files.readAllBytes(written)).isEqualTo(snapshotBytes());
        assertThat(Snapshot.decode(new String(snapshotBytes(), StandardCharsets.UTF_8)))
                .isEqualTo(built);
    }
}
