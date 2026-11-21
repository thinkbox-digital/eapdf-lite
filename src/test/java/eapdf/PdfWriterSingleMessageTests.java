package eapdf;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PdfWriterSingleMessageTests {

  @TempDir
  Path tempDir;

  @Test
  void renderedBodyStartsAsPdfA4AndFinalMetadataReplacesItsOutputIntent() throws Exception {
    byte[] body = PdfMailWriter.renderXhtmlPdf(
        "<html xmlns=\"http://www.w3.org/1999/xhtml\"><head><title>Body</title></head><body><p>Archival body</p></body></html>",
        "", TestSupport.fontPath());
    try (PDDocument document = Loader.loadPDF(body)) {
      String xmp = TestSupport.metadataXml(document);
      Assertions.assertTrue(xmp.contains("<pdfaid:part>4</pdfaid:part>"));
      Assertions.assertFalse(xmp.contains("<pdfaid:conformance>F</pdfaid:conformance>"));
      Assertions.assertEquals(1, document.getDocumentCatalog().getOutputIntents().size());
      var record = EmlReader.readEml(TestSupport.firstInputWithExtension(".eml").toString(), "", List.of(), false);
      PdfMailWriter.setPdfA3Metadata(document, TestSupport.iccPath(), record);
      Assertions.assertEquals(1, document.getDocumentCatalog().getOutputIntents().size());
      Assertions.assertTrue(TestSupport.metadataXml(document).contains("<pdfaid:part>4</pdfaid:part>"));
    }
  }

  @Test
  void writeEaPdfA3EmbedsExpectedFilesAndXmp() throws Exception {
    Path emlPath = TestSupport.firstInputWithExtension(".eml");
    EmailRecord record = EmlReader.readEml(
        emlPath.toString(),
        "",
        List.of(),
        false);

    Path out = tempDir.resolve("test_1s.pdf");
    PdfMailWriter.writeEaPdfA3(out, record, TestSupport.fontPath(), TestSupport.iccPath(), false, tempDir.resolve("debug"), 1);

    Assertions.assertTrue(Files.exists(out));

    try (PDDocument doc = Loader.loadPDF(out.toFile())) {
      PreservationRegressionTests.assertDPartTree(doc);
      PreservationRegressionTests.assertPageStructureBindings(doc);
      String xmp = TestSupport.metadataXml(doc);
      Assertions.assertTrue(xmp.contains("<pdfaid:conformance>F</pdfaid:conformance>"));
      Assertions.assertTrue(xmp.contains("<pdfmailid:conformance>s</pdfmailid:conformance>"));
      Assertions.assertTrue(xmp.contains("<pdfmailmeta:attachments>"));
      Assertions.assertTrue(xmp.contains("<pdfmailmeta:assets>"));

      TestSupport.assertPdfInfoBlank(doc);
      TestSupport.assertAfPresent(doc);

      var embeddedNames = TestSupport.embeddedFileNames(doc);
      Assertions.assertTrue(embeddedNames.stream().anyMatch(name -> name.endsWith(emlPath.getFileName().toString())));
      Assertions.assertFalse(embeddedNames.isEmpty());
      String expectedPageMode =
          record.associatedFiles.stream().anyMatch(af -> af != null && af.emailAttachment)
              ? "UseAttachments"
              : "UseOutlines";
      Assertions.assertEquals(expectedPageMode, doc.getDocumentCatalog().getCOSObject().getNameAsString(org.apache.pdfbox.cos.COSName.PAGE_MODE));
    }
  }
}
