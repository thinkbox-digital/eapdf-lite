package eapdf;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class ReaderTests {

  @Test
  void readEmlParsesKeyFieldsAndAssociatedFiles() throws Exception {
    var emlPath = TestSupport.firstInputWithExtension(".eml");
    EmailRecord record = EmlReader.readEml(
        emlPath.toString(),
        "",
        List.of(),
        false);

    Assertions.assertNotNull(record.subject);
    Assertions.assertFalse(record.subject.isBlank());
    Assertions.assertNotNull(record.from);
    Assertions.assertFalse(record.from.isBlank());
    Assertions.assertNotNull(record.to);
    Assertions.assertFalse(record.to.isBlank());
    Assertions.assertTrue(
        (record.textBody != null && !record.textBody.isBlank())
            || (record.htmlBody != null && !record.htmlBody.isBlank()));
    Assertions.assertEquals(emlPath.getFileName().toString(), record.sourceFilename);
    Assertions.assertTrue(
        record.associatedFiles.stream()
            .anyMatch(
                af ->
                    "Source".equals(af.afRelationship)
                        && emlPath.getFileName().toString().equals(af.filename)
                        && "Original EML source".equals(af.description)));
  }

}
