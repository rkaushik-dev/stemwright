package io.github.rkaushikdev.stemwright.domain.drill;

import static io.github.rkaushikdev.stemwright.support.Drill.DRILL_CONTEXT;
import static io.github.rkaushikdev.stemwright.support.Drill.PUELLA;
import static io.github.rkaushikdev.stemwright.support.Profiles.LATIN_NOUN;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.rkaushikdev.stemwright.domain.mastery.CellStrength;
import io.github.rkaushikdev.stemwright.domain.mastery.Ladder;
import io.github.rkaushikdev.stemwright.domain.mastery.LadderInput;
import io.github.rkaushikdev.stemwright.domain.mastery.MasteryLedger;
import io.github.rkaushikdev.stemwright.domain.paradigm.Cell;
import io.github.rkaushikdev.stemwright.domain.text.Text;
import io.github.rkaushikdev.stemwright.domain.time.CivilDates;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class QueueTest {

    private static final LocalDate TERM = d("2024-10-01");
    private static final Ladder FLAT =
            Ladder.of(LadderInput.builder("flat", 1, 3, 7).spread(0).build());

    private static final Membership MEMBERSHIP =
            Membership.resolve(DRILL_CONTEXT, Deck.of(LATIN_NOUN, DeckInput.of("all")), TERM);

    private static LocalDate d(String value) {
        return CivilDates.parseCivilDate(value);
    }

    private static Cell cellOf(String bundle) {
        return MEMBERSHIP.cells().stream()
                .filter(cell ->
                        cell.lexemeId().equals(PUELLA.id()) && cell.bundle().equals(bundle))
                .findFirst()
                .orElseThrow();
    }

    private static CellStrength seenOn(String cellId, String day) {
        return CellStrength.fresh(cellId).recordCorrect(FLAT, d(day));
    }

    private static DrillPolicyPatch.Builder patch() {
        return DrillPolicyPatch.builder();
    }

    private static DrillPolicy policy(DrillPolicyPatch.Builder patch) {
        return DrillPolicy.of(patch.build());
    }

    private static MasteryLedger empty() {
        return MasteryLedger.empty("lrn_one");
    }

    private static MasteryLedger ledgerOf(CellStrength... strengths) {
        return MasteryLedger.of("lrn_one", List.of(strengths));
    }

    private static Queue assemble(DrillPolicy policy, MasteryLedger ledger) {
        return Queue.assemble(DRILL_CONTEXT, MEMBERSHIP, policy, ledger, TERM);
    }

    private static List<String> bundlesOf(List<Cell> cells) {
        return cells.stream().map(Cell::bundle).toList();
    }

    @Nested
    class Assemble {

        @Test
        void introducesNewCellsUpToTheBudgetWhenNothingIsDue() {
            Queue queue = assemble(policy(patch().newPerDay(4).perLexemeCap(10).rowSpacing(0)), empty());
            assertThat(queue.length()).isEqualTo(4);
            assertThat(queue.newCount()).isEqualTo(4);
            assertThat(queue.reviewCount()).isEqualTo(0);
        }

        @Test
        void marksTheCellsTheNewBudgetLeftOut() {
            Queue queue = assemble(policy(patch().newPerDay(2).perLexemeCap(10).rowSpacing(0)), empty());
            assertThat(queue.excludedFor(ExclusionReason.NEW_BUDGET)).hasSize(28);
        }

        @Test
        void putsReviewsBeforeNewCells() {
            MasteryLedger ledger = ledgerOf(seenOn(cellOf("case=gen;number=sg").id(), "2024-09-20"));
            Queue queue = assemble(policy(patch().newPerDay(2).perLexemeCap(10).rowSpacing(0)), ledger);
            assertThat(queue.entries().get(0).kind()).isEqualTo(QueueEntry.Kind.REVIEW);
            assertThat(queue.entries().get(0).cell().bundle()).isEqualTo("case=gen;number=sg");
        }

        @Test
        void sortsReviewsOnTheSameIntervalWithTheMostOverdueFirst() {
            MasteryLedger ledger = ledgerOf(
                    seenOn(cellOf("case=gen;number=sg").id(), "2024-09-28"),
                    seenOn(cellOf("case=dat;number=sg").id(), "2024-09-10"));
            Queue queue = assemble(policy(patch().newPerDay(0).perLexemeCap(10).rowSpacing(0)), ledger);
            assertThat(bundlesOf(queue.cells())).containsExactly("case=dat;number=sg", "case=gen;number=sg");
            assertThat(queue.entries().get(0).overdueDays()).isEqualTo(20);
        }

        @Test
        void spendsTheReviewBudgetOnTheCellFurthestPastItsOwnInterval() {
            String shortId = cellOf("case=gen;number=sg").id();
            String longId = cellOf("case=dat;number=sg").id();
            CellStrength shortInterval = seenOn(shortId, "2024-09-28");
            CellStrength longInterval = new CellStrength(
                    longId, 3, Optional.of(d("2024-09-20")), 4, 4, 0, 4, 0, Optional.of(d("2024-09-04")), false);
            Queue queue = assemble(
                    policy(patch().newPerDay(0)
                            .reviewsPerDay(1)
                            .perLexemeCap(10)
                            .rowSpacing(0)),
                    ledgerOf(longInterval, shortInterval));

            assertThat(queue.cells()).extracting(Cell::id).containsExactly(shortId);
            assertThat(queue.entries().getFirst().overdueDays()).isEqualTo(2);
            assertThat(queue.whyExcluded(longId)).contains(ExclusionReason.REVIEW_BUDGET);
        }

        @Test
        void honoursTheReviewBudgetSeparatelyFromTheNewBudget() {
            MasteryLedger ledger = ledgerOf(
                    seenOn(cellOf("case=gen;number=sg").id(), "2024-09-20"),
                    seenOn(cellOf("case=dat;number=sg").id(), "2024-09-20"),
                    seenOn(cellOf("case=acc;number=sg").id(), "2024-09-20"));
            Queue queue = assemble(
                    policy(patch().newPerDay(1)
                            .reviewsPerDay(2)
                            .perLexemeCap(10)
                            .rowSpacing(0)),
                    ledger);
            assertThat(queue.reviewCount()).isEqualTo(2);
            assertThat(queue.newCount()).isEqualTo(1);
            assertThat(queue.excludedFor(ExclusionReason.REVIEW_BUDGET)).hasSize(1);
        }
    }

    @Nested
    class CapsAndSpacing {

        @Test
        void stopsTakingCellsOfOneLexemeAtTheCap() {
            Queue queue = assemble(policy(patch().newPerDay(30).perLexemeCap(2).rowSpacing(0)), empty());
            Map<String, Integer> perLexeme = new LinkedHashMap<>();
            for (Cell cell : queue.cells()) {
                perLexeme.merge(cell.lexemeId(), 1, Integer::sum);
            }
            assertThat(perLexeme.values().stream().allMatch(count -> count <= 2))
                    .isTrue();
            assertThat(queue.excludedFor(ExclusionReason.LEXEME_CAP).size()).isGreaterThan(0);
        }

        @Test
        void keepsTwoCellsOfOneRowApart() {
            Queue queue = assemble(policy(patch().newPerDay(30).perLexemeCap(10).rowSpacing(2)), empty());
            List<String> rows = queue.cells().stream()
                    .map(cell -> cell.lexemeId() + "|"
                            + Text.split(cell.bundle(), ";").get(0))
                    .toList();
            for (int index = 1; index < rows.size(); index += 1) {
                assertThat(rows.get(index)).isNotEqualTo(rows.get(index - 1));
            }
        }

        @Test
        void takesTheRowDimensionItIsToldToUse() {
            Queue queue = Queue.assemble(
                    DRILL_CONTEXT,
                    MEMBERSHIP,
                    policy(patch().newPerDay(30).perLexemeCap(10).rowSpacing(3)),
                    empty(),
                    TERM,
                    QueueOptions.DEFAULT.withRowAcross("case"));
            assertThat(queue.excludedFor(ExclusionReason.ROW_SPACING).size()).isGreaterThan(0);
        }
    }

    @Nested
    class Exclusions {

        @Test
        void leavesOutASuspendedCellAndSaysSo() {
            Cell target = cellOf("case=gen;number=sg");
            MasteryLedger ledger = ledgerOf(
                    CellStrength.fresh(target.id()).recordLapse(FLAT, TERM).suspend());
            Queue queue = assemble(policy(patch().newPerDay(30).perLexemeCap(10).rowSpacing(0)), ledger);
            assertThat(queue.whyExcluded(target.id())).contains(ExclusionReason.SUSPENDED);
            assertThat(queue.cells()).doesNotContain(target);
        }

        @Test
        void leavesOutABuriedCellAndSaysSo() {
            Cell target = cellOf("case=gen;number=sg");
            BurialSet buried = BurialSet.NONE.bury(DRILL_CONTEXT, target, TERM);
            Queue queue = Queue.assemble(
                    DRILL_CONTEXT,
                    MEMBERSHIP,
                    policy(patch().newPerDay(30).perLexemeCap(10).rowSpacing(0)),
                    empty(),
                    TERM,
                    QueueOptions.DEFAULT.withBuried(buried));
            assertThat(queue.whyExcluded(target.id())).contains(ExclusionReason.BURIED);
            assertThat(queue.excludedFor(ExclusionReason.BURIED)).hasSize(3);
        }

        @Test
        void emptiesTheWholeQueueOnARestDayAndSaysWhy() {
            Queue queue = assemble(policy(patch().restDays(List.of(2))), empty());
            assertThat(queue.length()).isEqualTo(0);
            assertThat(queue.excludedFor(ExclusionReason.REST_DAY)).hasSize(30);
        }

        @Test
        void marksACellThatIsNeitherDueNorNewAsNotDue() {
            Cell target = cellOf("case=gen;number=sg");
            MasteryLedger ledger = ledgerOf(seenOn(target.id(), "2024-10-01"));
            Queue queue = assemble(policy(patch().newPerDay(0).perLexemeCap(10).rowSpacing(0)), ledger);
            assertThat(queue.whyExcluded(target.id())).contains(ExclusionReason.NOT_DUE);
        }

        @Test
        void accountsForEveryCellOfTheMembership() {
            Queue queue = assemble(policy(patch().newPerDay(3).perLexemeCap(10).rowSpacing(0)), empty());
            assertThat(queue.length() + queue.excluded().size())
                    .isEqualTo(MEMBERSHIP.cells().size());
        }
    }

    @Nested
    class QueueMetadata {

        @Test
        void carriesTheDayAndTheDeckItWasBuiltFor() {
            Queue queue = assemble(DrillPolicy.of(DrillPolicyPatch.EMPTY), empty());
            assertThat(queue.on()).isEqualTo(d("2024-10-01"));
            assertThat(queue.deckKey()).isEqualTo("all");
        }

        @Test
        void isEmptyWhenTheMembershipIs() {
            Membership nothing = Membership.resolve(
                    DRILL_CONTEXT,
                    Deck.of(
                            LATIN_NOUN,
                            DeckInput.builder("none")
                                    .scope(DeckScopeInput.builder()
                                            .cells("case=nom,case!=nom")
                                            .build())
                                    .build()),
                    TERM);
            Queue queue = Queue.assemble(DRILL_CONTEXT, nothing, DrillPolicy.of(DrillPolicyPatch.EMPTY), empty(), TERM);
            assertThat(queue.length()).isEqualTo(0);
            assertThat(queue.excluded()).isEmpty();
        }
    }
}
