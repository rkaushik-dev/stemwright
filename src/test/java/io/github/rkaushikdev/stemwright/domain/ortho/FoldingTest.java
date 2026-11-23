package io.github.rkaushikdev.stemwright.domain.ortho;

import static io.github.rkaushikdev.stemwright.domain.ortho.FoldStep.CONSONANTAL_LETTERS;
import static io.github.rkaushikdev.stemwright.domain.ortho.FoldStep.FINAL_FORMS;
import static io.github.rkaushikdev.stemwright.domain.ortho.FoldStep.LENGTH_MARKS;
import static io.github.rkaushikdev.stemwright.domain.ortho.FoldStep.LIGATURES;
import static io.github.rkaushikdev.stemwright.domain.ortho.FoldStep.LOWERCASE;
import static io.github.rkaushikdev.stemwright.domain.ortho.FoldStep.MARKS;
import static io.github.rkaushikdev.stemwright.domain.ortho.FoldStep.PUNCTUATION;
import static io.github.rkaushikdev.stemwright.domain.ortho.FoldStep.SPACES;
import static io.github.rkaushikdev.stemwright.domain.ortho.Folding.applyFolds;
import static io.github.rkaushikdev.stemwright.domain.ortho.Folding.describeFolds;
import static io.github.rkaushikdev.stemwright.domain.ortho.Folding.foldedEqual;
import static io.github.rkaushikdev.stemwright.domain.ortho.Folding.foldsThatMattered;
import static io.github.rkaushikdev.stemwright.domain.ortho.Folding.normaliseFoldList;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.stemwright.domain.errors.ValidationException;
import io.github.rkaushikdev.stemwright.domain.text.Normalise;
import java.text.Normalizer.Form;
import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class FoldingTest {

    private static String cp(int point) {
        return Character.toString(point);
    }

    private static final String MACRON = cp(0x0304);
    private static final String ACUTE = cp(0x0301);

    @Nested
    class FoldSteps {

        @Test
        void declaresEveryKeyThatTheOrderNames() {
            for (FoldStep step : Folding.ORDER) {
                assertThat(FoldStep.fromKey(step.key())).contains(step);
            }
            assertThat(Folding.ORDER).hasSize(FoldStep.values().length);
        }

        @Test
        void recognisesItsOwnKeysAndNothingElse() {
            assertThat(FoldStep.fromKey("marks")).isPresent();
            assertThat(FoldStep.fromKey("vowels")).isEmpty();
            assertThatThrownBy(() -> FoldStep.require("vowels")).isInstanceOf(ValidationException.class);
        }
    }

    @Nested
    class IndividualSteps {

        @Test
        void lowercases() {
            assertThat(applyFolds("Puella", List.of(LOWERCASE))).isEqualTo("puella");
        }

        @Test
        void stripsAccentsAndBreathings() {
            assertThat(applyFolds("a" + ACUTE + "nthropos", List.of(MARKS))).isEqualTo("anthropos");
            assertThat(applyFolds("ἄνθρωπος", List.of(MARKS))).isEqualTo("ανθρωπος");
        }

        @Test
        void acceptsAMedialSigmaAtTheEndOfAWord() {
            assertThat(applyFolds("λογος", List.of(FINAL_FORMS))).isEqualTo(applyFolds("λογοσ", List.of(FINAL_FORMS)));
        }

        @Test
        void foldsTheHebrewFinalLettersToo() {
            assertThat(applyFolds("מלך", List.of(FINAL_FORMS))).isEqualTo("מלכ");
        }

        @Test
        void spellsOutTheLatinLigatures() {
            assertThat(applyFolds("pœnæ", List.of(LIGATURES))).isEqualTo("poenae");
            assertThat(applyFolds("Æneas", List.of(LIGATURES))).isEqualTo("AEneas");
            assertThat(foldedEqual("cælum", "caelum", List.of(LIGATURES))).isTrue();
            assertThat(foldedEqual("cælum", "celum", List.of(LIGATURES))).isFalse();
        }

        @Test
        void readsConsonantalVAndJAsTheirVowels() {
            assertThat(applyFolds("virum", List.of(CONSONANTAL_LETTERS))).isEqualTo("uirum");
            assertThat(applyFolds("Julius", List.of(CONSONANTAL_LETTERS))).isEqualTo("Iulius");
            assertThat(foldedEqual("jam", "iam", List.of(CONSONANTAL_LETTERS))).isTrue();
            assertThat(foldedEqual("vir", "uir", List.of(LIGATURES))).isFalse();
        }

        @Test
        void keepsAMarkOnTheLetterThatReplacedTheOneItSatOn() {
            String marked = Normalise.toForm("v" + MACRON + "s", Form.NFC);
            assertThat(applyFolds(marked, List.of(CONSONANTAL_LETTERS))).isEqualTo("ūs");
            assertThat(applyFolds(marked, List.of(CONSONANTAL_LETTERS, LENGTH_MARKS)))
                    .isEqualTo("us");
        }

        @Test
        void foldsTheLettersAfterCapitalsWhicheverOrderTheyWereListedIn() {
            assertThat(applyFolds("VIRVM", List.of(CONSONANTAL_LETTERS, LOWERCASE)))
                    .isEqualTo("uirum");
            assertThat(describeFolds(List.of(CONSONANTAL_LETTERS, LIGATURES, LOWERCASE)))
                    .containsExactly(
                            "ignore capitals", "accept ae and oe for a ligature", "accept u for v and i for j");
        }

        @Test
        void dropsMacronsButKeepsAccents() {
            assertThat(applyFolds("a" + MACRON + "mo", List.of(LENGTH_MARKS))).isEqualTo("amo");
            assertThat(applyFolds("a" + ACUTE + "mo", List.of(LENGTH_MARKS)))
                    .isEqualTo(Normalise.toForm("a" + ACUTE + "mo", Form.NFC));
        }

        @Test
        void dropsPunctuation() {
            assertThat(applyFolds("quo usque, tandem?", List.of(PUNCTUATION))).isEqualTo("quo usque tandem");
        }

        @Test
        void dropsSpacingEntirely() {
            assertThat(applyFolds("in urbe", List.of(SPACES))).isEqualTo("inurbe");
        }
    }

    @Nested
    class ApplyFolds {

        @Test
        void changesNothingWhenNoStepIsAskedFor() {
            assertThat(applyFolds("  Puella  ", List.of())).isEqualTo("Puella");
        }

        @Test
        void isIdempotent() {
            List<FoldStep> steps = List.of(LOWERCASE, MARKS, PUNCTUATION, FINAL_FORMS);
            String once = applyFolds("Ἄνθρωπος,", steps);
            assertThat(applyFolds(once, steps)).isEqualTo(once);
        }

        @Test
        void runsInTheDeclaredOrderWhateverOrderItWasGiven() {
            String forward = applyFolds("Ἄνθρωπος", List.of(LOWERCASE, MARKS));
            String backward = applyFolds("Ἄνθρωπος", List.of(MARKS, LOWERCASE));
            assertThat(forward).isEqualTo(backward);
            assertThat(forward).isEqualTo("ανθρωπος");
        }

        @Test
        void ignoresAStepListedTwice() {
            assertThat(applyFolds("Puella", List.of(LOWERCASE, LOWERCASE))).isEqualTo("puella");
        }
    }

    @Nested
    class NormaliseFoldList {

        @Test
        void sortsIntoTheDeclaredOrderAndDropsDuplicates() {
            assertThat(normaliseFoldList(List.of("marks", "lowercase", "marks")))
                    .containsExactly(LOWERCASE, MARKS);
        }

        @Test
        void refusesAStepNobodyDeclared() {
            assertThatThrownBy(() -> normaliseFoldList(List.of("marks", "vowels")))
                    .hasMessageContaining("is not a fold step: vowels");
        }

        @Test
        void acceptsAnEmptyList() {
            assertThat(normaliseFoldList(List.of())).isEmpty();
        }
    }

    @Nested
    class DescribeFolds {

        @Test
        void readsTheStepsOutInOrder() {
            assertThat(describeFolds(List.of(MARKS, LOWERCASE)))
                    .containsExactly("ignore capitals", "ignore accents and breathings");
        }

        @Test
        void saysNothingForAnEmptyList() {
            assertThat(describeFolds(List.of())).isEmpty();
        }
    }

    @Nested
    class FoldedEqual {

        @Test
        void isExactWhenNothingIsFolded() {
            assertThat(foldedEqual("puella", "Puella", List.of())).isFalse();
            assertThat(foldedEqual("puella", "puella", List.of())).isTrue();
        }

        @Test
        void letsAStepBringTwoSpellingsTogether() {
            assertThat(foldedEqual("puella", "Puella", List.of(LOWERCASE))).isTrue();
        }

        @Test
        void staysFalseWhenTheDifferenceIsNotOneOfTheSteps() {
            assertThat(foldedEqual("puella", "puellae", List.of(LOWERCASE, MARKS)))
                    .isFalse();
        }
    }

    @Nested
    class FoldsThatMattered {

        @Test
        void namesTheOneStepThatMadeTheAnswerPass() {
            assertThat(foldsThatMattered("puella", "Puella", List.of(LOWERCASE, MARKS)))
                    .containsExactly(LOWERCASE);
        }

        @Test
        void namesNothingWhenTheAnswerWasExactAnyway() {
            assertThat(foldsThatMattered("puella", "puella", List.of(LOWERCASE, MARKS)))
                    .isEmpty();
        }

        @Test
        void namesNothingWhenTheAnswerIsWrongHoweverItIsFolded() {
            assertThat(foldsThatMattered("puella", "puellae", List.of(LOWERCASE, MARKS)))
                    .isEmpty();
        }

        @Test
        void namesBothWhenTwoStepsWereEachNeeded() {
            List<FoldStep> mattered =
                    foldsThatMattered("anthropos", "A" + ACUTE + "nthropos", List.of(LOWERCASE, MARKS));
            assertThat(mattered).containsExactly(LOWERCASE, MARKS);
        }
    }
}
