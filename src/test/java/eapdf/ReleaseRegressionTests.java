package eapdf;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSObject;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReleaseRegressionTests {

  @TempDir
  Path tempDir;

  @Test
  void fontsPreserveSeveralWritingSystemsInBodyAndGeneratedText() throws Exception {
    String text = "Café Ελληνικά Кириллица Հայերեն ქართული";
    byte[] raw = ("Date: Fri, 16 Oct 2026 12:00:00 +0000\r\nSubject: Languages\r\nContent-Type: text/plain; charset=UTF-8\r\n\r\n" + text).getBytes(StandardCharsets.UTF_8);
    EmailRecord record = EmlReader.readRfc822Bytes(raw, "languages.eml", "languages.eml", "", List.of(), false);
    Path output = tempDir.resolve("languages.pdf");
    PdfMailWriter.writeEaPdfA3(output, record, TestSupport.fontPath(), TestSupport.iccPath(), false, tempDir, 1);
    try (PDDocument document = Loader.loadPDF(output.toFile())) {
      Assertions.assertTrue(new PDFTextStripper().getText(document).contains(text));
    }
    try (PDDocument document = new PDDocument()) {
      PDPage page = new PDPage();
      document.addPage(page);
      var font = PdfMailWriter.loadUiFont(document, TestSupport.fontPath());
      try (PDPageContentStream content = new PDPageContentStream(document, page)) {
        PdfMailWriter.drawText(content, font, 12, 40, 700, text);
      }
      Assertions.assertEquals(text, new PDFTextStripper().getText(document).trim());
    }
  }

  @Test
  void missingGlyphsAreReportedWithCodePointAndField() throws Exception {
    String missing = new String(Character.toChars(0x10FFFF));
    String subject = jakarta.mail.internet.MimeUtility.encodeText(missing, "UTF-8", "B");
    EmailRecord record = EmlReader.readRfc822Bytes(("Date: Fri, 16 Oct 2026 12:00:00 +0000\r\nSubject: " + subject + "\r\n\r\nBody").getBytes(StandardCharsets.UTF_8), "missing.eml", "missing.eml", "", List.of(), false);
    PdfMailWriter.writeEaPdfA3(tempDir.resolve("missing.pdf"), record, TestSupport.fontPath(), TestSupport.iccPath(), false, tempDir, 1);
    Assertions.assertTrue(record.conversionWarnings.stream().anyMatch(warning -> warning.contains("subject") && warning.contains("U+10FFFF")));
  }

  @Test
  void limitsRejectOversizedMessagesBodiesAndMetadataWithoutOutOfMemory() throws Exception {
    String messageSetting = System.getProperty("eapdf.maxMessageBytes");
    String bodySetting = System.getProperty("eapdf.maxBodyChars");
    String metadataSetting = System.getProperty("eapdf.maxMetadataBytes");
    try {
      System.setProperty("eapdf.maxMessageBytes", "64");
      Assertions.assertThrows(Common.UserFacingException.class, () -> EmlReader.readRfc822Bytes(new byte[65], "large.eml", "large.eml", "", List.of(), false));
      System.setProperty("eapdf.maxMessageBytes", "4096");
      System.setProperty("eapdf.maxBodyChars", "32");
      Assertions.assertThrows(Common.UserFacingException.class, () -> EmlReader.readRfc822Bytes(("Subject: Large\r\n\r\n" + "x".repeat(100)).getBytes(StandardCharsets.UTF_8), "large.eml", "large.eml", "", List.of(), false));
      System.setProperty("eapdf.maxMetadataBytes", "100");
      try (var job = Common.beginJob()) {
        Assertions.assertThrows(Common.UserFacingException.class, () -> Common.stageRecord(new EmailRecord()));
      }
    } finally {
      restore("eapdf.maxMessageBytes", messageSetting);
      restore("eapdf.maxBodyChars", bodySetting);
      restore("eapdf.maxMetadataBytes", metadataSetting);
    }
  }

  @Test
  void allProfilesKeepEaPdfStructureAndOmitPdfUaIdentification() throws Exception {
    EmailRecord first = EmlReader.readRfc822Bytes(("Date: Fri, 16 Oct 2026 12:00:00 +0000\r\nSubject: First\r\nContent-Type: text/html; charset=UTF-8\r\n\r\n"
        + "<h1>Real heading</h1><p>Real paragraph <a href='https://example.org'>link</a></p>"
        + "<table><tr><th>Column</th></tr><tr><td>Cell</td></tr></table>").getBytes(StandardCharsets.UTF_8), "first.eml", "first.eml", "", List.of(), false);
    EmailRecord second = EmlReader.readRfc822Bytes("Date: Fri, 16 Oct 2026 12:00:00 +0000\r\nSubject: Second\r\n\r\nSecond body".getBytes(StandardCharsets.UTF_8), "second.eml", "second.eml", "", List.of(), false);
    for (String profile : List.of("s", "si", "m", "mi", "c", "ci")) {
      Path output = tempDir.resolve("semantic_1" + profile + ".pdf");
      if (profile.startsWith("s")) {
        first.embedSourceMessage = profile.equals("s");
        PdfMailWriter.writeEaPdfA3(output, first, TestSupport.fontPath(), TestSupport.iccPath(), false, tempDir, 1);
      } else if (profile.startsWith("m")) PdfMailWriter.writeEaPdfArchive(output, List.of(first, second), TestSupport.fontPath(), TestSupport.iccPath(), false, tempDir, profile);
      else PdfMailWriter.writeEaPdfCollection(output, List.of(first, second), TestSupport.fontPath(), TestSupport.iccPath(), false, tempDir, profile);
      try (PDDocument document = Loader.loadPDF(output.toFile())) {
        Assertions.assertFalse(TestSupport.metadataXml(document).contains("pdfuaid:"));
        var tree = (COSDictionary)document.getDocumentCatalog().getCOSObject().getDictionaryObject(COSName.STRUCT_TREE_ROOT);
        List<String> roles = new ArrayList<>();
        collectRoles(tree.getDictionaryObject(COSName.K), roles);
        COSDictionary root = (COSDictionary)tree.getDictionaryObject(COSName.K);
        COSDictionary namespace = (COSDictionary)root.getDictionaryObject(COSName.getPDFName("NS"));
        Assertions.assertEquals("http://iso.org/pdf2/ssn", namespace.getString(COSName.getPDFName("NS")));
        var outline = document.getDocumentCatalog().getDocumentOutline().getFirstChild();
        Assertions.assertNotNull(outline.findDestinationPage(document));
        if (!profile.startsWith("c")) {
        Assertions.assertTrue(roles.contains("Table"), profile + roles);
        Assertions.assertTrue(roles.contains("TH"), profile + roles);
        Assertions.assertTrue(roles.contains("TD"), profile + roles);
        }
        if (!profile.startsWith("c")) Assertions.assertTrue(roles.contains("H1"), profile + roles);
        else Assertions.assertTrue(roles.contains("P"), profile + roles);
        for (PDPage page : document.getPages()) {
          Assertions.assertTrue(page.getCOSObject().containsKey(COSName.getPDFName("StructParents")));
          for (var annotation : page.getAnnotations()) Assertions.assertTrue(annotation.getCOSObject().containsKey(COSName.getPDFName("StructParent")));
        }
      }
    }
  }

  static void collectRoles(COSBase value, List<String> roles) {
    if (value instanceof COSObject reference) value = reference.getObject();
    if (value instanceof COSArray array) {
      for (COSBase child : array) collectRoles(child, roles);
    } else if (value instanceof COSDictionary dictionary && dictionary.containsKey(COSName.S)) {
      roles.add(dictionary.getNameAsString(COSName.S));
      collectRoles(dictionary.getDictionaryObject(COSName.K), roles);
    }
  }

  static void restore(String key, String value) {
    if (value == null) System.clearProperty(key);
    else System.setProperty(key, value);
  }
}
