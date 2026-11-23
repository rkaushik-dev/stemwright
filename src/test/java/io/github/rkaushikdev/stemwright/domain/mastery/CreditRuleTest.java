package io.github.rkaushikdev.stemwright.domain.mastery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.stemwright.domain.errors.ValidationException;
import io.github.rkaushikdev.stemwright.domain.paradigm.Cell;
import io.github.rkaushikdev.stemwright.domain.time.CivilDates;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class CreditRuleTest {

    private static final LocalDate DAY = d("2025-03-03");
    private static final Ladder FLAT =
            Ladder.of(LadderInput.builder("flat", 2, 6, 14, 30).spread(0).build());

    private static LocalDate d(String value) {
        return CivilDates.parseCivilDate(value);
    }

    private static CellStrength known(String cellId, int times) {
        CellStrength strength = CellStrength.fresh(cellId);
        for (int step = 0; step < times; step += 1) {
            strength = strength.recordCorrect(FLAT, d("2025-01-01"));
        }
        return strength;
    }

    /** The strength with only its due date replaced, and every other field kept. */
    private static CellStrength dueOn(CellStrength strength, LocalDate due) {
        return new CellStrength(
                strength.cellId(),
                strength.rung(),
                Optional.of(due),
                strength.seen(),
                strength.correct(),
                strength.lapses(),
                strength.streak(),
                strength.lapseRun(),
                strength.lastSeenOn(),
                strength.suspended());
    }

    private static CreditRule.Builder rule() {
        return CreditRule.builder();
    }

    @Nested
    class Build {

        @Test
        void takesTheDefaultWhenNothingIsPatched() {
            assertThat(rule().build()).isEqualTo(CreditRule.DEFAULT);
        }

        @Test
        void patchesOnlyWhatItIsGiven() {
            assertThat(rule().share(0.25).build())
                    .isEqualTo(new CreditRule(0.25, CreditRule.DEFAULT.allowClimb(), CreditRule.DEFAULT.introduces()));
        }

        @Test
        void refusesAShareOutsideNoughtToOne() {
            assertThatThrownBy(() -> rule().share(1.5).build()).isInstanceOf(ValidationException.class);
            assertThatThrownBy(() -> rule().share(-0.1).build()).hasMessageContaining("between nought and one");
        }
    }

    @Nested
    class CreditFor {

        @Test
        void movesAKnownTwinOutByAShareOfTheInterval() {
            CellStrength twin = known("cell_b", 2);
            CreditRule.Credited credited = rule().share(0.5).build().creditFor(twin, FLAT, DAY);
            assertThat(credited.outcome().moved()).isTrue();
            assertThat(credited.outcome().climbed()).isTrue();
            assertThat(credited.strength().rung()).isEqualTo(2);
            assertThat(credited.strength().dueOn()).contains(d("2025-03-10"));
        }

        @Test
        void doesNotClimbWhenTheRuleForbidsIt() {
            CellStrength twin = known("cell_b", 2);
            CreditRule forbidding = rule().share(0.5).allowClimb(false).build();
            CreditRule.Credited credited = forbidding.creditFor(twin, FLAT, DAY);
            assertThat(credited.outcome().climbed()).isFalse();
            assertThat(credited.strength().rung()).isEqualTo(twin.rung());
        }

        @Test
        void leavesANewTwinAloneByDefault() {
            CreditRule.Credited credited = rule().build().creditFor(CellStrength.fresh("cell_b"), FLAT, DAY);
            assertThat(credited.outcome().moved()).isFalse();
            assertThat(credited.strength().dueOn()).isEmpty();
        }

        @Test
        void introducesANewTwinWhenTheRuleSaysTo() {
            CreditRule introducing = rule().introduces(true).share(0.5).build();
            CreditRule.Credited credited = introducing.creditFor(CellStrength.fresh("cell_b"), FLAT, DAY);
            assertThat(credited.outcome().moved()).isTrue();
            assertThat(credited.strength().dueOn()).contains(d("2025-03-04"));
            assertThat(credited.strength().rung()).isEqualTo(0);
        }

        @Test
        void leavesASuspendedTwinAloneWhateverTheRule() {
            CellStrength twin = known("cell_b", 2).suspend();
            assertThat(rule().share(1)
                            .build()
                            .creditFor(twin, FLAT, DAY)
                            .outcome()
                            .moved())
                    .isFalse();
        }

        @Test
        void doesNothingAtAllWhenTheShareIsNought() {
            CellStrength twin = known("cell_b", 2);
            assertThat(CreditRule.NONE.creditFor(twin, FLAT, DAY).outcome().moved())
                    .isFalse();
        }

        @Test
        void neverPullsADueDateForward() {
            CellStrength far = dueOn(known("cell_b", 1), d("2025-12-01"));
            CreditRule forbidding = rule().share(0.5).allowClimb(false).build();
            CreditRule.Credited credited = forbidding.creditFor(far, FLAT, DAY);
            assertThat(credited.strength().dueOn()).contains(d("2025-12-01"));
            assertThat(credited.outcome().moved()).isFalse();
        }

        @Test
        void stillClimbsATwinWhoseDueDateIsFurtherOut() {
            CellStrength far = dueOn(known("cell_b", 1), d("2025-12-01"));
            CreditRule.Credited credited = rule().share(0.5).build().creditFor(far, FLAT, DAY);
            assertThat(credited.strength().rung()).isEqualTo(far.rung() + 1);
            assertThat(credited.strength().dueOn()).contains(d("2025-12-01"));
            assertThat(credited.outcome().climbed()).isTrue();
        }

        @Test
        void neverSchedulesATwinSoonerThanTomorrow() {
            CellStrength twin = known("cell_b", 1);
            CreditRule tiny = rule().share(0.01).allowClimb(false).build();
            assertThat(tiny.creditFor(twin, FLAT, DAY).strength().dueOn()).contains(d("2025-03-04"));
        }
    }

    @Nested
    class Propagate {

        private final MasteryLedger ledger =
                MasteryLedger.of("lrn_one", List.of(known("cell_b", 2), known("cell_c", 1)));

        @Test
        void movesEveryTwinItIsHanded() {
            CreditReport report = rule().build().propagate(ledger, FLAT, List.of("cell_b", "cell_c"), DAY);
            assertThat(report.movedCount()).isEqualTo(2);
            assertThat(report.climbedCount()).isEqualTo(2);
            assertThat(report.ledger().strengthFor("cell_b").rung()).isEqualTo(2);
        }

        @Test
        void leavesTheOriginalLedgerAlone() {
            rule().build().propagate(ledger, FLAT, List.of("cell_b"), DAY);
            assertThat(ledger.strengthFor("cell_b").rung()).isEqualTo(1);
        }

        @Test
        void reportsAnOutcomeForEveryTwinMovedOrNot() {
            CreditReport report = rule().build().propagate(ledger, FLAT, List.of("cell_b", "cell_new"), DAY);
            assertThat(report.outcomes()).hasSize(2);
            assertThat(report.movedCount()).isEqualTo(1);
        }

        @Test
        void changesNothingForAnEmptyTwinList() {
            CreditReport report = rule().build().propagate(ledger, FLAT, List.of(), DAY);
            assertThat(report.ledger()).isEqualTo(ledger);
            assertThat(report.outcomes()).isEmpty();
        }

        @Test
        void changesNothingUnderTheNoCreditRule() {
            CreditReport report = CreditRule.NONE.propagate(ledger, FLAT, List.of("cell_b", "cell_c"), DAY);
            assertThat(report.movedCount()).isEqualTo(0);
        }
    }

    @Nested
    class Describe {

        private final Cell answered = Cell.of("lex_aaaaaa", "case=gen;number=sg", "first-declension");

        @Test
        void saysSoWhenNoCreditPasses() {
            assertThat(CreditRule.NONE.describe(answered, 0)).isEqualTo("no credit passes from case=gen;number=sg");
        }

        @Test
        void namesTheShareAndTheCount() {
            assertThat(rule().share(0.5).build().describe(answered, 2))
                    .isEqualTo("2 twins of case=gen;number=sg took 50 percent of the interval");
        }
    }
}
