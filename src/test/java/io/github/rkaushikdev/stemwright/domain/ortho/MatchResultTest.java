package io.github.rkaushikdev.stemwright.domain.ortho;

import static io.github.rkaushikdev.stemwright.domain.ortho.MatchResult.compareAgainstAny;
import static io.github.rkaushikdev.stemwright.domain.ortho.MatchResult.compareForm;
import static io.github.rkaushikdev.stemwright.support.Ortho.GREEK;
import static io.github.rkaushikdev.stemwright.support.Ortho.LATIN;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class MatchResultTest {

    private static final ToleranceLevel EXACT = GREEK.requireLevel("exact");
    private static final ToleranceLevel STANDARD = GREEK.requireLevel("standard");
    private static final ToleranceLevel LENIENT = GREEK.requireLevel("lenient");
    private static final ToleranceLevel LATIN_STANDARD = LATIN.requireLevel("standard");

    @Nested
    class CompareForm {

        @Test
        void callsAByteIdenticalAnswerExact() {
            MatchResult result = compareForm("puella", "puella", EXACT);
            assertThat(result.verdict()).isEqualTo(MatchVerdict.EXACT);
            assertThat(result.distance()).isZero();
            assertThat(result.foldsUsed()).isEmpty();
            assertThat(result.isCorrect()).isTrue();
        }

        @Test
        void trimsAndNormalisesBeforeComparing() {
            assertThat(compareForm("puella", "  puella  ", EXACT).verdict()).isEqualTo(MatchVerdict.EXACT);
        }

        @Test
        void callsADifferenceTheLevelFoldsTolerated() {
            MatchResult result = compareForm("Puella", "puella", LATIN_STANDARD);
            assertThat(result.verdict()).isEqualTo(MatchVerdict.TOLERATED);
            assertThat(result.foldsUsed()).containsExactly(FoldStep.LOWERCASE);
            assertThat(result.isCorrect()).isTrue();
        }

        @Test
        void keepsTheSameDifferenceWrongAtAStricterLevel() {
            MatchResult result = compareForm("Puella", "puella", EXACT);
            assertThat(result.verdict()).isEqualTo(MatchVerdict.NEAR);
            assertThat(result.isCorrect()).isFalse();
        }

        @Test
        void callsAnAnswerOneClusterAwayANearMiss() {
            MatchResult result = compareForm("puella", "puellu", EXACT);
            assertThat(result.verdict()).isEqualTo(MatchVerdict.NEAR);
            assertThat(result.distance()).isEqualTo(1);
        }

        @Test
        void callsAnAdjacentSwapANearMissHoweverLongTheWord() {
            assertThat(compareForm("bellorum", "bellourm", EXACT).verdict()).isEqualTo(MatchVerdict.NEAR);
        }

        @Test
        void callsAnythingFurtherWrong() {
            MatchResult result = compareForm("puella", "bellorum", EXACT);
            assertThat(result.verdict()).isEqualTo(MatchVerdict.WRONG);
            assertThat(result.distance()).isGreaterThan(1);
        }

        @Test
        void reportsSimilarityForAWrongAnswer() {
            MatchResult near = compareForm("puellarum", "puellorum", EXACT);
            MatchResult far = compareForm("puellarum", "xyz", EXACT);
            assertThat(near.similarity()).isGreaterThan(far.similarity());
        }

        @Test
        void measuresDistanceAfterFoldingNotBefore() {
            MatchResult result = compareForm("ἄνθρωπος", "ανθρωποσ", LENIENT);
            assertThat(result.verdict()).isEqualTo(MatchVerdict.TOLERATED);
            assertThat(result.distance()).isZero();
        }
    }

    @Nested
    class ClassifyMiss {

        @Test
        void namesACapital() {
            assertThat(MissKind.classify("Puella", "puella")).isEqualTo(MissKind.CAPITAL);
        }

        @Test
        void namesAnAccent() {
            assertThat(MissKind.classify("ἄνθρωπος", "ανθρωπος")).isEqualTo(MissKind.ACCENT);
        }

        @Test
        void namesAMissingLongMarkAsLengthRatherThanAnAccent() {
            assertThat(MissKind.classify("puellā", "puella")).isEqualTo(MissKind.LENGTH);
            assertThat(MissKind.classify("rosă", "rosa")).isEqualTo(MissKind.LENGTH);
            assertThat(MissKind.classify("rosa", "rosā")).isEqualTo(MissKind.LENGTH);
        }

        @Test
        void keepsAnAccentAnAccentWhenALongMarkIsMissingBesideIt() {
            assertThat(MissKind.classify("ᾱ̓́νθρωπος", "ανθρωπος")).isEqualTo(MissKind.ACCENT);
            assertThat(MissKind.classify("ᾱ", "α")).isEqualTo(MissKind.LENGTH);
        }

        @Test
        void asksAboutCapitalsBeforeLength() {
            assertThat(MissKind.classify("Rōma", "rōma")).isEqualTo(MissKind.CAPITAL);
        }

        @Test
        void readsEveryKindBackFromItsKey() {
            for (MissKind kind : MissKind.values()) {
                assertThat(MissKind.fromKey(kind.key())).contains(kind);
            }
            assertThat(MissKind.fromKey("macron")).isEmpty();
        }

        @Test
        void namesSpacing() {
            assertThat(MissKind.classify("in urbe", "inurbe")).isEqualTo(MissKind.SPACING);
        }

        @Test
        void namesAnOrderSwap() {
            assertThat(MissKind.classify("puella", "peulla")).isEqualTo(MissKind.ORDER);
        }

        @Test
        void namesAnEndingWhenTheOpeningIsShared() {
            assertThat(MissKind.classify("puellarum", "puellorum")).isEqualTo(MissKind.ENDING);
            assertThat(MissKind.classify("puella", "puellae")).isEqualTo(MissKind.ENDING);
        }

        @Test
        void namesTheStemWhenTheOpeningIsNotShared() {
            assertThat(MissKind.classify("puellarum", "bellorum")).isEqualTo(MissKind.STEM);
        }

        @Test
        void fallsBackToOtherForIdenticalForms() {
            assertThat(MissKind.classify("puella", "puella")).isEqualTo(MissKind.OTHER);
        }
    }

    @Nested
    class CompareAgainstAny {

        @Test
        void takesTheBestVerdictAcrossTheAcceptableForms() {
            MatchResult result = compareAgainstAny(List.of("bellorum", "puella"), "puella", EXACT);
            assertThat(result.verdict()).isEqualTo(MatchVerdict.EXACT);
            assertThat(result.expected()).isEqualTo("puella");
        }

        @Test
        void prefersExactOverTolerated() {
            MatchResult result = compareAgainstAny(List.of("Puella", "puella"), "puella", LATIN_STANDARD);
            assertThat(result.verdict()).isEqualTo(MatchVerdict.EXACT);
        }

        @Test
        void prefersTheCloserOfTwoEquallyWrongForms() {
            MatchResult result = compareAgainstAny(List.of("xyzzy", "puellu"), "puella", EXACT);
            assertThat(result.expected()).isEqualTo("puellu");
        }

        @Test
        void marksWrongWhenNothingMatches() {
            assertThat(compareAgainstAny(List.of("bellorum", "agrorum"), "puella", EXACT)
                            .verdict())
                    .isEqualTo(MatchVerdict.WRONG);
        }

        @Test
        void treatsAnEmptyListAsAnAnswerAgainstNothing() {
            assertThat(compareAgainstAny(List.of(), "puella", EXACT).verdict()).isEqualTo(MatchVerdict.WRONG);
        }
    }

    @Nested
    class TransliteratedAnswers {

        private static final TransliterationTable BETA = TransliterationTable.GREEK_BETA_CODE;

        @Test
        void readsAnAnswerTypedOnALatinKeyboardThroughTheTable() {
            MatchResult result = compareForm("ἄνθρωπος", "anthrwpos", LENIENT, BETA);
            assertThat(result.verdict()).isEqualTo(MatchVerdict.TOLERATED);
            assertThat(result.given()).isEqualTo("ανθρωποσ");
            assertThat(result.foldsUsed()).containsExactly(FoldStep.FINAL_FORMS, FoldStep.MARKS);
        }

        @Test
        void stillMarksTheAccentsTheLevelDoesNotForgive() {
            MatchResult result = compareForm("ἄνθρωπος", "anthrwpos", EXACT, BETA);
            assertThat(result.verdict()).isEqualTo(MatchVerdict.NEAR);
            assertThat(result.distance()).isEqualTo(1);
            assertThat(compareForm("λογος", "logos", EXACT, BETA).verdict()).isEqualTo(MatchVerdict.TOLERATED);
        }

        @Test
        void keepsTheTypedMarkWhenTheAnswerIsAlreadyRightOrCannotBeRead() {
            MatchResult typed = compareForm("λόγος", "λόγος", EXACT, BETA);
            assertThat(typed.verdict()).isEqualTo(MatchVerdict.EXACT);
            assertThat(typed.given()).isEqualTo("λόγος");
            assertThat(compareForm("λόγος", "logos1", EXACT, BETA).given()).isEqualTo("logos1");
        }

        @Test
        void changesNothingUnderATableWithNoRules() {
            assertThat(compareForm("ἄνθρωπος", "anthrwpos", LENIENT, TransliterationTable.NONE))
                    .isEqualTo(compareForm("ἄνθρωπος", "anthrwpos", LENIENT));
            assertThat(compareForm("ἄνθρωπος", "anthrwpos", LENIENT).verdict()).isEqualTo(MatchVerdict.WRONG);
        }
    }

    @Nested
    class Describe {

        @Test
        void saysRightForAnExactAnswer() {
            assertThat(compareForm("puella", "puella", EXACT).describe()).isEqualTo("right");
        }

        @Test
        void namesWhatTheToleranceLetThrough() {
            assertThat(compareForm("Puella", "puella", LATIN_STANDARD).describe())
                    .isEqualTo("right, and the capital is not marked at this tolerance");
        }

        @Test
        void callsAMissingMacronTheVowelLength() {
            assertThat(compareForm("puellā", "puella", LATIN_STANDARD).describe())
                    .isEqualTo("right, and the vowel length is not marked at this tolerance");
            assertThat(compareForm("puellā", "puella", EXACT).describe())
                    .isEqualTo("not quite, the vowel length is off by 1");
        }

        @Test
        void namesTheMissAndTheDistanceForANearAnswer() {
            assertThat(compareForm("puella", "puellu", EXACT).describe())
                    .isEqualTo("not quite, the ending is off by 1");
        }

        @Test
        void givesTheExpectedFormForAWrongAnswer() {
            assertThat(compareForm("puella", "bellorum", EXACT).describe())
                    .isEqualTo("wrong, the expected form was puella");
        }
    }

    @Nested
    class TheStandardLevel {

        @Test
        void foldsAFinalSigmaButNotAnAccent() {
            assertThat(compareForm("λόγος", "λόγοσ", STANDARD).verdict()).isEqualTo(MatchVerdict.TOLERATED);
            assertThat(compareForm("λόγος", "λογος", STANDARD).verdict()).isEqualTo(MatchVerdict.NEAR);
        }
    }
}
