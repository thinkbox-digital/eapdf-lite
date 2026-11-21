package eapdf;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import javax.imageio.ImageIO;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSObject;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PreservationRegressionTests {
  @TempDir
  Path tempDir;

  static byte[] nestedEml(String subject, String body) {
    return ("From: author@example.org\r\nTo: recipient@example.org\r\n"
        + "Date: Tue, 13 Oct 2026 12:00:00 +0000\r\nSubject: " + subject
        + "\r\nMessage-ID: <duplicate@example.org>\r\nMIME-Version: 1.0\r\n" + body)
        .getBytes(StandardCharsets.UTF_8);
  }

  static byte[] messageWithAttachment(String subject, byte[] attachment) {
    String boundary = "boundary_" + Common.safeFileStem(subject);
    return nestedEml(subject, "Content-Type: multipart/mixed; boundary=" + boundary + "\r\n\r\n"
        + "--" + boundary + "\r\nContent-Type: text/plain; charset=UTF-8\r\n\r\nParent body\r\n"
        + "--" + boundary + "\r\nContent-Type: message/rfc822\r\nContent-Disposition: attachment; filename=child.eml\r\n\r\n"
        + new String(attachment, StandardCharsets.UTF_8) + "\r\n--" + boundary + "--\r\n");
  }

  @Test
  void attachedEmlPreservesHeadersBodyAndFurtherNestedMessagesInsideSourceEmail() throws Exception {
    byte[] grandchild = nestedEml("Grandchild", "Content-Type: text/plain\r\n\r\nDeep message body");
    byte[] child = messageWithAttachment("Child", grandchild);
    EmailRecord record = EmlReader.readRfc822Bytes(messageWithAttachment("Parent", child), "parent.eml", "parent.eml", "", List.of(), false);
    AssociatedFile attachment = record.associatedFiles.stream().filter(file -> file.emailAttachment).findFirst().orElseThrow();
    Assertions.assertArrayEquals(child, attachment.bytes);
    EmailRecord childRecord = EmlReader.readRfc822Bytes(attachment.bytes, "child.eml", "child.eml", "", List.of(), false);
    Assertions.assertEquals("Child", childRecord.subject);
    Assertions.assertArrayEquals(grandchild, childRecord.associatedFiles.stream().filter(file -> file.emailAttachment).findFirst().orElseThrow().bytes);
    record.embedSourceMessage = true;
    Path output = tempDir.resolve("attached_message.pdf");
    PdfMailWriter.writeEaPdfA3(output, record, TestSupport.fontPath(), TestSupport.iccPath(), false, tempDir, 1);
    try (PDDocument document = Loader.loadPDF(output.toFile())) {
      var embedded = document.getDocumentCatalog().getNames().getEmbeddedFiles().getNames();
      Assertions.assertEquals(1, embedded.size());
      byte[] source = embedded.values().iterator().next().getEmbeddedFileUnicode().toByteArray();
      EmailRecord decoded = EmlReader.readRfc822Bytes(source, "parent.eml", "parent.eml", "", List.of(), false);
      Assertions.assertTrue(decoded.associatedFiles.stream().anyMatch(file -> file.emailAttachment && java.util.Arrays.equals(child, file.bytes)));
      assertDPartTree(document);
      assertPageStructureBindings(document);
    }
  }

  @Test
  void archiveIdAndDuplicateMessageIdsDoNotCollideAndMissingMessageIdsStayMissing() throws Exception {
    byte[] raw = nestedEml("Duplicate", "Content-Type: text/plain\r\n\r\nBody");
    EmailRecord first = EmlReader.readRfc822Bytes(raw, "same.eml", "same.eml", "archive-id", List.of(), false);
    EmailRecord second = EmlReader.readRfc822Bytes(raw, "same.eml", "same.eml", "archive-id", List.of(), false);
    Assertions.assertEquals(first.messageId, second.messageId);
    Assertions.assertEquals("archive-id", first.archiveGlobalId);
    Assertions.assertNotEquals(first.archiveGlobalId, first.mailGuid);
    Assertions.assertNotEquals(first.mailGuid, second.mailGuid);
    String identifier = first.mailGuid;
    Common.finalizeRecordMetadata(first);
    Assertions.assertEquals(identifier, first.mailGuid);
    EmailRecord missing = new EmailRecord();
    missing.archiveGlobalId = "archive-id";
    Common.finalizeRecordMetadata(missing);
    Assertions.assertEquals("", missing.messageId);
    Assertions.assertFalse(missing.mailGuid.isBlank());
  }

  @Test
  void rendererMakesNoNetworkRequestsAndRejectsUntrustedFiles() throws Exception {
    AtomicInteger connections = new AtomicInteger();
    try (ServerSocket server = new ServerSocket(0, 10, InetAddress.getLoopbackAddress())) {
      Thread listener = new Thread(() -> {
        while (!server.isClosed()) {
          try (var client = server.accept()) {
            connections.incrementAndGet();
            client.getOutputStream().write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
          } catch (SocketException closed) {
            break;
          } catch (java.io.IOException error) {
            throw new java.io.UncheckedIOException(error);
          }
        }
      });
      listener.setDaemon(true);
      listener.start();
      String address = "http://localhost:" + server.getLocalPort();
      Path privateImage = tempDir.resolve("private.png");
      ImageIO.write(new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "png", privateImage.toFile());
      String html = "<html><head><link rel='stylesheet' href='" + address + "/style.css'/></head><body>Body"
          + "<img src='" + address + "/track.png'/><img src='" + privateImage.toUri() + "'/></body></html>";
      byte[] bytes = PdfMailWriter.renderHtmlPdf(html, tempDir.toUri().toString(), TestSupport.fontPath(), new EmailRecord(), false);
      try (PDDocument document = Loader.loadPDF(bytes)) {
        Assertions.assertEquals(0, imageCount(document));
      }
      Assertions.assertEquals(0, connections.get());
      Assertions.assertFalse(PdfMailWriter.isAllowedRenderResource("ftp://localhost/resource"));
      Assertions.assertFalse(PdfMailWriter.isAllowedRenderResource("jar:file:/tmp/private.jar!/image.png"));
      server.close();
      listener.join(1000);
    }
  }

  @Test
  void rendererUsesTrustedInlineBytesRatherThanFilesOnDisk() throws Exception {
    EmailRecord record = new EmailRecord();
    AssociatedFile image = new AssociatedFile();
    image.filename = "inline.png";
    image.contentId = "picture";
    image.mime = "image/png";
    ByteArrayOutputStream png = new ByteArrayOutputStream();
    ImageIO.write(new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "png", png);
    image.bytes = png.toByteArray();
    record.associatedFiles.add(image);
    String body = PdfMailWriter.rewriteCidToFileUris("<img src='cid:picture'/>", record.associatedFiles, record, false, tempDir);
    Files.writeString(tempDir.resolve("inline.png"), "not an image");
    byte[] bytes = PdfMailWriter.renderHtmlPdf("<html><body>Body" + body + "</body></html>", tempDir.toUri().toString(), TestSupport.fontPath(), record, false);
    try (PDDocument document = Loader.loadPDF(bytes)) {
      Assertions.assertTrue(imageCount(document) > 0);
    }
  }

  static int imageCount(PDDocument document) throws Exception {
    int count = 0;
    for (PDPage page : document.getPages()) {
      for (COSName name : page.getResources().getXObjectNames()) {
        if (page.getResources().getXObject(name) instanceof PDImageXObject) count++;
      }
    }
    return count;
  }

  @Test
  void archiveRejectsOneEmailAndRepeatedInternalIdentifiers() throws Exception {
    EmailRecord first = EmlReader.readRfc822Bytes(nestedEml("First", "Content-Type: text/plain\r\n\r\nBody"), "first.eml", "first.eml", "", List.of(), false);
    Assertions.assertThrows(Common.UserFacingException.class, () -> PdfMailWriter.writeEaPdfArchive(tempDir.resolve("one.pdf"), List.of(first),
        TestSupport.fontPath(), TestSupport.iccPath(), false, tempDir, "m"));
    Assertions.assertThrows(Common.UserFacingException.class, () -> PdfMailWriter.writeEaPdfArchive(tempDir.resolve("duplicate.pdf"), List.of(first, first),
        TestSupport.fontPath(), TestSupport.iccPath(), false, tempDir, "m"));
  }

  @Test
  void allProfileBuildersProduceValidSerializedDocumentParts() throws Exception {
    for (String profile : List.of("s", "si", "m", "mi", "c", "ci")) {
      try (PDDocument document = new PDDocument()) {
        document.setVersion(2.0f);
        int pages = profile.startsWith("m") ? 4 : 2;
        for (int index = 0; index < pages; index++) document.addPage(new PDPage());
        EmailRecord first = new EmailRecord();
        first.sourceType = "eml";
        first.sourceMime = "message/rfc822";
        if (profile.startsWith("m")) {
          EmailRecord second = new EmailRecord();
          second.sourceType = "eml";
          PdfMailWriter.addArchiveDPartStructure(document, List.of(new PdfCollectionSupport.MessageSegment(first, 2, 3),
              new PdfCollectionSupport.MessageSegment(second, 4, 4)), 1);
        } else if (profile.startsWith("c")) {
          if (profile.equals("c")) PdfCollectionSupport.addCollectionDPartStructure(document, List.of(first), profile);
          else PdfMailWriter.addCollectionDPartStructure(document, List.of(first), profile);
        } else {
          PdfMailWriter.addDPartStructure(document, first);
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        document.save(output);
        try (PDDocument reopened = Loader.loadPDF(output.toByteArray())) {
          assertDPartTree(reopened);
        }
      }
    }
  }

  @Test
  void existingPdfOutputIsIntentionallyOverwritten() throws Exception {
    Path output = tempDir.resolve("existing.pdf");
    Files.writeString(output, "old output");
    EmailRecord record = EmlReader.readRfc822Bytes(nestedEml("Overwrite", "Content-Type: text/plain\r\n\r\nBody"), "overwrite.eml", "overwrite.eml", "", List.of(), false);
    PdfMailWriter.writeEaPdfA3(output, record, TestSupport.fontPath(), TestSupport.iccPath(), false, tempDir, 1);
    try (PDDocument document = Loader.loadPDF(output.toFile())) {
      Assertions.assertTrue(document.getNumberOfPages() > 0);
    }
  }

  static void assertDPartTree(PDDocument document) {
    COSObject root = Assertions.assertInstanceOf(COSObject.class, document.getDocumentCatalog().getCOSObject().getItem(COSName.getPDFName("DPartRoot")));
    COSDictionary dictionary = (COSDictionary) root.getObject();
    COSObject rootNode = Assertions.assertInstanceOf(COSObject.class, dictionary.getItem(COSName.getPDFName("DPartRootNode")));
    assertDPartNode(rootNode, root);
    for (PDPage page : document.getPages()) {
      Assertions.assertInstanceOf(COSObject.class, page.getCOSObject().getItem(COSName.getPDFName("DPart")));
    }
  }

  static void assertDPartNode(COSObject node, COSObject parent) {
    COSDictionary dictionary = (COSDictionary) node.getObject();
    COSObject actualParent = Assertions.assertInstanceOf(COSObject.class, dictionary.getItem(COSName.getPDFName("Parent")));
    Assertions.assertSame(parent.getObject(), actualParent.getObject());
    COSBase children = dictionary.getDictionaryObject(COSName.getPDFName("DParts"));
    if (children != null) {
      COSArray groups = Assertions.assertInstanceOf(COSArray.class, children);
      Assertions.assertTrue(groups.size() > 0);
      for (COSBase value : groups) {
        COSArray group = Assertions.assertInstanceOf(COSArray.class, value);
        Assertions.assertTrue(group.size() > 0);
        for (COSBase child : group) assertDPartNode(Assertions.assertInstanceOf(COSObject.class, child), node);
      }
      Assertions.assertNull(dictionary.getItem(COSName.getPDFName("Start")));
    } else {
      COSObject start = Assertions.assertInstanceOf(COSObject.class, dictionary.getItem(COSName.getPDFName("Start")));
      COSBase end = dictionary.getItem(COSName.getPDFName("End"));
      if (end != null) Assertions.assertNotSame(start.getObject(), Assertions.assertInstanceOf(COSObject.class, end).getObject());
      COSDictionary metadata = (COSDictionary) dictionary.getDictionaryObject(COSName.getPDFName("DPM"));
      Assertions.assertInstanceOf(COSName.class, metadata.getItem(COSName.getPDFName("Mail_ContentSetType")));
      if (metadata.containsKey(COSName.getPDFName("Mail_Subtype"))) Assertions.assertInstanceOf(COSName.class, metadata.getItem(COSName.getPDFName("Mail_Subtype")));
    }
  }

  static void assertPageStructureBindings(PDDocument document) throws Exception {
    COSDictionary tree = (COSDictionary) document.getDocumentCatalog().getCOSObject().getDictionaryObject(COSName.STRUCT_TREE_ROOT);
    COSDictionary parentTree = (COSDictionary) tree.getDictionaryObject(COSName.getPDFName("ParentTree"));
    COSArray numbers = (COSArray) parentTree.getDictionaryObject(COSName.NUMS);
    Assertions.assertTrue(numbers.size() >= document.getNumberOfPages() * 2);
    java.util.Map<Integer, COSBase> entries = new java.util.HashMap<>();
    for (int position = 0; position < numbers.size(); position += 2) {
      int key = ((org.apache.pdfbox.cos.COSNumber) numbers.getObject(position)).intValue();
      Assertions.assertNull(entries.put(key, numbers.getObject(position + 1)));
    }
    for (int index = 0; index < document.getNumberOfPages(); index++) {
      PDPage page = document.getPage(index);
      int parentKey = page.getCOSObject().getInt(COSName.getPDFName("StructParents"), -1);
      Assertions.assertTrue(entries.containsKey(parentKey));
      try (InputStream input = page.getContents()) {
        Assertions.assertTrue(new String(input.readAllBytes(), StandardCharsets.ISO_8859_1).contains("/MCID"));
      }
      COSArray parents = Assertions.assertInstanceOf(COSArray.class, entries.get(parentKey));
      Assertions.assertTrue(parents.size() > 0);
      COSDictionary contentSet = Assertions.assertInstanceOf(COSDictionary.class, parents.getObject(0));
      int depth = 0;
      while (!"Mail_ContentSet".equals(contentSet.getNameAsString(COSName.S)) && depth++ < 128) {
        contentSet = Assertions.assertInstanceOf(COSDictionary.class, contentSet.getDictionaryObject(COSName.P));
      }
      Assertions.assertEquals("Mail_ContentSet", contentSet.getNameAsString(COSName.S));
      COSDictionary namespace = (COSDictionary) contentSet.getDictionaryObject(COSName.getPDFName("NS"));
      Assertions.assertNotNull(namespace.getDictionaryObject(COSName.getPDFName("RoleMapNS")));
    }
  }
}
