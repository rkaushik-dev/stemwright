package io.github.rkaushikdev.stemwright.domain.drill;

import static io.github.rkaushikdev.stemwright.support.Problems.problemMessages;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class DrillPolicyTest {

    private static final DrillPolicy DEFAULT = DrillPolicy.DEFAULT;

    private static DrillPolicyPatch.Builder patch() {
        return DrillPolicyPatch.builder();
    }

    @Nested
    class Of {

        @Test
        void isTheDefaultWhenNothingIsPatched() {
            assertThat(DrillPolicy.of(DrillPolicyPatch.EMPTY)).isEqualTo(DEFAULT);
        }

        @Test
        void takesOnlyTheFieldsThePatchNames() {
            DrillPolicy patched = DrillPolicy.of(patch().newPerDay(4).build());
            assertThat(patched.newPerDay()).isEqualTo(4);
            assertThat(patched.reviewsPerDay()).isEqualTo(DEFAULT.reviewsPerDay());
            assertThat(patched.leechThreshold()).isEqualTo(DEFAULT.leechThreshold());
        }

        @Test
        void lowerCasesTheLadderKey() {
            assertThat(DrillPolicy.of(patch().ladderKey("Slow-Start").build()).ladderKey())
                    .isEqualTo("slow-start");
        }

        @Test
        void sortsAndDeduplicatesTheRestDays() {
            assertThat(DrillPolicy.of(patch().restDays(List.of(7, 6, 6)).build())
                            .restDays())
                    .containsExactly(6, 7);
        }
    }

    @Nested
    class ApplyPatch {

        @Test
        void layersAPatchOntoSomethingThatIsNotTheDefault() {
            DrillPolicy deckPolicy =
                    DrillPolicy.of(patch().newPerDay(5).buryTwins(false).build());
            DrillPolicy assignment =
                    deckPolicy.applyPatch(patch().reviewsPerDay(20).build());
            assertThat(assignment.newPerDay()).isEqualTo(5);
            assertThat(assignment.buryTwins()).isFalse();
            assertThat(assignment.reviewsPerDay()).isEqualTo(20);
        }

        @Test
        void allowsAFalsePatchToOverrideATrueBase() {
            assertThat(DEFAULT.applyPatch(patch().buryTwins(false).build()).buryTwins())
                    .isFalse();
        }

        @Test
        void allowsAZeroPatchToOverrideAPositiveBase() {
            assertThat(DEFAULT.applyPatch(patch().newPerDay(0).build()).newPerDay())
                    .isEqualTo(0);
        }

        @Test
        void leavesTheBaseUntouched() {
            DrillPolicy base = DrillPolicy.of(DrillPolicyPatch.EMPTY);
            base.applyPatch(patch().newPerDay(99).build());
            assertThat(base.newPerDay()).isEqualTo(DEFAULT.newPerDay());
        }
    }

    @Nested
    class Validation {

        @Test
        void refusesANegativeCount() {
            assertThat(problemMessages(
                            () -> DrillPolicy.of(patch().newPerDay(-1).build())))
                    .containsExactly("newPerDay must not be negative: -1");
        }

        /**
         * A {@code perLexemeCap} of two and a half cannot be written, because a count is an {@code
         * int} and the type refuses it; what is left of the rule is that a count the policy cannot
         * hold is reported against its own field, which is checked at the edge of the cap's
         * ceiling.
         */
        @Test
        void refusesACountThePolicyCannotHold() {
            assertThat(DrillPolicy.of(patch().perLexemeCap(200).build()).perLexemeCap())
                    .isEqualTo(200);
            assertThat(problemMessages(
                            () -> DrillPolicy.of(patch().perLexemeCap(201).build())))
                    .containsExactly("perLexemeCap must be at most 200: 201");
        }

        @Test
        void refusesALeechThresholdOfNoughtThatWouldCatchEveryCell() {
            assertThat(problemMessages(
                            () -> DrillPolicy.of(patch().leechThreshold(0).build())))
                    .containsExactly("leechThreshold must be at least 1: 0");
            assertThat(DrillPolicy.of(patch().leechThreshold(1).build()).leechThreshold())
                    .isEqualTo(1);
        }

        @Test
        void refusesACountOverTheCeiling() {
            assertThat(problemMessages(
                            () -> DrillPolicy.of(patch().reviewsPerDay(5000).build())))
                    .containsExactly("reviewsPerDay must be at most 2000: 5000");
        }

        @Test
        void refusesAWeekdayOutsideOneToSeven() {
            assertThat(problemMessages(
                            () -> DrillPolicy.of(patch().restDays(List.of(0)).build())))
                    .containsExactly("restDays is not an iso weekday: 0");
            assertThat(problemMessages(
                            () -> DrillPolicy.of(patch().restDays(List.of(8)).build())))
                    .containsExactly("restDays is not an iso weekday: 8");
        }

        @Test
        void refusesRestingEveryDay() {
            assertThat(problemMessages(() -> DrillPolicy.of(
                            patch().restDays(List.of(1, 2, 3, 4, 5, 6, 7)).build())))
                    .containsExactly("restDays would rest every day of the week");
        }

        @Test
        void refusesAPolicyThatAsksForNoDrillingAtAll() {
            assertThat(problemMessages(() ->
                            DrillPolicy.of(patch().newPerDay(0).reviewsPerDay(0).build())))
                    .containsExactly("newPerDay is zero and so is reviewsPerDay, which asks for no drilling at all");
        }

        @Test
        void validatesTheMergedResultRatherThanThePatch() {
            DrillPolicy zeroNew = DrillPolicy.of(patch().newPerDay(0).build());
            assertThat(problemMessages(
                            () -> zeroNew.applyPatch(patch().reviewsPerDay(0).build())))
                    .hasSize(1);
        }

        @Test
        void reportsSeveralProblemsAtOnce() {
            assertThat(problemMessages(() ->
                            DrillPolicy.of(patch().newPerDay(-1).rowSpacing(-2).build())))
                    .hasSize(2);
        }
    }

    @Nested
    class AllowsDay {

        @Test
        void allowsEveryDayWhenNothingRests() {
            DrillPolicy policy = DrillPolicy.of(DrillPolicyPatch.EMPTY);
            assertThat(IntStream.rangeClosed(1, 7).allMatch(policy::allowsDay)).isTrue();
        }

        @Test
        void refusesTheDaysItRestsOn() {
            DrillPolicy policy = DrillPolicy.of(patch().restDays(List.of(6, 7)).build());
            assertThat(policy.allowsDay(5)).isTrue();
            assertThat(policy.allowsDay(6)).isFalse();
        }
    }

    @Nested
    class DailyCeiling {

        @Test
        void addsTheTwoBudgets() {
            assertThat(DrillPolicy.of(patch().newPerDay(4).reviewsPerDay(20).build())
                            .dailyCeiling())
                    .isEqualTo(24);
        }
    }

    @Nested
    class Describe {

        @Test
        void readsTheWholePolicyOut() {
            DrillPolicy policy = DrillPolicy.of(
                    patch().newPerDay(5).reviewsPerDay(30).restDays(List.of(7)).build());
            assertThat(policy.describe())
                    .isEqualTo("up to 5 new and 30 review cells a day, at most 3 cells of one word in a session, "
                            + "2 questions between cells of the same row, twins buried after an answer, "
                            + "resting on weekdays 7");
        }

        @Test
        void leavesOutThePartsThatAreSwitchedOff() {
            DrillPolicy quiet =
                    DrillPolicy.of(patch().rowSpacing(0).buryTwins(false).build());
            assertThat(quiet.describe())
                    .isEqualTo("up to 10 new and 60 review cells a day, at most 3 cells of one word in a session");
        }
    }

    @Nested
    class IsAtLeastAsDemandingAs {

        @Test
        void isTrueWhenEveryBudgetIsAtLeastAsLarge() {
            DrillPolicy base = DrillPolicy.of(patch().newPerDay(5).build());
            DrillPolicy other = DrillPolicy.of(patch().newPerDay(9).build());
            assertThat(other.isAtLeastAsDemandingAs(base)).isTrue();
        }

        @Test
        void isFalseWhenAnyBudgetShrinks() {
            DrillPolicy base = DrillPolicy.of(patch().reviewsPerDay(40).build());
            DrillPolicy other = DrillPolicy.of(patch().reviewsPerDay(20).build());
            assertThat(other.isAtLeastAsDemandingAs(base)).isFalse();
        }

        @Test
        void countsANewRestDayAsLessDemanding() {
            DrillPolicy other = DrillPolicy.of(patch().restDays(List.of(7)).build());
            assertThat(other.isAtLeastAsDemandingAs(DrillPolicy.of(DrillPolicyPatch.EMPTY)))
                    .isFalse();
        }

        @Test
        void isTrueOfAPolicyAgainstItself() {
            assertThat(DEFAULT.isAtLeastAsDemandingAs(DEFAULT)).isTrue();
        }
    }
}
