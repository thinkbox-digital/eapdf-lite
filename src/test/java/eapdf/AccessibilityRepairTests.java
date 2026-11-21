package eapdf;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.*;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class AccessibilityRepairTests {
  @TempDir Path temp;

  @Test
  void legacyHeadingsAndOrphanListItemsKeepValidStructureAndAllText() throws Exception {
    var record = EmlReader.readRfc822Bytes(("From: a@example.org\r\nTo: b@example.org\r\n"
        + "Date: Fri, 16 Oct 2026 12:00:00 +0000\r\nSubject: Legacy structure\r\n"
        + "Content-Type: text/html; charset=UTF-8\r\n\r\n"
        + "<h2>Heading<p></h2><table><tr><td><div><li>First item</li>"
        + "<li>Second item</li></div><ul><li>Normal item</li></ul></td></tr></table>")
        .getBytes(StandardCharsets.UTF_8), "structure.eml", "structure.eml", "", List.of(), false);
    byte[] bytes = PdfMailWriter.buildSingleMessagePdf(temp.resolve("structure.pdf"), record,
        TestSupport.fontPath(), TestSupport.iccPath(), false, temp, 1, "s").pdfBytes;
    try (var pdf = Loader.loadPDF(bytes)) {
      String text = new PDFTextStripper().getText(pdf);
      for (String expected : List.of("Heading", "First item", "Second item", "Normal item")) assertTrue(text.contains(expected));
      int[] counts = new int[2];
      checkHeadingLists(pdf.getDocumentCatalog().getStructureTreeRoot().getCOSObject().getDictionaryObject(COSName.K), "", counts);
      assertEquals(2, counts[0], "One recovered list and one existing list");
      assertEquals(3, counts[1], "Every list item is retained");
    }
  }

  @Test
  void linkedInlineImageKeepsItsFigureAndLinkWithoutAParagraphInsideTheLink() throws Exception {
    String image = java.util.Base64.getEncoder().encodeToString(CidUrlTests.image());
    var record = EmlReader.readRfc822Bytes(("From: a@example.org\r\nTo: b@example.org\r\n"
        + "Date: Fri, 16 Oct 2026 12:00:00 +0000\r\nSubject: Linked image\r\n"
        + "Content-Type: text/html; charset=UTF-8\r\n\r\n"
        + "<p>Before <a href='https://example.org/image'><span><img alt='Four colors' "
        + "width='24' height='24' src='data:image/png;base64," + image + "'></span></a> after</p>")
        .getBytes(StandardCharsets.UTF_8), "linked.eml", "linked.eml", "", List.of(), false);
    byte[] bytes = PdfMailWriter.buildSingleMessagePdf(temp.resolve("eml_linked_1s.pdf"), record,
        TestSupport.fontPath(), TestSupport.iccPath(), false, temp, 1, "s").pdfBytes;
    try (var pdf = Loader.loadPDF(bytes)) {
      var root = pdf.getDocumentCatalog().getStructureTreeRoot().getCOSObject();
      int[] counts = new int[2];
      checkLinkedFigure(root.getDictionaryObject(COSName.K), false, counts);
      assertArrayEquals(new int[] {1, 1}, counts);
      assertTrue(pdf.getPage(0).getAnnotations().stream().anyMatch(annotation ->
          annotation instanceof PDAnnotationLink link
              && link.getAction() instanceof org.apache.pdfbox.pdmodel.interactive.action.PDActionURI action
              && "https://example.org/image".equals(action.getURI())));
    }
  }

  private void checkLinkedFigure(COSBase value, boolean inLink, int[] counts) {
    if (value instanceof COSObject ref) value = ref.getObject();
    if (value instanceof COSArray array) {
      for (COSBase child : array) checkLinkedFigure(child, inLink, counts);
    } else if (value instanceof COSDictionary element && element.containsKey(COSName.S)) {
      String role = element.getNameAsString(COSName.S);
      if ("Link".equals(role)) counts[0]++;
      if ("Figure".equals(role) && "Four colors".equals(element.getString(COSName.ALT))) counts[1]++;
      if (inLink) assertNotEquals("P", role);
      checkLinkedFigure(element.getDictionaryObject(COSName.K), inLink || "Link".equals(role), counts);
    }
  }

  private void checkHeadingLists(COSBase value, String parent, int[] counts) {
    if (value instanceof COSObject ref) value = ref.getObject();
    if (value instanceof COSArray array) {
      for (COSBase child : array) checkHeadingLists(child, parent, counts);
    } else if (value instanceof COSDictionary element && element.containsKey(COSName.S)) {
      String role = element.getNameAsString(COSName.S);
      if ("LI".equals(role)) { assertEquals("L", parent); counts[1]++; }
      if ("L".equals(role)) counts[0]++;
      if (parent.matches("H[1-6]?")) assertNotEquals("P", role);
      checkHeadingLists(element.getDictionaryObject(COSName.K), "Div".equals(role) ? parent : role, counts);
    }
  }

  @Test
  void linksKeepTheirVisibleTextAndStructureOwners() throws Exception {
    String html = "<p>Bonjour <a href='https://example.org'>visiter</a> "
        + "<a href='https://example.org/help' title='Aide du site'>aide</a></p>";
    EmailRecord record = EmlReader.readRfc822Bytes(("From: a@example.org\r\nTo: b@example.org\r\n"
        + "Date: Fri, 16 Oct 2026 12:00:00 +0000\r\nSubject: Links\r\n"
        + "Content-Type: text/html; charset=UTF-8\r\n\r\n" + html).getBytes(StandardCharsets.UTF_8),
        "links.eml", "links.eml", "", List.of(), false);
    byte[] bytes = PdfMailWriter.buildSingleMessagePdf(temp.resolve("links.pdf"), record,
        TestSupport.fontPath(), TestSupport.iccPath(), false, temp, 1, "s").pdfBytes;
    try (var pdf = Loader.loadPDF(bytes)) {
      String text = new PDFTextStripper().getText(pdf).replaceAll("\\s+", " ");
      assertTrue(text.contains("Bonjour visiter aide"), text);
      var tree = pdf.getDocumentCatalog().getStructureTreeRoot().getCOSObject();
      var numbers = tree.getCOSDictionary(COSName.PARENT_TREE).getCOSArray(COSName.NUMS);
      int checked = 0;
      for (var page : pdf.getPages()) for (var annotation : page.getAnnotations()) {
        if (!(annotation instanceof PDAnnotationLink)) continue;
        int key = annotation.getCOSObject().getInt(COSName.STRUCT_PARENT);
        COSDictionary owner = null;
        for (int i = 0; i < numbers.size(); i += 2)
          if (((COSNumber)numbers.getObject(i)).intValue() == key) owner = (COSDictionary)numbers.getObject(i + 1);
        assertNotNull(owner);
        assertEquals("Link", owner.getNameAsString(COSName.S));
        checked++;
      }
      assertEquals(2, checked);
    }
  }

  @Test
  void emptyHeaderAndBodyCellsKeepTheirTableColumns() throws Exception {
    var record = EmlReader.readRfc822Bytes(("From: a@example.org\r\n"
        + "Date: Fri, 16 Oct 2026 12:00:00 +0000\r\nSubject: Blank cells\r\n"
        + "Content-Type: text/html; charset=UTF-8\r\n\r\n"
        + "<table><tr><th>A</th><th>B</th></tr><tr><td></td><td>present</td></tr></table>")
        .getBytes(StandardCharsets.UTF_8), "blank.eml", "blank.eml", "", List.of(), false);
    byte[] bytes = PdfMailWriter.buildSingleMessagePdf(temp.resolve("blank.pdf"), record,
        TestSupport.fontPath(), TestSupport.iccPath(), false, temp, 1, "s").pdfBytes;
    try (var pdf = Loader.loadPDF(bytes)) {
      assertTrue(checkTwoColumnRows(pdf.getDocumentCatalog().getStructureTreeRoot().getCOSObject()) >= 6);
      assertTrue(new PDFTextStripper().getText(pdf).contains("present"));
    }
  }

  private int checkTwoColumnRows(COSBase value) {
    if (value instanceof COSObject reference) value = reference.getObject();
    if (value instanceof COSArray array) {
      int rows = 0;
      for (var child : array) rows += checkTwoColumnRows(child);
      return rows;
    }
    if (!(value instanceof COSDictionary dictionary)) return 0;
    var children = dictionary.getDictionaryObject(COSName.K);
    boolean row = java.util.Set.of("TR", "Mail_Field").contains(dictionary.getNameAsString(COSName.S, ""));
    if (row) assertEquals(2, assertInstanceOf(COSArray.class, children).size());
    return (row ? 1 : 0) + checkTwoColumnRows(children);
  }

}
