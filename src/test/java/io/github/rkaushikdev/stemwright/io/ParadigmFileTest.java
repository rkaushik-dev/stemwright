package io.github.rkaushikdev.stemwright.io;

import static io.github.rkaushikdev.stemwright.support.Profiles.LATIN_NOUN;
import static io.github.rkaushikdev.stemwright.support.TestMaps.ordered;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.stemwright.domain.errors.ValidationException;
import io.github.rkaushikdev.stemwright.domain.features.FeatureBundle;
import io.github.rkaushikdev.stemwright.domain.paradigm.ParadigmSlot;
import io.github.rkaushikdev.stemwright.domain.text.Text;
import io.github.rkaushikdev.stemwright.support.Problems;
import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class ParadigmFileTest {

    private static final String SOURCE = String.join(
            "\n",
            "# the first declension",
            "profile first-declension",
            "class   first-declension",
            "label   First declension",
            "",
            "nom.sg  -a",
            "gen.sg  -ae",
            "dat.sg  -ae",
            "acc.sg  -am",
            "abl.sg  -a",
            "nom.pl  -ae",
            "gen.pl  -arum",
            "dat.pl  -is",
            "acc.pl  -as",
            "abl.pl  -is",
            "",
            "syncretism ae: gen.sg dat.sg nom.pl",
            "");

    private static final FeatureBundle NOM_SG = FeatureBundle.of(LATIN_NOUN, ordered("case", "nom", "number", "sg"));

    private static ParadigmFile parse(String text) {
        return ParadigmFile.parse(LATIN_NOUN, text);
    }

    private static List<String> problemMessages(String text) {
        return Problems.problemMessages(() -> parse(text));
    }

    @Nested
    class ParseParadigmFile {

        @Test
        void readsTheHeader() {
            ParadigmFile file = parse(SOURCE);
            assertThat(file.profile().key()).isEqualTo("first-declension");
            assertThat(file.profile().classKey()).isEqualTo("first-declension");
            assertThat(file.profile().label()).isEqualTo("First declension");
        }

        @Test
        void readsEverySlot() {
            assertThat(parse(SOURCE).profile().slotCount()).isEqualTo(10);
            assertThat(parse(SOURCE).profile().slotBundles().get(0)).isEqualTo("case=nom;number=sg");
        }

        @Test
        void stripsTheLeadingDashFromAnEnding() {
            FeatureBundle bundle = FeatureBundle.of(LATIN_NOUN, ordered("case", "gen", "number", "pl"));
            assertThat(parse(SOURCE).profile().slotFor(bundle).map(ParadigmSlot::affix))
                    .contains("arum");
        }

        @Test
        void readsAnEndingWithNoDash() {
            ParadigmFile file = parse("profile p\nclass c\nnom.sg a\n");
            assertThat(file.profile().slotFor(NOM_SG).map(ParadigmSlot::affix)).contains("a");
        }

        @Test
        void readsALiteralForm() {
            ParadigmFile file = parse("profile p\nclass c\nnom.sg = vis\n");
            assertThat(file.profile().slotFor(NOM_SG).flatMap(ParadigmSlot::literal))
                    .contains("vis");
        }

        @Test
        void readsAnEndingOnANamedStem() {
            ParadigmFile file = parse("profile p\nclass c\nnom.sg oblique -is\n");
            ParadigmSlot slot = file.profile().slotFor(NOM_SG).orElseThrow();
            assertThat(slot.stemRole()).isEqualTo("oblique");
            assertThat(slot.affix()).isEqualTo("is");
        }

        @Test
        void readsTheVariantsWrittenAfterAnEnding() {
            ParadigmFile file = parse("profile p\nclass c\nacc.pl -es|-is\ngen.pl oblique -um|ium\n");
            FeatureBundle accPl = FeatureBundle.of(LATIN_NOUN, ordered("case", "acc", "number", "pl"));
            FeatureBundle genPl = FeatureBundle.of(LATIN_NOUN, ordered("case", "gen", "number", "pl"));
            assertThat(file.profile().requireSlot(accPl).affix()).isEqualTo("es");
            assertThat(file.profile().requireSlot(accPl).variants()).containsExactly("is");
            assertThat(file.profile().requireSlot(genPl).stemRole()).isEqualTo("oblique");
            assertThat(file.profile().requireSlot(genPl).variants()).containsExactly("ium");
        }

        @Test
        void readsASyncretismGroup() {
            ParadigmFile file = parse(SOURCE);
            assertThat(file.syncretism().twinsOf("case=gen;number=sg")).hasSize(2);
        }

        @Test
        void givesAnEmptyMapWhenNothingIsDeclared() {
            assertThat(parse("profile p\nclass c\nnom.sg -a\n").syncretism().groups())
                    .isEmpty();
        }

        @Test
        void ignoresCommentsAndBlankLines() {
            String text = "# a note\n\nprofile p\n\nclass c\n# another\nnom.sg -a\n";
            assertThat(parse(text).profile().slotCount()).isEqualTo(1);
        }

        @Test
        void stripsATrailingCommentFromALine() {
            ParadigmFile file = parse("profile p\nclass c\nnom.sg -a  # the usual ending\n");
            assertThat(file.profile().slotFor(NOM_SG).map(ParadigmSlot::affix)).contains("a");
        }
    }

    @Nested
    class Failures {

        @Test
        void refusesAFileWithNoProfileLine() {
            assertThat(problemMessages("class c\nnom.sg -a\n"))
                    .contains("profile is missing, the file needs a profile line");
        }

        @Test
        void refusesAFileWithNoClassLine() {
            assertThat(problemMessages("profile p\nnom.sg -a\n"))
                    .contains("class is missing, the file needs a class line");
        }

        @Test
        void namesTheLineOfASlotWithNothingAfterTheBundle() {
            List<String> messages = problemMessages("profile p\nclass c\nnom.sg\n");
            assertThat(messages.get(0)).contains("line 3");
        }

        @Test
        void namesTheLineOfAnEqualsSignWithNoForm() {
            List<String> messages = problemMessages("profile p\nclass c\nnom.sg =\n");
            assertThat(messages.get(0)).contains("line 3");
        }

        @Test
        void refusesASyncretismLineWithNoColon() {
            List<String> messages = problemMessages("profile p\nclass c\nnom.sg -a\nsyncretism ae gen.sg dat.sg\n");
            assertThat(messages.get(0)).contains("group name followed by a colon");
        }

        @Test
        void refusesASyncretismGroupWithOneMember() {
            List<String> messages = problemMessages("profile p\nclass c\nnom.sg -a\nsyncretism ae: gen.sg\n");
            assertThat(messages.get(0)).contains("at least two bundles");
        }

        @Test
        void refusesABundleTheSchemaDoesNotAdmit() {
            assertThatThrownBy(() -> parse("profile p\nclass c\ninst.sg -a\n")).isInstanceOf(ValidationException.class);
        }

        @Test
        void collectsSeveralBadLinesAtOnce() {
            List<String> messages = problemMessages("profile p\nclass c\nnom.sg\ngen.sg\n");
            assertThat(messages).hasSize(2);
        }
    }

    @Nested
    class WriteParadigmFile {

        @Test
        void roundTrips() {
            ParadigmFile file = parse(SOURCE);
            ParadigmFile rewritten = parse(file.write());
            assertThat(rewritten.profile().slotCount()).isEqualTo(file.profile().slotCount());
            assertThat(rewritten.syncretism().groups()).hasSize(1);
        }

        @Test
        void writesTheHeaderFirst() {
            List<String> lines = Text.split(parse(SOURCE).write(), "\n");
            assertThat(lines.get(0)).isEqualTo("profile first-declension");
            assertThat(lines.get(1)).contains("first-declension");
        }

        @Test
        void writesALiteralWithAnEqualsSign() {
            ParadigmFile file = parse("profile p\nclass c\nnom.sg = vis\n");
            assertThat(file.write()).contains("= vis");
        }

        @Test
        void writesTheVariantsBackAfterABar() {
            ParadigmFile file = parse("profile p\nclass c\nacc.pl -es|-is\n");
            assertThat(file.write()).contains("case=acc;number=pl -es|-is");
            assertThat(parse(file.write()).profile()).isEqualTo(file.profile());
        }

        @Test
        void namesAStemRoleThatIsNotTheBase() {
            ParadigmFile file = parse("profile p\nclass c\nnom.sg oblique -is\n");
            assertThat(file.write()).contains("oblique -is");
        }

        @Test
        void leavesTheSyncretismBlockOutWhenThereIsNone() {
            ParadigmFile file = parse("profile p\nclass c\nnom.sg -a\n");
            assertThat(file.write()).doesNotContain("syncretism");
        }
    }
}
