package io.github.rkaushikdev.stemwright.domain.paradigm;

import static io.github.rkaushikdev.stemwright.support.Profiles.FIRST_DECLENSION;
import static io.github.rkaushikdev.stemwright.support.Profiles.LATIN_NOUN;
import static io.github.rkaushikdev.stemwright.support.Profiles.SECOND_NEUTER;
import static io.github.rkaushikdev.stemwright.support.TestMaps.ordered;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.stemwright.domain.features.FeatureBundle;
import io.github.rkaushikdev.stemwright.domain.lexicon.Lexeme;
import io.github.rkaushikdev.stemwright.domain.lexicon.LexemeContext;
import io.github.rkaushikdev.stemwright.domain.lexicon.LexemeInput;
import io.github.rkaushikdev.stemwright.domain.lexicon.StemInput;
import io.github.rkaushikdev.stemwright.domain.lexicon.StemSet;
import io.github.rkaushikdev.stemwright.support.Classes;
import io.github.rkaushikdev.stemwright.support.Problems;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class RuleSetTest {

    private static final LexemeContext CONTEXT = new LexemeContext(LATIN_NOUN, Classes.LATIN);

    private static final Lexeme PUELLA = lexeme("puella", "first-declension", "puell");

    private static final Lexeme BELLUM = lexeme("bellum", "second-declension.neuter", "bell");

    private static final RuleSet LATIN_RULES = RuleSet.of(
            "latin",
            List.of(
                    JoinRuleInput.of("g-before-s", "g", "s", "x").withNote("reg plus si is rexi"),
                    JoinRuleInput.of("g-before-t", "g", "t", "ct"),
                    JoinRuleInput.of("double-s", "s", "s", "s")));

    private static final FeatureBundle GEN_SG = FeatureBundle.of(LATIN_NOUN, ordered("case", "gen", "number", "sg"));

    private static Lexeme lexeme(String lemma, String classKey, String base) {
        return Lexeme.of(CONTEXT, LexemeInput.of(lemma, classKey, StemSet.of(StemInput.of("base", base))));
    }

    private static ParadigmProfile profileOf(String key, String classKey, ParadigmSlotInput... slots) {
        return ParadigmProfile.of(LATIN_NOUN, ParadigmProfileInput.of(key, classKey, slots));
    }

    @Nested
    class Of {

        @Test
        void keepsTheRulesInOrder() {
            assertThat(LATIN_RULES.rules())
                    .extracting(JoinRule::key)
                    .containsExactly("g-before-s", "g-before-t", "double-s");
        }

        @Test
        void refusesARepeatedKey() {
            List<JoinRuleInput> inputs =
                    List.of(JoinRuleInput.of("a", "g", "s", "x"), JoinRuleInput.of("a", "g", "t", "ct"));
            assertThat(Problems.problemMessages(() -> RuleSet.of("latin", inputs)))
                    .containsExactly("rules.1 repeats the rule key a");
        }

        @Test
        void refusesARuleThatConstrainsNeitherSide() {
            List<JoinRuleInput> inputs = List.of(JoinRuleInput.of("a", "", "", "x"));
            assertThat(Problems.problemMessages(() -> RuleSet.of("latin", inputs)))
                    .containsExactly("rules.0 must constrain at least one side of the seam");
        }
    }

    @Nested
    class JoinAtSeam {

        @Test
        void concatenatesWhenNothingMatches() {
            assertThat(LATIN_RULES.joinAtSeam("puell", "ae")).isEqualTo(new JoinResult("puellae", List.of()));
        }

        @Test
        void rewritesTheSeamWhenARuleMatchesBothSides() {
            JoinResult joined = LATIN_RULES.joinAtSeam("reg", "si");
            assertThat(joined.form()).isEqualTo("rexi");
            assertThat(joined.steps()).containsExactly(new JoinStep("g-before-s", "regsi", "rexi"));
        }

        @Test
        void takesTheFirstMatchingRuleAndStops() {
            assertThat(LATIN_RULES.joinAtSeam("ag", "tus").form()).isEqualTo("actus");
        }

        @Test
        void doesNotCascadeASecondRuleOntoTheRewrittenForm() {
            RuleSet cascading = RuleSet.of(
                    "x",
                    List.of(JoinRuleInput.of("a-to-s", "a", "b", "s"), JoinRuleInput.of("double-s", "s", "s", "s")));
            assertThat(cascading.joinAtSeam("ma", "bs").form()).isEqualTo("mss");
        }

        @Test
        void matchesARuleThatConstrainsOnlyTheStemSide() {
            RuleSet stemOnly = RuleSet.of("x", List.of(JoinRuleInput.of("drop-n", "n", "", "")));
            assertThat(stemOnly.joinAtSeam("man", "us").form()).isEqualTo("maus");
        }

        @Test
        void joinsWithAnEmptyAffix() {
            assertThat(RuleSet.NONE.joinAtSeam("vis", "").form()).isEqualTo("vis");
        }
    }

    @Nested
    class Realise {

        @Test
        void buildsAFormFromTheStemAndTheEnding() {
            assertThat(RuleSet.NONE.formOf(PUELLA, FIRST_DECLENSION, GEN_SG)).isEqualTo("puellae");
            assertThat(RuleSet.NONE.formOf(BELLUM, SECOND_NEUTER, GEN_SG)).isEqualTo("belli");
        }

        @Test
        void reportsWhichStemItUsed() {
            assertThat(RuleSet.NONE.realise(PUELLA, FIRST_DECLENSION, GEN_SG).stemRole())
                    .isEqualTo("base");
        }

        @Test
        void fallsBackToTheBaseStemWhenTheSlotNamesOneTheLexemeLacks() {
            ParadigmProfile profile = profileOf(
                    "p",
                    "c",
                    ParadigmSlotInput.builder("gen.sg")
                            .stemRole("oblique")
                            .affix("is")
                            .build());
            RealisedCell realised = RuleSet.NONE.realise(PUELLA, profile, GEN_SG);
            assertThat(realised.stemRole()).isEqualTo("base");
            assertThat(realised.form()).isEqualTo("puellis");
        }

        @Test
        void refusesABundleTheProfileHasNoSlotFor() {
            FeatureBundle bundle = FeatureBundle.of(LATIN_NOUN, Map.of("case", "gen"));
            assertThatThrownBy(() -> RuleSet.NONE.realise(PUELLA, FIRST_DECLENSION, bundle))
                    .hasMessageContaining("has no slot");
        }
    }

    @Nested
    class LiteralSlots {

        private final Lexeme vis = lexeme("vis", "third-declension", "vir");

        private final ParadigmProfile profile = profileOf(
                "vis",
                "third-declension",
                ParadigmSlotInput.literal("nom.sg", "vis"),
                ParadigmSlotInput.of("gen.pl", "ium"));

        @Test
        void takesTheLiteralFormAndIgnoresTheStem() {
            FeatureBundle bundle = FeatureBundle.of(LATIN_NOUN, ordered("case", "nom", "number", "sg"));
            RealisedCell realised = RuleSet.NONE.realise(vis, profile, bundle);
            assertThat(realised.form()).isEqualTo("vis");
            assertThat(realised.fromLiteral()).isTrue();
            assertThat(realised.stem()).isEmpty();
        }

        @Test
        void stillBuildsTheOtherSlotsFromTheStem() {
            FeatureBundle bundle = FeatureBundle.of(LATIN_NOUN, ordered("case", "gen", "number", "pl"));
            assertThat(RuleSet.NONE.formOf(vis, profile, bundle)).isEqualTo("virium");
        }
    }

    @Nested
    class RealiseAllAndCollidingForms {

        @Test
        void realisesEverySlotInOrder() {
            assertThat(RuleSet.NONE.realiseAll(PUELLA, FIRST_DECLENSION))
                    .extracting(RealisedCell::form)
                    .containsExactly(
                            "puella",
                            "puellae",
                            "puellae",
                            "puellam",
                            "puella",
                            "puellae",
                            "puellarum",
                            "puellis",
                            "puellas",
                            "puellis");
        }

        @Test
        void groupsTheBundlesThatEndUpSpelledTheSame() {
            Map<String, List<String>> collisions = RuleSet.NONE.collidingForms(PUELLA, FIRST_DECLENSION);
            assertThat(collisions.get("puellae")).hasSize(3);
            assertThat(collisions.get("puellis")).hasSize(2);
            assertThat(collisions).doesNotContainKey("puellarum");
        }

        @Test
        void findsNoCollisionsWhenEveryEndingIsDistinct() {
            ParadigmProfile distinct =
                    profileOf("d", "c", ParadigmSlotInput.of("nom.sg", "a"), ParadigmSlotInput.of("gen.sg", "b"));
            assertThat(RuleSet.NONE.collidingForms(PUELLA, distinct)).isEmpty();
        }
    }

    @Nested
    class Variants {

        @Test
        void joinsEachVariantEndingToTheSameStemByTheSameRules() {
            Lexeme rex = lexeme("rex", "third-declension", "reg");
            ParadigmProfile profile = profileOf("p", "c", ParadigmSlotInput.of("gen.sg", "is", "sis"));
            RealisedCell realised = LATIN_RULES.realise(rex, profile, GEN_SG);
            assertThat(realised.form()).isEqualTo("regis");
            assertThat(realised.variants()).containsExactly("rexis");
            assertThat(realised.acceptedForms()).containsExactly("regis", "rexis");
        }

        @Test
        void dropsAVariantTheRulesTurnIntoTheMainForm() {
            Lexeme mas = lexeme("mas", "third-declension", "mas");
            ParadigmProfile profile = profileOf("p", "c", ParadigmSlotInput.of("gen.sg", "is", "sis"));
            RealisedCell realised = LATIN_RULES.realise(mas, profile, GEN_SG);
            assertThat(realised.form()).isEqualTo("masis");
            assertThat(realised.variants()).isEmpty();
        }

        @Test
        void givesALiteralSlotNoVariants() {
            ParadigmProfile profile = profileOf("p", "c", ParadigmSlotInput.literal("gen.sg", "vis"));
            assertThat(RuleSet.NONE.realise(PUELLA, profile, GEN_SG).acceptedForms())
                    .containsExactly("vis");
        }
    }

    @Nested
    class Explain {

        @Test
        void readsAPlainJoinOut() {
            RealisedCell realised = RuleSet.NONE.realise(PUELLA, FIRST_DECLENSION, GEN_SG);
            assertThat(realised.explain(LATIN_NOUN, GEN_SG)).isEqualTo("puell plus ae gives puellae in latin");
        }

        @Test
        void namesTheRuleThatFired() {
            Lexeme rex = lexeme("rex", "third-declension", "reg");
            ParadigmProfile profile = profileOf("p", "c", ParadigmSlotInput.of("gen.sg", "sis"));
            RealisedCell realised = LATIN_RULES.realise(rex, profile, GEN_SG);
            assertThat(realised.explain(LATIN_NOUN, GEN_SG))
                    .isEqualTo("reg plus sis gives rexis in latin after g-before-s");
        }

        @Test
        void saysSoForALiteralForm() {
            ParadigmProfile profile = profileOf("p", "c", ParadigmSlotInput.literal("gen.sg", "vis"));
            RealisedCell realised = RuleSet.NONE.realise(PUELLA, profile, GEN_SG);
            assertThat(realised.explain(LATIN_NOUN, GEN_SG)).isEqualTo("vis is stated outright by the profile");
        }

        @Test
        void namesTheVariantsASlotAlsoAccepts() {
            ParadigmProfile profile = profileOf("p", "c", ParadigmSlotInput.of("gen.sg", "ae", "ai"));
            RealisedCell realised = RuleSet.NONE.realise(PUELLA, profile, GEN_SG);
            assertThat(realised.explain(LATIN_NOUN, GEN_SG))
                    .isEqualTo("puell plus ae gives puellae in latin, and also puellai");
        }

        @Test
        void refusesToExplainACellAgainstTheWrongBundle() {
            RealisedCell realised = RuleSet.NONE.realise(PUELLA, FIRST_DECLENSION, GEN_SG);
            FeatureBundle other = FeatureBundle.of(LATIN_NOUN, ordered("case", "dat", "number", "sg"));
            assertThatThrownBy(() -> realised.explain(LATIN_NOUN, other)).hasMessageContaining("does not match");
        }
    }
}
