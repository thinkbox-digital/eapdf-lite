package eapdf;

import com.google.gson.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.verapdf.gf.model.impl.containers.StaticContainers;
import org.verapdf.pdfa.flavours.PDFAFlavour;
import static org.junit.jupiter.api.Assertions.*;

class ValidationResourceTests {
    @TempDir Path temporary;
    @Test void indexedBodyMatchesPreserveConflictingAndDuplicateIdentities() {
        var identities = new ArrayList<AttachmentChecks.Identity>();
        for (String guid : List.of("", "a", "b")) for (String id : List.of("", "<x@EXAMPLE.org>", "<x@example.org>", "y@example.org"))
            identities.add(new AttachmentChecks.Identity(guid, id));
        var bodies = new ArrayList<AttachmentChecks.Body>();
        for (int repeat = 0; repeat < 2; repeat++) for (var id : identities)
            bodies.add(new AttachmentChecks.Body(new org.apache.pdfbox.cos.COSDictionary(), List.of(), id));
        var index = new AttachmentChecks.BodyIndex(bodies);
        for (var query : identities) {
            var expected = bodies.stream().filter(body -> query.matches(body.identity())).toList();
            var actual = index.matching(query);
            assertEquals(expected.size(), actual.size());
            for (int i = 0; i < expected.size(); i++) assertSame(expected.get(i), actual.get(i));
        }
    }
    @Test void upstreamValidationReleasesDocumentAndModelCaches() {
        Path sample = Path.of("..", "examples", "eml", "eml_Agenda_and_timeline_slides_1s.pdf").toAbsolutePath().normalize();
        var result = JavaValidation.pdfa(sample, null);
        assertEquals("PASS", result.get("status").getAsString());
        assertNull(org.verapdf.tools.StaticResources.getDocument(), "Closed parser must not retain the archive");
        assertTrue(StaticContainers.getCachedGlyphs().isEmpty());
        var fonts = org.verapdf.tools.StaticResources.getCachedFonts();
        assertTrue(fonts == null || fonts.isEmpty(), "The calling thread must not retain upstream fonts");
    }
    @Test @SuppressWarnings("unchecked")
    void completedUpstreamPassesDoNotRetainRuleContextsOnTheCallingThread() throws Exception {
        var field = org.verapdf.pdfa.validation.validators.JavaScriptEvaluator.class.getDeclaredField("context");
        field.setAccessible(true);
        var context = (ThreadLocal<Object>) field.get(null);
        Object previous = context.get();
        context.remove();
        try {
            Path sample = Path.of("..", "examples", "eml", "eml_Agenda_and_timeline_slides_1s.pdf").toAbsolutePath().normalize();
            assertEquals("PASS", JavaValidation.pdfa(sample, null).get("status").getAsString());
            assertNull(context.get(), "A closed upstream pass must not retain its rule context for the EA-PDF stage");
            assertEquals("PASS", JavaValidation.iso(sample).get("status").getAsString());
            assertNull(context.get(), "The ISO pass must release its thread-local rule context too");
        } finally {
            if (previous == null) context.remove(); else context.set(previous);
        }
    }
    @Test void streamedReportRetainsAllFindingsAndMatchesInMemorySummary() throws Exception {
        Path output = temporary.resolve("report.json");
        try (var memory = new JavaValidation.Report("input.pdf"); var disk = new JavaValidation.Report("input.pdf", true)) {
            JsonObject pdfa = JsonParser.parseString("{\"status\":\"PASS\"}").getAsJsonObject();
            memory.pdfa = pdfa; disk.pdfa = pdfa;
            for (int i = 0; i < 3000; i++) for (var report : List.of(memory, disk))
                report.add(i % 100 == 0 ? "FAIL" : "PASS", "EMBEDDED_STREAM", "Preserved payload", Map.of("file", "email-" + i));
            disk.write(output);
            assertEquals(memory.result(), JsonParser.parseString(Files.readString(output)));
            assertTrue(disk.items.isEmpty(), "The CLI must not retain the complete findings tree");
            var brief = JsonParser.parseString(Files.readString(output.resolveSibling("report.json.summary.json"))).getAsJsonObject();
            assertEquals(30, brief.getAsJsonObject("summary").get("fail").getAsInt());
            assertEquals("EMBEDDED_STREAM", brief.getAsJsonArray("failure_codes").get(0).getAsString());
        }
    }
    @Test void storedMimeSlicesKeepHeadersAndMultipartBodiesAfterTheirInputCloses() throws Exception {
        byte[] first = ("Subject: first\r\nContent-Type: multipart/mixed; boundary=b\r\n\r\n--b\r\nContent-Type: text/plain\r\n\r\nbody\r\n--b\r\nContent-Type: application/octet-stream\r\nContent-Transfer-Encoding: base64\r\nContent-Disposition: attachment; filename=data.bin\r\n\r\nAAECAw==\r\n--b--\r\n").getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        byte[] second = "Subject: second\r\n\r\nseparate".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        var a = new EaPdfChecks.Embedded("", new org.apache.pdfbox.cos.COSDictionary(), null, first, "a.eml", "message/rfc822", "", "Source");
        var b = new EaPdfChecks.Embedded("", new org.apache.pdfbox.cos.COSDictionary(), null, second, "b.eml", "message/rfc822", "", "Source");
        try (var store = new MimeSourceStore(List.of(a, b))) {
            jakarta.mail.internet.MimeMessage message;
            try (var input = store.open(a)) { message = new jakarta.mail.internet.MimeMessage(jakarta.mail.Session.getInstance(new Properties()), input); }
            assertEquals("first", message.getSubject());
            var parts = (jakarta.mail.Multipart)message.getContent();
            assertEquals("body", parts.getBodyPart(0).getContent());
            assertArrayEquals(new byte[]{0,1,2,3}, parts.getBodyPart(1).getInputStream().readAllBytes());
            try (var input = store.open(b)) {
                input.mark(100); assertEquals('S', input.read()); input.reset();
                assertArrayEquals(second, input.readAllBytes()); assertEquals(-1, input.read());
            }
        }
    }
    @Test void engineFailureIsIncompleteAndKeepsTheCompletedPdfaOutcome() throws Exception {
        Path output = temporary.resolve("stopped.json");
        try (var session = new ValidationSession(temporary.resolve("email.pdf"), output)) {
            ValidationSession.pdfaFinished(JsonParser.parseString("{\"status\":\"PASS\",\"ran\":true,\"compliant\":true,\"profile_allowed\":true}").getAsJsonObject());
            session.failed("Stopped during EA-PDF checking.");
        }
        var parsed = JsonParser.parseString(Files.readString(output)).getAsJsonObject();
        var summary = parsed.getAsJsonObject("summary");
        assertEquals("INCOMPLETE", summary.get("status").getAsString());
        assertEquals("PASS", summary.get("pdfa_status").getAsString());
        assertTrue(parsed.getAsJsonArray("items").asList().stream().map(JsonElement::getAsJsonObject)
            .anyMatch(item -> "VALIDATION_INCOMPLETE".equals(item.get("code").getAsString())
                && "Stopped during EA-PDF checking.".equals(item.get("message").getAsString())));
        try (var report = new JavaValidation.Report("input.pdf")) {
            report.pdfa = JsonParser.parseString("{\"status\":\"ERROR\"}").getAsJsonObject();
            assertEquals("INCOMPLETE", report.summary().get("status").getAsString());
        }
    }
    @Test void supervisorFailureKeepsUpstreamCheckpointsButANewRunResetsThem() throws Exception {
        Path input = Files.writeString(temporary.resolve("email.pdf"), "fixture"), output = temporary.resolve("checkpoint.json");
        try (var session = new ValidationSession(input, output)) {
            ValidationSession.pdfaFinished(JsonParser.parseString("{\"status\":\"PASS\"}").getAsJsonObject());
            ValidationSession.isoFinished(JsonParser.parseString("{\"status\":\"FAIL\"}").getAsJsonObject());
            session.failed("Worker stopped.");
        }
        try (var session = new ValidationSession(input, output, true)) { session.failed("Supervisor observed worker termination."); }
        var recovered = JsonParser.parseString(Files.readString(output)).getAsJsonObject();
        assertEquals("PASS", recovered.getAsJsonObject("pdfa").get("status").getAsString());
        assertEquals("FAIL", recovered.getAsJsonObject("summary").get("status").getAsString());
        try (var session = new ValidationSession(input, output)) { session.failed("Starting a separate run."); }
        var fresh = JsonParser.parseString(Files.readString(output)).getAsJsonObject();
        assertEquals("NOT_RUN", fresh.getAsJsonObject("pdfa").get("status").getAsString());
    }
    @Test void childBudgetsAreCheckedBeforeAdmissionAndSupportOrdinaryLargeCollections() {
        var children = new JavaValidation.Children();
        assertTrue(children.accepts(300L * 1024 * 1024, 0));
        for (int i = 0; i < 300; i++) assertTrue(children.accepts(1, 0));
        long before = children.bytes; int count = children.count;
        assertFalse(children.accepts(Long.MAX_VALUE, 0));
        assertEquals(before, children.bytes); assertEquals(count, children.count);
        assertTrue(children.accepts(1, 1000));
    }
    @Test void glyphInventoryIsNotEvictedForPdfa1() {
        StaticContainers.clearAllContainers();
        try {
            StaticContainers.setFlavour(List.of(PDFAFlavour.PDFA_1_A));
            var original = StaticContainers.getCachedGlyphs(); BoundedGlyphCache.install();
            assertSame(original, StaticContainers.getCachedGlyphs());
            StaticContainers.setFlavour(List.of(PDFAFlavour.PDFA_4_F)); BoundedGlyphCache.install();
            assertInstanceOf(BoundedGlyphCache.class, StaticContainers.getCachedGlyphs());
        } finally { StaticContainers.clearAllContainers(); }
    }
}
