package eapdf;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.JsonParser;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;
import java.util.zip.CRC32;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Runs after shading during mvn verify, against the distributable itself. */
class ExecutableJarPackagingIT {
  @TempDir Path temporary;

  @Test void executableArchiveHasACompleteManifestResourcesAndDependencies() throws Exception {
    Path path=Path.of(System.getProperty("validator.packagedJar"));
    try(var jar=new JarFile(path.toFile())) {
      var manifest=jar.getManifest();assertNotNull(manifest,"META-INF/MANIFEST.MF is required");
      assertEquals(System.getProperty("validator.mainClass"),manifest.getMainAttributes().getValue(Attributes.Name.MAIN_CLASS));
      assertNull(manifest.getMainAttributes().getValue(Attributes.Name.CLASS_PATH),"The distributable must not require external JARs");
      Set<String> names=new HashSet<>();byte[] buffer=new byte[65536];
      for(var entries=jar.entries();entries.hasMoreElements();) {
        var entry=entries.nextElement();assertTrue(names.add(entry.getName()),"Duplicate ZIP entry: "+entry.getName());
        assertFalse(entry.getName().startsWith("/")||Arrays.asList(entry.getName().split("/")).contains(".."));
        if(entry.isDirectory())continue;
        var crc=new CRC32();long length=0;
        try(var input=jar.getInputStream(entry)){for(int count;(count=input.read(buffer))!=-1;){crc.update(buffer,0,count);length+=count;}}
        assertEquals(entry.getSize(),length,entry.getName());assertEquals(entry.getCrc(),crc.getValue(),entry.getName());
      }
      for(String required:List.of("eapdf/JavaValidation.class",
          "com/google/gson/Gson.class","org/apache/pdfbox/Loader.class","org/verapdf/pdfa/Foundries.class",
          "jakarta/mail/internet/MimeMessage.class","org/eclipse/angus/mail/util/MailStreamProvider.class",
          "validator/validator_rule_text.json","validator/config/verapdf/ISO-32005.xml",
          "META-INF/services/jakarta.mail.util.StreamProvider"))assertTrue(names.contains(required),"Missing runtime entry: "+required);
      assertFalse(names.stream().anyMatch(name -> name.startsWith("eapdf/ValidatorGui")),
          "The CLI distributable must not contain the validator GUI");
      try(var input=new java.io.DataInputStream(jar.getInputStream(jar.getJarEntry("eapdf/JavaValidation.class")))) {
        assertEquals(0xcafebabe,input.readInt());input.readUnsignedShort();assertEquals(65,input.readUnsignedShort(),"Java 21 bytecode");
      }
    }
  }

  @Test void javaJarShowsUsageWithoutArguments() throws Exception {
    Path log = temporary.resolve("usage.log");
    assertEquals(1, launch(log), () -> readLog(log));
    assertEquals("Usage: java -jar eapdf-validator-<version>.jar <input.pdf> <report.json>", Files.readString(log).strip());
  }

  @Test void javaJarValidatesAPdfHeadlessly() throws Exception {
    Path input = temporary.resolve("input.pdf");
    Files.copy(Path.of("..", "examples", "eml", "eml_Agenda_and_timeline_slides_1s.pdf"), input);
    Path reportPath = temporary.resolve("report.json");
    Path log = temporary.resolve("validation.log");

    assertEquals(0, launch(log, input.toString(), reportPath.toString()), () -> readLog(log));
    var report = JsonParser.parseString(Files.readString(reportPath)).getAsJsonObject();
    var summary = report.getAsJsonObject("summary");
    assertEquals("PASS", summary.get("status").getAsString());
    assertEquals("PASS", summary.get("pdfa_status").getAsString());
    assertEquals("1s", summary.get("verification_spec").getAsString());
  }

  private int launch(Path log, String... args) throws Exception {
    Path copy = temporary.resolve("eapdf-validator.jar");
    Files.copy(Path.of(System.getProperty("validator.packagedJar")), copy);
    var command = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
        "-Djava.awt.headless=true", "-Duser.home=" + temporary, "-jar", copy.toString()));
    command.addAll(List.of(args));
    var builder = new ProcessBuilder(command);
    builder.directory(temporary.toFile()).redirectErrorStream(true).redirectOutput(log.toFile());
    for (String key : List.of("CLASSPATH", "JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "EAPDF_CONFIG")) builder.environment().remove(key);
    Process process = builder.start();
    try {
      assertTrue(process.waitFor(60, java.util.concurrent.TimeUnit.SECONDS), "Packaged entry point did not respond");
      return process.exitValue();
    } finally {
      if (process.isAlive()) process.destroyForcibly().waitFor();
    }
  }

  private static String readLog(Path log) {
    try { return Files.readString(log); }
    catch (java.io.IOException error) { throw new java.io.UncheckedIOException(error); }
  }
}
