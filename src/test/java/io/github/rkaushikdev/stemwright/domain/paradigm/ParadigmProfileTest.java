package io.github.rkaushikdev.stemwright.domain.paradigm;

import static io.github.rkaushikdev.stemwright.support.Profiles.FIRST_DECLENSION;
import static io.github.rkaushikdev.stemwright.support.Profiles.LATIN_NOUN;
import static io.github.rkaushikdev.stemwright.support.Profiles.SECOND_NEUTER;
import static io.github.rkaushikdev.stemwright.support.TestMaps.ordered;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.stemwright.domain.features.FeatureBundle;
import io.github.rkaushikdev.stemwright.support.Problems;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class ParadigmProfileTest {

    @Nested
    class ReadBundle {

        @Test
        void readsTheDottedNotation() {
            assertThat(ParadigmProfile.readBundle(LATIN_NOUN, "gen.pl", "x"))
                    .isEqualTo(FeatureBundle.of(LATIN_NOUN, ordered("case", "gen", "number", "pl")));
        }

        @Test
        void readsACanonicalKey() {
            assertThat(ParadigmProfile.readBundle(LATIN_NOUN, "case=gen;number=pl", "x"))
                    .isEqualTo(FeatureBundle.parseKey(LATIN_NOUN, "case=gen;number=pl"));
        }
    }

    @Nested
    class Of {

        @Test
        void keysEverySlotByItsCanonicalBundle() {
            assertThat(FIRST_DECLENSION.slotCount()).isEqualTo(10);
            assertThat(FIRST_DECLENSION.slotBundles()).first().isEqualTo("case=nom;number=sg");
        }

        @Test
        void defaultsTheStemRoleToBase() {
            assertThat(FIRST_DECLENSION.stemRolesUsed()).containsExactly("base");
        }

        @Test
        void defaultsTheLabelToTheKey() {
            assertThat(SECOND_NEUTER.label()).isEqualTo("second-neuter");
            assertThat(FIRST_DECLENSION.label()).isEqualTo("First declension");
        }

        @Test
        void takesTheLanguageFromTheSchema() {
            assertThat(FIRST_DECLENSION.language()).isEqualTo("latin");
        }

        @Test
        void refusesAnEmptySlotList() {
            assertThat(Problems.problemMessages(
                            () -> ParadigmProfile.of(LATIN_NOUN, ParadigmProfileInput.of("x", "y"))))
                    .containsExactly("slots must hold at least one slot");
        }

        @Test
        void refusesASlotThatLeavesARequiredDimensionOpen() {
            ParadigmProfileInput input = ParadigmProfileInput.of("x", "y", ParadigmSlotInput.of("gen", "ae"));
            assertThat(Problems.problemMessages(() -> ParadigmProfile.of(LATIN_NOUN, input)))
                    .containsExactly("slots.0 does not fix every required dimension: gen");
        }

        @Test
        void refusesTheSameBundleTwiceHoweverItWasSpelled() {
            ParadigmProfileInput input = ParadigmProfileInput.of(
                    "x", "y", ParadigmSlotInput.of("gen.sg", "ae"), ParadigmSlotInput.of("case=gen;number=sg", "ai"));
            assertThat(Problems.problemMessages(() -> ParadigmProfile.of(LATIN_NOUN, input)))
                    .containsExactly("slots.1 repeats the bundle case=gen;number=sg");
        }

        @Test
        void refusesASlotWithNeitherAnAffixNorALiteral() {
            ParadigmProfileInput input = ParadigmProfileInput.of(
                    "x", "y", ParadigmSlotInput.builder("gen.sg").build());
            assertThat(Problems.problemMessages(() -> ParadigmProfile.of(LATIN_NOUN, input)))
                    .containsExactly("slots.0 must give either an affix or a literal form");
        }

        @Test
        void keepsTheVariantEndingsASlotAlsoAccepts() {
            ParadigmProfile profile = ParadigmProfile.of(
                    LATIN_NOUN, ParadigmProfileInput.of("i-stem", "y", ParadigmSlotInput.of("acc.pl", "ēs", " īs ")));
            FeatureBundle bundle = FeatureBundle.of(LATIN_NOUN, ordered("case", "acc", "number", "pl"));
            assertThat(profile.requireSlot(bundle).affix()).isEqualTo("ēs");
            assertThat(profile.requireSlot(bundle).variants()).containsExactly("īs");
        }

        @Test
        void refusesAVariantThatRepeatsAnEnding() {
            ParadigmProfileInput repeated =
                    ParadigmProfileInput.of("x", "y", ParadigmSlotInput.of("acc.pl", "es", "is", "es"));
            assertThat(Problems.problemMessages(() -> ParadigmProfile.of(LATIN_NOUN, repeated)))
                    .containsExactly("slots.0.variants.1 repeats the ending es");
        }

        @Test
        void refusesVariantEndingsOnALiteralSlot() {
            ParadigmSlotInput both = new ParadigmSlotInput(
                    "nom.sg", Optional.empty(), Optional.empty(), Optional.of("vis"), Optional.empty(), List.of("is"));
            assertThat(Problems.problemMessages(
                            () -> ParadigmProfile.of(LATIN_NOUN, ParadigmProfileInput.of("x", "y", both))))
                    .containsExactly("slots.0 states a literal form, which takes no variant endings");
        }

        @Test
        void acceptsAnEmptyAffixWhichIsABareStemForm() {
            ParadigmProfile profile = ParadigmProfile.of(
                    LATIN_NOUN, ParadigmProfileInput.of("x", "y", ParadigmSlotInput.of("nom.sg", "")));
            FeatureBundle bundle = FeatureBundle.of(LATIN_NOUN, ordered("case", "nom", "number", "sg"));
            assertThat(profile.requireSlot(bundle).affix()).isEmpty();
        }
    }

    @Nested
    class SlotFor {

        @Test
        void findsTheSlotForABundle() {
            FeatureBundle bundle = FeatureBundle.of(LATIN_NOUN, ordered("case", "gen", "number", "pl"));
            assertThat(FIRST_DECLENSION.slotFor(bundle).map(ParadigmSlot::affix))
                    .contains("arum");
        }

        @Test
        void givesNothingForABundleTheProfileDoesNotCover() {
            FeatureBundle partial = FeatureBundle.of(LATIN_NOUN, Map.of("case", "gen"));
            assertThat(FIRST_DECLENSION.slotFor(partial)).isEmpty();
            assertThatThrownBy(() -> FIRST_DECLENSION.requireSlot(partial))
                    .hasMessageContaining("has no slot in profile");
        }
    }

    @Nested
    class LiteralSlots {

        private final ParadigmProfile withLiteral = ParadigmProfile.of(
                LATIN_NOUN,
                ParadigmProfileInput.of(
                        "vis",
                        "third-declension",
                        ParadigmSlotInput.builder("nom.sg")
                                .literal("vis")
                                .note("the paradigm is defective in the singular")
                                .build(),
                        ParadigmSlotInput.literal("acc.sg", "vim"),
                        ParadigmSlotInput.literal("abl.sg", "vi"),
                        ParadigmSlotInput.of("nom.pl", "es"),
                        ParadigmSlotInput.of("gen.pl", "ium"),
                        ParadigmSlotInput.of("dat.pl", "ibus"),
                        ParadigmSlotInput.of("acc.pl", "is"),
                        ParadigmSlotInput.of("abl.pl", "ibus")));

        @Test
        void listsTheSlotsThatStateAFormOutright() {
            assertThat(withLiteral.literalSlots().stream().map(ParadigmSlot::literal))
                    .containsExactly(Optional.of("vis"), Optional.of("vim"), Optional.of("vi"));
        }

        @Test
        void keepsANoteOnTheSlotThatCarriesOne() {
            assertThat(withLiteral.literalSlots().stream().findFirst().flatMap(ParadigmSlot::note))
                    .contains("the paradigm is defective in the singular");
        }

        @Test
        void findsTheHolesTheDefectiveParadigmLeaves() {
            assertThat(withLiteral.holesIn(LATIN_NOUN)).containsExactly("case=gen;number=sg", "case=dat;number=sg");
            assertThat(withLiteral.isTotal(LATIN_NOUN)).isFalse();
        }
    }

    @Nested
    class Coverage {

        @Test
        void callsAFullProfileTotal() {
            assertThat(FIRST_DECLENSION.isTotal(LATIN_NOUN)).isTrue();
            assertThat(FIRST_DECLENSION.holesIn(LATIN_NOUN)).isEmpty();
        }
    }

    @Nested
    class WithSlot {

        private final FeatureBundle genSg = FeatureBundle.of(LATIN_NOUN, ordered("case", "gen", "number", "sg"));

        @Test
        void replacesASlotTheProfileAlreadyHad() {
            ParadigmProfile grown = SECOND_NEUTER.withSlot(LATIN_NOUN, ParadigmSlotInput.of("gen.sg", "ii"));
            assertThat(grown.slotCount()).isEqualTo(10);
            assertThat(grown.slotFor(genSg).map(ParadigmSlot::affix)).contains("ii");
        }

        @Test
        void keepsTheVariantsOfTheSlotsItDidNotReplace() {
            ParadigmProfile withVariant = SECOND_NEUTER.withSlot(LATIN_NOUN, ParadigmSlotInput.of("gen.sg", "i", "ii"));
            ParadigmProfile grown = withVariant.withSlot(LATIN_NOUN, ParadigmSlotInput.of("dat.sg", "o"));
            assertThat(grown.slotFor(genSg).map(ParadigmSlot::variants)).contains(List.of("ii"));
        }

        @Test
        void leavesTheOriginalProfileAlone() {
            SECOND_NEUTER.withSlot(LATIN_NOUN, ParadigmSlotInput.of("gen.sg", "ii"));
            assertThat(SECOND_NEUTER.slotFor(genSg).map(ParadigmSlot::affix)).contains("i");
        }
    }
}
