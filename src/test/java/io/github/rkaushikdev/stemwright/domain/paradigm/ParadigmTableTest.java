package io.github.rkaushikdev.stemwright.domain.paradigm;

import static io.github.rkaushikdev.stemwright.support.Profiles.FIRST_DECLENSION;
import static io.github.rkaushikdev.stemwright.support.Profiles.LATIN_NOUN;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.stemwright.domain.features.FeatureBundle;
import io.github.rkaushikdev.stemwright.domain.features.FeatureSchema;
import io.github.rkaushikdev.stemwright.domain.features.FeatureSchemaInput;
import io.github.rkaushikdev.stemwright.domain.features.FeatureSchemaInput.Dimension;
import io.github.rkaushikdev.stemwright.domain.lexicon.Lexeme;
import io.github.rkaushikdev.stemwright.domain.lexicon.LexemeContext;
import io.github.rkaushikdev.stemwright.domain.lexicon.LexemeInput;
import io.github.rkaushikdev.stemwright.domain.lexicon.StemInput;
import io.github.rkaushikdev.stemwright.domain.lexicon.StemSet;
import io.github.rkaushikdev.stemwright.domain.text.Text;
import io.github.rkaushikdev.stemwright.support.Classes;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class ParadigmTableTest {

    private static final TableRequest CASE_BY_NUMBER = TableRequest.of("case", "number");

    private static final Lexeme PUELLA = Lexeme.of(
            new LexemeContext(LATIN_NOUN, Classes.LATIN),
            LexemeInput.of("puella", "first-declension", StemSet.of(StemInput.of("base", "puell"))));

    private static final ParadigmTable TABLE =
            ParadigmTable.build(LATIN_NOUN, RuleSet.NONE, PUELLA, FIRST_DECLENSION, CASE_BY_NUMBER);

    private static ParadigmProfile firstDeclensionWith(ParadigmSlotInput... slots) {
        return ParadigmProfile.of(LATIN_NOUN, ParadigmProfileInput.of("p", "first-declension", slots));
    }

    private static ParadigmTable caseByNumber(ParadigmProfile profile) {
        return ParadigmTable.build(LATIN_NOUN, RuleSet.NONE, PUELLA, profile, CASE_BY_NUMBER);
    }

    @Nested
    class Build {

        @Test
        void hasOneRowPerCaseAndOneColumnPerNumber() {
            assertThat(TABLE.rowValues()).containsExactly("nom", "gen", "dat", "acc", "abl");
            assertThat(TABLE.columnValues()).containsExactly("sg", "pl");
            assertThat(TABLE.cells()).hasSize(5);
            assertThat(TABLE.cells().get(0)).hasSize(2);
        }

        @Test
        void fillsEveryCellTheProfileCovers() {
            assertThat(TABLE.filledCount()).isEqualTo(10);
            assertThat(TABLE.holeCount()).isZero();
            assertThat(TABLE.blockedCount()).isZero();
        }

        @Test
        void putsTheRightFormInTheRightCell() {
            assertThat(TABLE.cell("gen", "sg").flatMap(TableCell::form)).contains("puellae");
            assertThat(TABLE.cell("abl", "pl").flatMap(TableCell::form)).contains("puellis");
        }

        @Test
        void givesNothingForARowOrColumnThatIsNotThere() {
            assertThat(TABLE.cell("voc", "sg")).isEmpty();
            assertThat(TABLE.cell("gen", "du")).isEmpty();
        }

        @Test
        void refusesToUseOneDimensionTwice() {
            assertThatThrownBy(() -> ParadigmTable.build(
                            LATIN_NOUN, RuleSet.NONE, PUELLA, FIRST_DECLENSION, TableRequest.of("case", "case")))
                    .hasMessageContaining("both rows and columns");
        }

        @Test
        void refusesADimensionTheSchemaDoesNotHave() {
            assertThatThrownBy(() -> ParadigmTable.build(
                            LATIN_NOUN, RuleSet.NONE, PUELLA, FIRST_DECLENSION, TableRequest.of("tense", "number")))
                    .hasMessageContaining("not in the latin schema");
        }
    }

    @Nested
    class Holes {

        @Test
        void countsACellTheProfileSaysNothingAbout() {
            ParadigmTable sparse = caseByNumber(
                    firstDeclensionWith(ParadigmSlotInput.of("nom.sg", "a"), ParadigmSlotInput.of("gen.sg", "ae")));
            assertThat(sparse.filledCount()).isEqualTo(2);
            assertThat(sparse.holeCount()).isEqualTo(8);
        }
    }

    @Nested
    class Rendering {

        @Test
        void putsTheHeadersFirstAndTheRowLabelsDownTheSide() {
            List<List<String>> grid = TABLE.render();
            assertThat(grid.get(0)).containsExactly("", "sg", "pl");
            assertThat(grid.get(1)).containsExactly("nom", "puella", "puellae");
        }

        @Test
        void marksAnEmptyCellWithTheBlankItWasGiven() {
            ParadigmTable sparse = caseByNumber(firstDeclensionWith(ParadigmSlotInput.of("nom.sg", "a")));
            assertThat(sparse.render("x").get(1)).containsExactly("nom", "puella", "x");
        }

        @Test
        void printsTheVariantsASlotAcceptsAfterTheFormWithASlash() {
            ParadigmTable withOldGenitive = caseByNumber(firstDeclensionWith(
                    ParadigmSlotInput.of("nom.sg", "a"), ParadigmSlotInput.of("gen.sg", "ae", "ai", "as")));
            assertThat(withOldGenitive.render().get(2)).containsExactly("gen", "puellae/puellai/puellas", "-");
            assertThat(withOldGenitive.cell("gen", "sg").map(TableCell::variants))
                    .contains(List.of("puellai", "puellas"));
            assertThat(withOldGenitive.cell("gen", "sg").flatMap(TableCell::form))
                    .contains("puellae");
            assertThat(withOldGenitive.cell("nom", "sg").map(TableCell::variants))
                    .contains(List.of());
        }

        @Test
        void padsColumnsToAFixedWidthInTheTextForm() {
            List<String> lines = Text.split(TABLE.toText(), "\n");
            assertThat(lines.get(0)).isEqualTo("     sg       pl");
            assertThat(lines.get(1)).isEqualTo("nom  puella   puellae");
            assertThat(lines).allMatch(line -> line.equals(Text.trimEnd(line)));
        }
    }

    @Nested
    class AThirdDimension {

        private final FeatureSchema gendered = FeatureSchema.of(FeatureSchemaInput.of(
                "latin",
                Dimension.of("case", "nom", "gen"),
                Dimension.of("number", "sg", "pl"),
                Dimension.of("gender", "masc", "fem")));

        private final LexemeContext genderedContext = new LexemeContext(gendered, Classes.LATIN);

        private final Lexeme bonus = Lexeme.of(
                genderedContext, LexemeInput.of("bonus", "first-declension", StemSet.of(StemInput.of("base", "bon"))));

        private final ParadigmProfile profile = ParadigmProfile.of(
                gendered,
                ParadigmProfileInput.of(
                        "p",
                        "first-declension",
                        ParadigmSlotInput.of("nom.sg.masc", "us"),
                        ParadigmSlotInput.of("gen.sg.masc", "i"),
                        ParadigmSlotInput.of("nom.pl.masc", "i"),
                        ParadigmSlotInput.of("gen.pl.masc", "orum"),
                        ParadigmSlotInput.of("nom.sg.fem", "a"),
                        ParadigmSlotInput.of("gen.sg.fem", "ae"),
                        ParadigmSlotInput.of("nom.pl.fem", "ae"),
                        ParadigmSlotInput.of("gen.pl.fem", "arum")));

        @Test
        void refusesToLeaveTheThirdDimensionOpen() {
            assertThatThrownBy(() -> ParadigmTable.build(gendered, RuleSet.NONE, bonus, profile, CASE_BY_NUMBER))
                    .hasMessageContaining("leaves gender open");
        }

        @Test
        void buildsOnceTheThirdDimensionIsFixed() {
            ParadigmTable fixed = ParadigmTable.build(
                    gendered,
                    RuleSet.NONE,
                    bonus,
                    profile,
                    CASE_BY_NUMBER.withFixed(FeatureBundle.of(gendered, Map.of("gender", "fem"))));
            assertThat(fixed.cell("gen", "sg").flatMap(TableCell::form)).contains("bonae");
            assertThat(fixed.filledCount()).isEqualTo(4);
        }

        @Test
        void blocksCellsTheInherentFeaturesRuleOut() {
            Lexeme feminine = Lexeme.of(
                    genderedContext,
                    LexemeInput.builder("puella", "first-declension", StemSet.of(StemInput.of("base", "puell")))
                            .inherent(Map.of("gender", "fem"))
                            .build());
            ParadigmTable masculine = ParadigmTable.build(
                    gendered,
                    RuleSet.NONE,
                    feminine,
                    profile,
                    CASE_BY_NUMBER.withFixed(FeatureBundle.of(gendered, Map.of("gender", "masc"))));
            assertThat(masculine.blockedCount()).isEqualTo(4);
            assertThat(masculine.filledCount()).isZero();
            assertThat(masculine.holeCount()).isZero();
        }
    }

    @Nested
    class FlatRows {

        @Test
        void findsARowWhoseFormsAreAllTheSame() {
            ParadigmTable built = caseByNumber(firstDeclensionWith(
                    ParadigmSlotInput.of("nom.sg", "a"),
                    ParadigmSlotInput.of("nom.pl", "a"),
                    ParadigmSlotInput.of("gen.sg", "ae"),
                    ParadigmSlotInput.of("gen.pl", "arum")));
            assertThat(built.flatRows()).containsExactly("nom");
        }

        @Test
        void doesNotCallASingleFilledCellAFlatRow() {
            ParadigmTable built = caseByNumber(firstDeclensionWith(ParadigmSlotInput.of("nom.sg", "a")));
            assertThat(built.flatRows()).isEmpty();
        }
    }
}
