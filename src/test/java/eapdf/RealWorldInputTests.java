package eapdf;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class RealWorldInputTests {
  @ParameterizedTest
  @ValueSource(strings = {"eml_03_994.eml", "eml_10_16554.eml", "eml_18_2058.eml", "eml_24_5870.eml",
      "eml_25_1447.eml", "eml_14_7246.eml", "eml_02_23641.eml"})
  void unsupportedRealWorldCharsetIsRecoveredWithOriginalBytes(String name) throws Exception {
    Path source = Path.of("in", name);
    assumeTrue(Files.isRegularFile(source), "Real-world corpus file is not installed: " + source);
    EmailRecord record = EmlReader.readEml(source.toString(), "", List.of(), false);
    assertArrayEquals(Files.readAllBytes(source), record.sourceBytes);
    assertFalse(record.htmlBody.isBlank());
    assertTrue(record.conversionWarnings.stream().anyMatch(w -> w.contains("windows-3839")));
  }

  static void assertNoInlineBlocks(org.apache.pdfbox.cos.COSBase value, String parent) {
    if (value instanceof org.apache.pdfbox.cos.COSObject object) value = object.getObject();
    if (value instanceof org.apache.pdfbox.cos.COSArray array) {
      for (var child : array) assertNoInlineBlocks(child, parent);
    } else if (value instanceof org.apache.pdfbox.cos.COSDictionary dictionary) {
      String role = dictionary.getNameAsString(org.apache.pdfbox.cos.COSName.S, "");
      if (parent.equals("Span")) assertFalse(List.of("P", "Div", "Table", "BlockQuote").contains(role), role);
      assertNoInlineBlocks(dictionary.getDictionaryObject(org.apache.pdfbox.cos.COSName.K), role);
    }
  }

  @Test void invalidLocalDatesDoNotAcquireInventedValues() {
    assertEquals("", Common.sourceLocalDate("Fri Feb 30 12:00:00 2019"));
    assertEquals("", Common.sourceLocalDate("Tue Mar 5 16:08:13 2019 garbage"));
    assertFalse(Common.validSentDate("2019-02-30T12:00:00"));
    assertFalse(Common.validSentDate(""));
    assertFalse(Common.validSentDate("0000-03-05T16:08:13"));
    assertFalse(Common.validSentDate("2019-03-05T16:08"));
    assertTrue(Common.validSentDate("2019-03-05T16:08:13"));
    assertTrue(Common.validSentDate("2019-03-05T16:08:13Z"));
  }
}
