package eapdf;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import javax.xml.parsers.DocumentBuilderFactory;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.jsoup.Jsoup;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RemainingPreservationTests {
  @TempDir
  Path tempDir;

  @Test
  void unnamedCidImagesAreCollectedWithoutChangingAttachmentCounts() throws Exception {
    String body = EmailContentRegressionTests.section("related", "text/html", "", "<p>Body</p><img src=\"cid:picture\"/>")
        + EmailContentRegressionTests.section("related", "image/png", "Content-ID: <picture>\r\nContent-Transfer-Encoding: base64\r\n", "AQID")
        + "--related--\r\n";
    EmailRecord record = EmailContentRegressionTests.parse("multipart/related; boundary=related", body);
    AssociatedFile image = record.associatedFiles.stream().filter(file -> file.inline).findFirst().orElseThrow();
    Assertions.assertArrayEquals(new byte[]{1, 2, 3}, image.bytes);
    Assertions.assertEquals("<picture>", image.contentId);
    Assertions.assertFalse(image.emailAttachment);
    Assertions.assertEquals(0, record.attachmentCount);
    Assertions.assertEquals("Body", Jsoup.parse(record.htmlBody).text());
  }

  @Test
  void inlineImagesSharingFilenameKeepDistinctUrisAndBytes() throws Exception {
    EmailRecord record = new EmailRecord();
    for (int index = 0; index < 2; index++) {
      AssociatedFile image = new AssociatedFile();
      image.filename = "image.png";
      image.mime = "image/png";
      image.contentId = "picture" + index;
      image.bytes = new byte[]{(byte)index, 2, 3};
      image.inline = true;
      record.associatedFiles.add(image);
    }
    String rewritten = PdfMailWriter.rewriteCidToFileUris("<img src=\"cid:picture0\"/><img src=\"cid:picture1\"/>", record.associatedFiles, record, false, tempDir);
    var images = Jsoup.parse(rewritten).select("img");
    Assertions.assertNotEquals(images.get(0).attr("src"), images.get(1).attr("src"));
    for (int index = 0; index < 2; index++) {
      String uri = images.get(index).attr("src");
      Assertions.assertArrayEquals(record.associatedFiles.get(index).bytes, record.renderAssets.get(uri));
      Assertions.assertArrayEquals(record.associatedFiles.get(index).bytes, Files.readAllBytes(Path.of(java.net.URI.create(uri))));
    }
    Assertions.assertEquals("image.png", record.associatedFiles.get(0).filename);
  }

  @Test
  void emptyAttachmentsRoundTripWithZeroSizeAndChecksum() throws Exception {
    String body = EmailContentRegressionTests.section("mixed", "text/plain", "", "Body")
        + EmailContentRegressionTests.section("mixed", "application/octet-stream", "Content-Disposition: attachment; filename=empty.bin\r\nContent-Transfer-Encoding: base64\r\n", "")
        + "--mixed--\r\n";
    EmailRecord original = EmailContentRegressionTests.parse("multipart/mixed; boundary=mixed", body);
    Assertions.assertEquals(1, original.attachmentCount);
    AssociatedFile attachment = original.associatedFiles.stream().filter(file -> file.emailAttachment).findFirst().orElseThrow();
    Assertions.assertEquals(0, attachment.bytes.length);
    Assertions.assertEquals("d41d8cd98f00b204e9800998ecf8427e", attachment.checksumMd5);
    EmailRecord decoded = EmlReader.readRfc822Bytes(Common.synthesizeEml(original), "empty.eml", "empty.eml", "", List.of(), false);
    Assertions.assertEquals(1, decoded.attachmentCount);
    Assertions.assertEquals(0, decoded.associatedFiles.stream().filter(file -> file.emailAttachment).findFirst().orElseThrow().bytes.length);
    Assertions.assertTrue(PdfMailWriter.buildXmpMetadata(original).contains("<pdfmailmeta:SizeInBytes>0</pdfmailmeta:SizeInBytes>"));
  }

  @Test
  void chronologyUsesInstantsAcrossFormatsAndPutsUnknownDatesLast() {
    EmailRecord earlier = new EmailRecord();
    earlier.rawDateHeader = "Tue, 13 Oct 2026 08:30:00 -0400";
    EmailRecord later = new EmailRecord();
    later.sentXmp = "2026-10-13T14:00:00+01:00";
    EmailRecord missing = new EmailRecord();
    missing.date = "invalid";
    List<EmailRecord> expected = List.of(earlier, later, missing);
    Assertions.assertEquals(expected, PdfCollectionSupport.sortRecordsChronologically(List.of(missing, later, earlier)));
    Assertions.assertEquals(expected, PdfMailWriter.sortRecordsChronologically(List.of(missing, later, earlier)));
    Assertions.assertNull(Common.parseMessageDate("Thu, 31 Feb 2026 12:00:00 +0000"));
  }

  @Test
  void replyHeadersSurviveReadingReconstructionAndBothXmpPaths() throws Exception {
    byte[] raw = ("Date: Fri, 16 Oct 2026 12:00:00 +0000\r\nFrom: author@example.org\r\nMessage-ID: <reply@example.org>\r\n"
        + "In-Reply-To: <parent@example.org> <other@example.org>\r\n"
        + "References: <root@example.org>\r\n <parent@example.org>\r\n\r\nReply").getBytes(StandardCharsets.UTF_8);
    EmailRecord original = EmlReader.readRfc822Bytes(raw, "reply.eml", "reply.eml", "", List.of(), false);
    EmailRecord decoded = EmlReader.readRfc822Bytes(Common.synthesizeEml(original), "reply.eml", "reply.eml", "", List.of(), false);
    Assertions.assertEquals(original.inReplyTo, decoded.inReplyTo);
    Assertions.assertEquals(original.references, decoded.references);
    Assertions.assertEquals("<reply@example.org>", decoded.messageId);
    for (String metadata : List.of(PdfMailWriter.buildXmpMetadata(original), PdfMailWriter.buildXmpMetadata(List.of(original), "m"))) {
      var xml = TestSupport.parseMetadata(metadata);
      var replies = xml.getElementsByTagNameNS("http://www.pdfa.org/eapdf/ns/meta/", "In-Reply-To");
      Assertions.assertEquals(1, replies.getLength());
      var bags = ((org.w3c.dom.Element) replies.item(0)).getElementsByTagNameNS("http://www.w3.org/1999/02/22-rdf-syntax-ns#", "Bag");
      Assertions.assertEquals(1, bags.getLength());
      var items = ((org.w3c.dom.Element) bags.item(0)).getElementsByTagNameNS("http://www.w3.org/1999/02/22-rdf-syntax-ns#", "li");
      Assertions.assertEquals(1, items.getLength());
      Assertions.assertEquals("<parent@example.org> <other@example.org>", items.item(0).getTextContent());
    }
  }

  @Test
  void encryptedBodyShowsAnExplicitNoticeAndPreservesOriginalSource() throws Exception {
    for (String type : List.of("application/pkcs7-mime; smime-type=enveloped-data", "application/x-pkcs7-mime", "multipart/encrypted; boundary=encrypted")) {
      String payload = type.startsWith("multipart/")
          ? EmailContentRegressionTests.section("encrypted", "application/pgp-encrypted", "", "Version: 1")
              + EmailContentRegressionTests.section("encrypted", "application/octet-stream", "", "Unreadable encrypted payload") + "--encrypted--\r\n"
          : "Unreadable encrypted payload";
      EmailRecord record = EmailContentRegressionTests.parse(type, payload);
      Assertions.assertTrue(record.textBody.contains("Encrypted email body could not be rendered"));
      Assertions.assertEquals(1, record.conversionWarnings.size());
      Assertions.assertTrue(new String(record.sourceBytes, StandardCharsets.UTF_8).contains("Unreadable encrypted payload"));
    }
    EmailRecord record = EmailContentRegressionTests.parse("application/pkcs7-mime; smime-type=enveloped-data", "encrypted payload");
    byte[] pdf = PdfMailWriter.buildSingleMessagePdf(tempDir.resolve("encrypted.pdf"), record, TestSupport.fontPath(), TestSupport.iccPath(), false, tempDir, 1, "s").pdfBytes;
    try (PDDocument document = Loader.loadPDF(pdf)) {
      Assertions.assertTrue(new PDFTextStripper().getText(document).contains("Encrypted email body could not be rendered"));
      Assertions.assertArrayEquals(record.sourceBytes, AttachmentProfilePolicyTests.bytes(AttachmentProfilePolicyTests.files(document).values().iterator().next()));
    }
    EmailRecord empty = EmailContentRegressionTests.parse("text/plain", "");
    Assertions.assertTrue(empty.conversionWarnings.isEmpty());
    EmailRecord unsupported = EmailContentRegressionTests.parse("application/unknown-body", "Unreadable body");
    Assertions.assertTrue(unsupported.textBody.contains("Unsupported email body could not be rendered"));
    Assertions.assertEquals(1, unsupported.conversionWarnings.size());
    EmailRecord alternative = EmailContentRegressionTests.parse("multipart/alternative; boundary=choices",
        EmailContentRegressionTests.section("choices", "text/plain", "", "Supported readable alternative")
            + EmailContentRegressionTests.section("choices", "application/unknown-body", "", "Unreadable alternative") + "--choices--\r\n");
    Assertions.assertTrue(alternative.textBody.contains("Supported readable alternative"));
    Assertions.assertFalse(alternative.textBody.contains("could not be rendered"));
    Assertions.assertTrue(alternative.conversionWarnings.isEmpty(), "A discarded alternative is not the rendered email body");
    EmailRecord mixed = EmailContentRegressionTests.parse("multipart/mixed; boundary=sections",
        EmailContentRegressionTests.section("sections", "text/plain", "", "Readable section")
            + EmailContentRegressionTests.section("sections", "application/unknown-body", "", "Unreadable section") + "--sections--\r\n");
    Assertions.assertTrue(mixed.textBody.contains("could not be rendered"));
    Assertions.assertEquals(1, mixed.conversionWarnings.size(), "An unsupported section actually selected for rendering still needs a warning");
  }

}
