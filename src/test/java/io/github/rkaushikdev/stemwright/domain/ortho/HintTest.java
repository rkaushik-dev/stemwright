package io.github.rkaushikdev.stemwright.domain.ortho;

import static io.github.rkaushikdev.stemwright.domain.ortho.Hint.openingOf;
import static io.github.rkaushikdev.stemwright.domain.ortho.Hint.shapeOf;
import static io.github.rkaushikdev.stemwright.support.Ortho.LATIN;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.stemwright.domain.errors.ValidationException;
import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class HintTest {

    private static final ToleranceLevel EXACT = LATIN.requireLevel("exact");
    private static final MatchResult WRONG_ENDING = MatchResult.compareForm("puellarum", "puellorum", EXACT);
    private static final MatchResult WRONG_WORD = MatchResult.compareForm("puellarum", "bellum", EXACT);
    private static final MatchResult RIGHT = MatchResult.compareForm("puellarum", "puellarum", EXACT);

    @Nested
    class TheLevels {

        @Test
        void namesFourLevelsInOrder() {
            assertThat(HintLevel.values())
                    .extracting(HintLevel::key)
                    .containsExactly("shape", "kind", "opening", "reveal");
        }

        @Test
        void recognisesItsOwnLevels() {
            assertThat(HintLevel.fromKey("opening")).isPresent();
            assertThat(HintLevel.fromKey("answer")).isEmpty();
        }

        @Test
        void stepsForwardAndStopsAtTheLastOne() {
            assertThat(HintLevel.SHAPE.next()).isEqualTo(HintLevel.KIND);
            assertThat(HintLevel.REVEAL.next()).isEqualTo(HintLevel.REVEAL);
        }
    }

    @Nested
    class ShapeOf {

        @Test
        void givesOneMarkerPerCluster() {
            assertThat(shapeOf("puella")).isEqualTo("......");
        }

        @Test
        void countsAnAccentedVowelAsOne() {
            assertThat(shapeOf("ἄνθρωπος")).isEqualTo("........");
        }

        @Test
        void takesTheMarkerItIsGiven() {
            assertThat(shapeOf("abc", "_")).isEqualTo("___");
        }
    }

    @Nested
    class OpeningOf {

        @Test
        void revealsTheSharedOpeningAndHidesTheRest() {
            assertThat(openingOf("puellarum", "puellorum")).isEqualTo("puell....");
        }

        @Test
        void neverRevealsTheWholeForm() {
            assertThat(openingOf("puella", "puella")).isEqualTo("puell.");
        }

        @Test
        void revealsTheFirstLetterWhenNothingIsShared() {
            assertThat(openingOf("puella", "bellum")).isEqualTo("p.....");
        }

        @Test
        void revealsTheFirstLetterForAnEmptyAnswer() {
            assertThat(openingOf("puella", "")).isEqualTo("p.....");
        }
    }

    @Nested
    class Of {

        @Test
        void givesTheLengthAtTheShapeLevel() {
            Hint hint = Hint.of(WRONG_ENDING, HintLevel.SHAPE);
            assertThat(hint.text()).isEqualTo("9 letters: .........");
            assertThat(hint.revealed()).isZero();
        }

        @Test
        void givesTheSharedOpeningAtTheOpeningLevel() {
            Hint hint = Hint.of(WRONG_ENDING, HintLevel.OPENING);
            assertThat(hint.text()).isEqualTo("puell....");
            assertThat(hint.revealed()).isEqualTo(5);
        }

        @Test
        void namesTheKindOfMissAndWhereItStarts() {
            assertThat(Hint.of(WRONG_ENDING, HintLevel.KIND).text()).isEqualTo("the ending is wrong, from letter 6");
        }

        @Test
        void namesTheStemWhenTheWholeWordIsWrong() {
            assertThat(Hint.of(WRONG_WORD, HintLevel.KIND).text()).contains("stem");
        }

        @Test
        void pointsAtTheVowelWhoseLongMarkWasLeftOut() {
            MatchResult unmarked = MatchResult.compareForm("puellās", "puellas", EXACT);
            assertThat(Hint.of(unmarked, HintLevel.KIND).text()).isEqualTo("the vowel length is wrong, from letter 6");
        }

        @Test
        void givesTheFormAtTheRevealLevel() {
            Hint hint = Hint.of(WRONG_ENDING, HintLevel.REVEAL);
            assertThat(hint.text()).isEqualTo("puellarum");
            assertThat(hint.revealsAnswer("puellarum")).isTrue();
        }

        @Test
        void refusesToHintAtAnAnswerThatWasAlreadyRight() {
            assertThatThrownBy(() -> Hint.of(RIGHT, HintLevel.SHAPE))
                    .isInstanceOf(ValidationException.class)
                    .hasMessageContaining("already right");
        }
    }

    @Nested
    class Ladder {

        @Test
        void givesOneHintPerLevelInOrder() {
            assertThat(Hint.ladder(WRONG_ENDING)).extracting(Hint::level).containsExactly(HintLevel.values());
        }

        @Test
        void revealsMoreAtEachStep() {
            List<Hint> ladder = Hint.ladder(WRONG_ENDING);
            for (int index = 1; index < ladder.size(); index += 1) {
                assertThat(ladder.get(index).revealed())
                        .isGreaterThanOrEqualTo(ladder.get(index - 1).revealed());
            }
        }

        @Test
        void onlyRevealsTheAnswerAtTheLastStep() {
            List<Hint> revealing = Hint.ladder(WRONG_ENDING).stream()
                    .filter(hint -> hint.revealsAnswer("puellarum"))
                    .toList();
            assertThat(revealing).hasSize(1);
            assertThat(revealing.get(0).level()).isEqualTo(HintLevel.REVEAL);
        }
    }

    @Nested
    class Describe {

        @Test
        void namesTheLevel() {
            assertThat(Hint.of(WRONG_ENDING, HintLevel.SHAPE).describe()).isEqualTo("shape: 9 letters: .........");
        }
    }
}
