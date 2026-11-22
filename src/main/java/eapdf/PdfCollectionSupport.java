package eapdf;
import eapdf.PdfAttachmentSupport.*;


import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSInteger;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSObject;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentCatalog;
import org.apache.pdfbox.pdmodel.PDDocumentNameDictionary;
import org.apache.pdfbox.pdmodel.PDEmbeddedFilesNameTreeNode;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.common.filespecification.PDComplexFileSpecification;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionGoTo;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageFitWidthDestination;

final class PdfCollectionSupport {
  private PdfCollectionSupport() {}



  // Collection wrapper pages describe the whole archive, not an individual email (§7.6).
  static PDDocument buildCollectionStartPage(List<EmailRecord> records, String profile,
      String fontPath, boolean debug) throws Exception {
    long attachments = records.stream().mapToLong(r -> Math.max(r.attachmentCount, 0)).sum();
    int folders = buildFolderSections(records).size();
    long incomplete = records.stream().filter(r -> !r.sourceProblems.isEmpty()).count();
    StringBuilder html = new StringBuilder("<!DOCTYPE html><html><head><meta charset=\"utf-8\"/>");
    html.append("<style>@page { size: letter; margin: 0.7in; }")
        .append("html, body, body * { font-family: ArchiveFont !important; }")
        .append("body { font-size: 11pt; color: #222; line-height: 1.45; }")
        .append("h1 { font-size: 26pt; margin: 0 0 8px; } h2 { font-size: 14pt; margin-top: 24px; }")
        .append(".profile { color: #555; margin-bottom: 24px; }")
        .append("table { width: 100%; border-collapse: collapse; margin: 20px 0; }")
        .append("th, td { text-align: left; padding: 10px; border-bottom: 1px solid #ccc; }")
        .append("th { width: 65%; } .warning { border: 2px solid #8b6500; padding: 10px; margin: 16px 0; }")
        .append("</style></head><body><h1>Email Collection</h1><p class=\"profile\">EA-PDF 1")
        .append(Common.escapeHtml(profile)).append(" — ")
        .append("ci".equals(profile) ? "Isolated collection" : "Email archive")
        .append("</p><p>This PDF contains a collection of email messages stored as embedded PDF files.</p>")
        .append("<table><tr><th scope=\"row\">Messages</th><td>").append(records.size())
        .append("</td></tr><tr><th scope=\"row\">Folders</th><td>").append(folders)
        .append("</td></tr><tr><th scope=\"row\">Attachments</th><td>").append(attachments)
        .append("</td></tr></table>")
        .append("<p>Messages and their attachments are counted once per message, even when a message appears in more than one folder.</p>")
        .append("<h2>Open the collection</h2><p>In the EA-PDF viewer, select a message in the Messages pane. ")
        .append("In another PDF reader, open the embedded message PDFs from its collection or attachments panel.</p>");
    if ("ci".equals(profile)) html.append("<h2>About this copy</h2><p>This isolated collection retains message renderings, folders and attachment information. ")
        .append("Source email files and attachment contents are omitted. The attachment count describes the messages, not downloadable attachment contents.</p>");
    String warning = Common.nz(EapdfConfig.get().validationWarning);
    if (!warning.isBlank()) {
      System.err.println("warning: VALIDATION WARNING BANNER: " + warning);
      html.append("<div class=\"warning\"><p><strong>WARNING — Validation requires review</strong></p><p>")
          .append(Common.escapeHtml(warning)).append("</p></div>");
    }
    if (incomplete > 0) html.append("<div class=\"warning\"><p><strong>INCOMPLETE SOURCE — conversion warnings</strong></p><p>")
        .append(incomplete).append(" message(s) have source-data warnings. Review the warnings in the embedded messages.</p></div>");
    html.append("</body></html>");
    byte[] rendered = PdfMailWriter.renderHtmlPdf(html.toString(), PdfMailWriter.ROOT_URI(), fontPath, new EmailRecord(), debug);
    PDDocument document = Loader.loadPDF(new org.apache.pdfbox.io.RandomAccessReadBuffer(rendered),
        org.apache.pdfbox.io.IOUtils.createTempFileOnlyStreamCache());
    try { ProcessingLimits.accountPages(document.getNumberOfPages()); return document; }
    catch (Exception error) { document.close(); throw error; }
  }

  static List<String> collectionGroupNames(EmailRecord record) {
    LinkedHashSet<String> out = new LinkedHashSet<>();
    String raw = Common.nz(record == null ? "" : record.folderPath);
    List<String> memberships = record != null && !record.folderMemberships.isEmpty()
        ? record.folderMemberships : List.of(raw.split(","));
    if (!memberships.isEmpty()) {
      for (String part : memberships) {
        String label = part == null ? "" : part.trim();
        if (label.isBlank()) continue;
        if (shouldIgnoreCollectionLabel(label)) continue;
        out.add(Common.stripInvalidXml(label));
      }
    }
    if (out.isEmpty()) out.add(Common.safeCollectionLabel("Messages", "Messages"));
    return new ArrayList<>(out);
  }

  static List<EmailRecord> sortRecordsChronologically(List<EmailRecord> records) {
    List<EmailRecord> out = new ArrayList<>(records == null ? List.of() : records);
    out.sort((a, b) -> {
      int cmp = Common.messageInstant(a).compareTo(Common.messageInstant(b));
      if (cmp != 0) return cmp;
      cmp = Common.nz(a == null ? "" : a.subject).compareToIgnoreCase(Common.nz(b == null ? "" : b.subject));
      if (cmp != 0) return cmp;
      return Common.nz(a == null ? "" : a.messageId).compareToIgnoreCase(Common.nz(b == null ? "" : b.messageId));
    });
    return out;
  }

  static List<EmailRecord> sortRecordsReverseChronologically(List<EmailRecord> records) {
    List<EmailRecord> out = sortRecordsChronologically(records);
    Collections.reverse(out);
    return out;
  }

  static List<PdfCollectionSupport.FolderSection> buildFolderSections(List<EmailRecord> records) {
    Map<String, List<EmailRecord>> groups = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    long memberships = 0;
    for (EmailRecord record : records) {
      Set<String> assigned = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
      for (String groupName : collectionGroupNames(record)) {
        if (!assigned.add(groupName)) continue;
        ProcessingLimits.checkCount(++memberships);
        groups.computeIfAbsent(groupName, k -> new ArrayList<>()).add(record);
      }
    }
    List<PdfCollectionSupport.FolderSection> sections = new ArrayList<>();
    for (Map.Entry<String, List<EmailRecord>> entry : groups.entrySet()) {
      List<EmailRecord> groupRecords = sortRecordsChronologically(entry.getValue());
      sections.add(new FolderSection(entry.getKey(), groupRecords));
    }
    return sections;
  }

  static List<PdfCollectionSupport.CollectionFolder> buildCollectionFolders(List<PdfCollectionSupport.FolderSection> sections) {
    List<PdfCollectionSupport.CollectionFolder> folders = new ArrayList<>();
    Map<String, String> paths = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    Map<String, Set<String>> siblingNames = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    int folderId = 1;
    for (PdfCollectionSupport.FolderSection section : sections) {
      String rawParent = "";
      String displayParent = "";
      for (String component : section.folderName.split("/", -1)) {
        String rawPath = rawParent + "/" + component;
        String displayPath = paths.get(rawPath);
        if (displayPath == null) {
          String base = Common.safeCollectionLabel(component, "Messages");
          String display = base;
          Set<String> names = siblingNames.computeIfAbsent(rawParent, key -> new LinkedHashSet<>());
          int suffix = 2;
          while (!names.add(Common.filenameCaseKey(display))) display = base + " (" + suffix++ + ")";
          displayPath = displayParent.isBlank() ? display : displayParent + "/" + display;
          paths.put(rawPath, displayPath);
        }
        rawParent = rawPath;
        displayParent = displayPath;
      }
      folders.add(new PdfCollectionSupport.CollectionFolder(displayParent, folderId++, section.records.size()));
    }
    return folders;
  }

  private record PendingCollectionMember(PdfCollectionSupport.CollectionFolder folder,
      Future<PdfCollectionSupport.CollectionMember> future, boolean alias) {}

  static List<PdfCollectionSupport.CollectionMember> buildFolderCollectionMembers(List<PdfCollectionSupport.FolderSection> sections, List<PdfCollectionSupport.CollectionFolder> folders, String fontPath, String iccPath, boolean debug, Path debugDir, String collectionProfile, PayloadStore memberStore) throws Exception {
    List<PdfCollectionSupport.CollectionMember> members = new ArrayList<>();
    Map<EmailRecord, Future<PdfCollectionSupport.CollectionMember>> rendered = new IdentityHashMap<>();
    int parallelism = PdfMailWriter.renderParallelism(Integer.MAX_VALUE);
    var pending = new java.util.ArrayDeque<PendingCollectionMember>();
    ExecutorService pool = Common.newWorkers(parallelism);
    int globalIndex = 0;
    try {
      for (int folderIndex = 0; folderIndex < folders.size(); folderIndex++) {
        PdfCollectionSupport.CollectionFolder folder = folders.get(folderIndex);
        int folderOrder = 0;
        for (EmailRecord record : sortRecordsReverseChronologically(sections.get(folderIndex).records)) {
          ProcessingLimits.checkCancelled();
          final int index = ++globalIndex, order = ++folderOrder;
          ProcessingLimits.checkCount(index);
          Future<PdfCollectionSupport.CollectionMember> future = rendered.get(record);
          boolean alias = future != null;
          if (!alias) {
            future = pool.submit(() -> buildFolderCollectionMember(record, folder, index, order,
                fontPath, iccPath, debug, debugDir, collectionProfile, memberStore));
            rendered.put(record, future);
          }
          pending.addLast(new PendingCollectionMember(folder, future, alias));
          // Bound queued work as well as active renderers; retain output order and shared folder members.
          if (pending.size() >= parallelism) finishCollectionMember(pending.removeFirst(), members);
        }
      }
      while (!pending.isEmpty()) finishCollectionMember(pending.removeFirst(), members);
    } catch (ExecutionException error) {
      if (error.getCause() instanceof Exception cause) throw cause;
      throw new RuntimeException(error.getCause());
    } finally { Common.stopWorkers(pool); }
    return members;
  }

  private static PdfCollectionSupport.CollectionMember buildFolderCollectionMember(EmailRecord record,
      PdfCollectionSupport.CollectionFolder folder, int index, int order, String fontPath, String iccPath,
      boolean debug, Path debugDir, String collectionProfile, PayloadStore memberStore) throws Exception {
    String subject = collectionMemberSubject(record, order);
    String childProfile = "ci".equals(collectionProfile) ? "si" : "s";
    String filename = collectionMemberFilename(subject, childProfile, index);
    record.embedSourceMessage = !"ci".equals(collectionProfile);
    Path childPath = memberStore == null ? null : memberStore.createPdf();
    var built = PdfMailWriter.buildSingleMessagePdf(Path.of(filename), record, fontPath, iccPath,
        debug, debugDir.resolve("collection_child_" + index), 1, childProfile, childPath);
    var member = new PdfCollectionSupport.CollectionMember(folder.name, subject, childProfile, List.of(record),
        folder.id, order, filename, built.pdfBytes, Math.max(record.attachmentCount, 0), Common.nz(record.date));
    member.pdfPath = childPath;
    member.storedPdfSize = childPath == null ? built.pdfBytes.length : Common.checkedPayloadSize(Files.size(childPath));
    member.stage(memberStore);
    return member;
  }

  private static void finishCollectionMember(PendingCollectionMember pending,
      List<PdfCollectionSupport.CollectionMember> members) throws Exception {
    var member = Common.awaitWorker(pending.future());
    if (pending.alias()) {
      var alias = new PdfCollectionSupport.CollectionMember(pending.folder().name, member.subject,
          member.childProfile, member.records, pending.folder().id, member.orderIndex, member.filename,
          member.pdfBytes, member.attachmentCount, member.sentDisplay);
      alias.pdfPath = member.pdfPath;
      alias.storedPdfSize = member.storedPdfSize;
      member = alias;
    }
    pending.folder().totalSizeBytes += member.pdfSize();
    members.add(member);
    if (!pending.alias()) ConversionProgress.completed();
  }

  static void addTopLevelCollectionDictionary(PDDocument doc, List<PdfCollectionSupport.CollectionFolder> folders) throws IOException {
    COSDictionary collection = new COSDictionary();
    collection.setName(COSName.TYPE, "Collection");
    collection.setName(COSName.getPDFName("View"), "T");
    collection.setItem(COSName.getPDFName("Sort"), collectionSortDictionary("adobe:Order", "FileName"));


    COSDictionary schema = new COSDictionary();
    schema.setItem(COSName.getPDFName("FileName"), collectionSchemaField("Folder Name", "F"));
    schema.setItem(COSName.getPDFName("Messages"), collectionSchemaField("# of Messages", "N"));
    schema.setItem(COSName.getPDFName("adobe:Order"), collectionSchemaField("Order", "N"));
    schema.setItem(COSName.getPDFName("To"), collectionSchemaField("To", "S"));
    schema.setItem(COSName.getPDFName("From"), collectionSchemaField("From", "S"));
    schema.setItem(COSName.getPDFName("Sent"), collectionSchemaField("Sent", "D"));
    schema.setItem(COSName.getPDFName("Subject"), collectionSchemaField("Subject", "S"));
    schema.setItem(COSName.getPDFName("Message-ID"), collectionSchemaField("Message-ID", "S"));
    schema.setItem(COSName.getPDFName("Cc"), collectionSchemaField("Cc", "S"));
    schema.setItem(COSName.getPDFName("Bcc"), collectionSchemaField("Bcc", "S"));
    schema.setItem(COSName.getPDFName("Size"), collectionSchemaField("Size", "Size"));
    schema.setItem(COSName.getPDFName("Attachments"), collectionSchemaField("Attachments", "N"));
    collection.setItem(COSName.getPDFName("Schema"), schema);
    COSBase folderTree = buildCollectionFolderTree(folders);
    if (folderTree != null) collection.setItem(COSName.getPDFName("Folders"), folderTree);
    doc.getDocumentCatalog().getCOSObject().setItem(COSName.getPDFName("Collection"), collection);
  }

  static void addMessageCollectionDictionary(PDDocument doc) {
    COSDictionary collection = new COSDictionary();
    collection.setName(COSName.TYPE, "Collection");
    collection.setName(COSName.getPDFName("View"), "T");
    collection.setItem(COSName.getPDFName("Sort"), collectionSortDictionary("adobe:Order", "Subject"));

    COSDictionary schema = new COSDictionary();
    schema.setItem(COSName.getPDFName("adobe:Order"), collectionSchemaField("Order", "N"));
    schema.setItem(COSName.getPDFName("To"), collectionSchemaField("To", "S"));
    schema.setItem(COSName.getPDFName("From"), collectionSchemaField("From", "S"));
    schema.setItem(COSName.getPDFName("Sent"), collectionSchemaField("Sent", "D"));
    schema.setItem(COSName.getPDFName("Subject"), collectionSchemaField("Subject", "S"));
    schema.setItem(COSName.getPDFName("Folder"), collectionSchemaField("Folder", "S"));
    schema.setItem(COSName.getPDFName("Message-ID"), collectionSchemaField("Message-ID", "S"));
    schema.setItem(COSName.getPDFName("Cc"), collectionSchemaField("Cc", "S"));
    schema.setItem(COSName.getPDFName("Bcc"), collectionSchemaField("Bcc", "S"));
    schema.setItem(COSName.getPDFName("Attachments"), collectionSchemaField("Attachments", "N"));
    schema.setItem(COSName.getPDFName("Size"), collectionSchemaField("Size", "Size"));
    collection.setItem(COSName.getPDFName("Schema"), schema);
    doc.getDocumentCatalog().getCOSObject().setItem(COSName.getPDFName("Collection"), collection);
  }

  static EmailRecord buildCollectionFrontRecord(List<EmailRecord> records, String collectionProfile, List<PdfCollectionSupport.CollectionMember> members) {
    EmailRecord r = new EmailRecord();
    r.subject = "Email Collection";
    r.folderPath = "Collection";
    r.contentSetDescription = "Collection front matter";
    r.mailGuid = Common.safeFilename("collection-" + collectionProfile + "-" + members.size());
    r.messageId = "";
    r.archiveGlobalId = records.isEmpty() ? "" : Common.nz(records.get(0).archiveGlobalId);
    r.archiveEmails = new ArrayList<>();
    for (EmailRecord item : records) {
      for (String email : item.archiveEmails) {
        if (!r.archiveEmails.contains(email)) r.archiveEmails.add(email);
      }
    }
    return r;
  }

  static void addCollectionDPartStructure(PDDocument doc, List<EmailRecord> records, String collectionProfile) throws Exception {
    if (doc.getNumberOfPages() == 0) return;
    new PdfDocumentParts(doc).addFrontMatter(0, doc.getNumberOfPages() - 1, "Collection front matter");
  }

  static void addCollectionLogicalStructure(PDDocument doc) throws IOException {
    if (doc.getNumberOfPages() == 0) return;
    COSDictionary tree = new COSDictionary();
    tree.setName(COSName.TYPE, "StructTreeRoot");
    COSDictionary roles = new COSDictionary();
    roles.setName(COSName.getPDFName("Mail_ContentSet"), "Art");
    tree.setItem(COSName.ROLE_MAP, roles);
    COSDictionary root = PdfMailWriter.structElem("Document", tree, doc.getPage(0));
    COSDictionary content = PdfMailWriter.structElem("Mail_ContentSet", PdfDocumentParts.indirect(doc, root), doc.getPage(0));
    PdfMailWriter.addStringUserProperty(content, "Mail_ContentSetType", "FrontMatter");
    COSDictionary renderedTree = (COSDictionary) doc.getDocumentCatalog().getCOSObject().getDictionaryObject(COSName.STRUCT_TREE_ROOT);
    SemanticStructure.attach(doc, tree, content, renderedTree, 0, doc.getNumberOfPages() - 1);
    root.setItem(COSName.K, PdfDocumentParts.indirect(doc, content));
    tree.setItem(COSName.K, PdfDocumentParts.indirect(doc, root));
    PdfMailWriter.addLogicalStructureNamespaces(doc, tree);
    doc.getDocumentCatalog().getCOSObject().setItem(COSName.STRUCT_TREE_ROOT, PdfDocumentParts.indirect(doc, tree));
  }

  static void embedCollectionFiles(PDDocument doc, List<EmailRecord> records, List<PdfCollectionSupport.CollectionMember> members, boolean debug) throws Exception {
    PDDocumentCatalog cat = doc.getDocumentCatalog();
    COSDictionary catd = cat.getCOSObject();

    COSArray afArray = (COSArray)catd.getDictionaryObject(COSName.getPDFName("AF"));
    if (afArray == null) {
      afArray = new COSArray();
      catd.setItem(COSName.getPDFName("AF"), afArray);
    }

    PDDocumentNameDictionary names = cat.getNames();
    if (names == null) {
      names = new PDDocumentNameDictionary(cat);
      cat.setNames(names);
    }

    PDEmbeddedFilesNameTreeNode efs = names.getEmbeddedFiles();
    Map<String, PDComplexFileSpecification> nameMap = efs != null && efs.getNames() != null ? new LinkedHashMap<>(efs.getNames()) : new LinkedHashMap<>();
    if (efs == null) efs = new PDEmbeddedFilesNameTreeNode();
    // A large collection must not rescan every previous filename for each new embedded file.
    Set<String> normalizedNames = new java.util.HashSet<>();
    nameMap.keySet().forEach(name -> normalizedNames.add(Common.filenameCaseKey(name)));

    Map<EmailRecord, PDComplexFileSpecification> childFiles = new IdentityHashMap<>();
    for (PdfCollectionSupport.CollectionMember member : members) {
      AssociatedFile af = new AssociatedFile();
      af.description = "Contained EA-PDF message";
      String nameTreeKey = member.folderId > 0 ? ("<" + member.folderId + ">" + member.filename) : member.filename;
      EmailRecord message = member.records.size() == 1 ? member.records.get(0) : null;
      PDComplexFileSpecification existing = message == null ? null : childFiles.get(message);
      if (existing != null) {
        nameMap.put(PdfAttachmentSupport.reserveAttachmentKey(normalizedNames, nameTreeKey), existing);
        if (debug) System.out.println("[debug] collection member embedded: " + member.filename + " messages=" + member.records.size());
        continue;
      }
      COSDictionary collectionItem = topLevelCollectionItemDictionary(member);
      af.bytes = member.pdfBytes;
      af.dataPath = member.pdfPath;
      af.storedSize = member.pdfSize();
      String checksum = member.pdfPath == null ? Common.md5Hex(member.pdfBytes) : Common.digestHex(member.pdfPath, "MD5");
      PDComplexFileSpecification embedded = addEmbeddedPlan(doc, afArray, nameMap, normalizedNames,
          nameTreeKey, member.filename, "application/pdf", "Data", checksum, af, collectionItem);
      if (message != null) childFiles.put(message, embedded);
      if (debug) System.out.println("[debug] collection member embedded: " + member.filename + " messages=" + member.records.size());
    }

    for (PdfAttachmentSupport.ArchiveEmbeddedFilePlan plan : PdfMailWriter.buildArchiveEmbeddedFilePlans(records)) {
      if (!"Source".equals(plan.relationship) && !plan.file.sourceSetMember) continue;
      addEmbeddedPlan(doc, afArray, nameMap, normalizedNames, plan.filename, plan.filename, plan.mime, plan.relationship,
          plan.checksumMd5, plan.file, null);
    }

    efs.setNames(nameMap);
    names.setEmbeddedFiles(efs);
    cat.setNames(names);
  }

  static void repairSavedTopLevelCollection(Path pdfPath, List<PdfCollectionSupport.CollectionFolder> folders,
      List<PdfCollectionSupport.CollectionMember> members, boolean debug, PreservationInventory expected) throws Exception {
    Common.writePdfAtomically(pdfPath, temporary -> {
      try (PDDocument doc = IndexedPdfLoader.load(pdfPath, org.apache.pdfbox.io.IOUtils.createTempFileOnlyStreamCache())) {
        addTopLevelCollectionDictionary(doc, folders);
        rewriteTopLevelCollectionItems(doc, members);
        PdfFontMetadata.save(doc, temporary.toFile());
      }
    }, expected);
  }

  static String collectionMemberFilename(String groupName, String childProfile, int index) {
    String rawStem = Common.safeFileStem(Common.nz(groupName).isBlank() ? ("folder_" + index) : groupName);
    String stem = rawStem.replaceAll("\\.{2,}", "_").replaceAll("\\s+", " ").trim();
    if (stem.isBlank()) stem = "message";
    return String.format("%s_%06d_1%s.pdf", stem, index, childProfile);
  }

  static String collectionMemberSubject(EmailRecord record, int index) {
    String subject = Common.nz(record == null ? "" : record.subject).trim();
    return subject.isBlank() ? ("message " + index) : subject;
  }

  private static boolean shouldIgnoreCollectionLabel(String label) {
    String upper = Common.nz(label).trim().toUpperCase(Locale.ROOT);
    if (upper.isBlank()) return true;
    if (upper.equals("UNREAD")
        || upper.equals("IMPORTANT")
        || upper.equals("STARRED")
        || upper.equals("ALL_MAIL")
        || upper.equals("CHAT")
        || upper.equals("CATEGORY_FORUMS")
        || upper.equals("CATEGORY_UPDATES")
        || upper.equals("CATEGORY_PROMOTIONS")
        || upper.equals("CATEGORY_SOCIAL")
        || upper.equals("CATEGORY_PERSONAL")) return true;
    return upper.startsWith("CATEGORY_");
  }

  private static COSDictionary collectionSortDictionary(String... fieldNames) {
    COSDictionary sort = new COSDictionary();
    COSArray fields = new COSArray();
    for (String fieldName : fieldNames) {
      if (!Common.nz(fieldName).isBlank()) fields.add(COSName.getPDFName(fieldName));
    }
    sort.setItem(COSName.S, fields);
    return sort;
  }

  private static COSBase buildCollectionFolderTree(List<PdfCollectionSupport.CollectionFolder> folders) throws IOException {
    if (folders == null || folders.isEmpty()) return null;
    COSDictionary root = new COSDictionary();
    root.setName(COSName.TYPE, "Folder");
    root.setInt(COSName.getPDFName("ID"), 0);
    root.setString(COSName.NAME, Common.safeCollectionLabel("Collection", "Collection"));
    root.setDate(COSName.MOD_DATE, Calendar.getInstance(Locale.ROOT));
    long nextId = Math.max(0, folders.stream().mapToLong(folder -> folder.id).max().orElse(0)) + 1;
    COSObject rootObj = new COSObject(root);
    Map<String, PdfCollectionSupport.CollectionFolder> messageFolders = new LinkedHashMap<>();
    for (PdfCollectionSupport.CollectionFolder folder : folders) messageFolders.put(Common.filenameCaseKey(folder.name), folder);
    Map<String, COSObject> nodes = new LinkedHashMap<>();
    Map<String, COSDictionary> tails = new LinkedHashMap<>();
    int orderIndex = 0;
    for (PdfCollectionSupport.CollectionFolder folder : folders) {
      String path = "";
      String parentKey = "";
      COSObject parent = rootObj;
      for (String component : folder.name.split("/", -1)) {
        path = path.isEmpty() ? component : path + "/" + component;
        String key = Common.filenameCaseKey(path);
        COSObject nodeObj = nodes.get(key);
        if (nodeObj == null) {
          ProcessingLimits.checkCount((long) nodes.size() + 1);
          PdfCollectionSupport.CollectionFolder group = messageFolders.get(key);
          if (group == null && nextId > Integer.MAX_VALUE) throw new IOException("Collection folder IDs exhausted");
          COSDictionary node = new COSDictionary();
          node.setName(COSName.TYPE, "Folder");
          node.setInt(COSName.getPDFName("ID"), group == null ? (int) nextId++ : group.id);
          node.setString(COSName.NAME, component);
          node.setItem(COSName.PARENT, parent);
          Calendar now = Calendar.getInstance(Locale.ROOT);
          node.setDate(COSName.CREATION_DATE, now);
          node.setDate(COSName.MOD_DATE, now);
          int messages = group == null ? 0 : group.messageCount;
          long size = group == null ? 0 : group.totalSizeBytes;


          COSDictionary ci = new COSDictionary();
          ci.setFloat(COSName.getPDFName("adobe:Order"), ++orderIndex);
          ci.setInt(COSName.getPDFName("Messages"), messages);
          ci.setLong(COSName.getPDFName("Size"), size);
          node.setItem(COSName.getPDFName("CI"), ci);
          nodeObj = new COSObject(node);
          COSDictionary previous = tails.put(parentKey, node);
          if (previous == null) ((COSDictionary) parent.getObject()).setItem(COSName.getPDFName("Child"), nodeObj);
          else previous.setItem(COSName.NEXT, nodeObj);
          nodes.put(key, nodeObj);
        }
        parent = nodeObj;
        parentKey = key;
      }
    }
    COSArray free = new COSArray();
    if (nextId <= Integer.MAX_VALUE) {
      free.add(COSInteger.get(nextId));
      free.add(COSInteger.get(Integer.MAX_VALUE));
    }
    root.setItem(COSName.getPDFName("Free"), free);
    return rootObj;
  }

  private static COSDictionary collectionSchemaField(String displayName, String subtype) {
    COSDictionary field = new COSDictionary();
    field.setString(COSName.N, displayName);
    field.setName(COSName.getPDFName("Subtype"), subtype);
    field.setBoolean(COSName.getPDFName("E"), false);
    return field;
  }

  private static PDComplexFileSpecification addEmbeddedPlan(PDDocument doc, COSArray afArray, Map<String, PDComplexFileSpecification> nameMap, Set<String> normalizedNames,
                                      String nameTreeKey, String filename, String mime, String relationship, String checksumMd5, AssociatedFile file,
                                      COSDictionary collectionItem) throws IOException {
    if (!file.hasData() || file.size() == 0) return null;
    var prefix = java.util.regex.Pattern.compile("^<\\d+>").matcher(nameTreeKey);
    String folderPrefix = !nameTreeKey.equals(filename) && prefix.find() ? prefix.group() : "";
    String key = PdfAttachmentSupport.reserveAttachmentKey(normalizedNames,
        folderPrefix + Common.collectionFilename(nameTreeKey.substring(folderPrefix.length())));
    String storedName = key.substring(folderPrefix.length());
    PDComplexFileSpecification fs = PdfAttachmentSupport.embedAssociatedFile(
        doc, file, storedName, mime, relationship, checksumMd5);
    if (collectionItem != null) fs.getCOSObject().setItem(COSName.getPDFName("CI"), collectionItem);

    nameMap.put(key, fs);
    afArray.add(fs.getCOSObject());
    return fs;
  }

  static COSDictionary topLevelCollectionItemDictionary(PdfCollectionSupport.CollectionMember member) {
    COSDictionary ci = messageCollectionItemDictionary(member);
    ci.removeItem(COSName.getPDFName("Sent"));
    if (!member.records.isEmpty()) {
      EmailRecord record = member.records.get(0);
      // A zone-free XMP date must not acquire the host timezone in a PDF date.
      boolean localOnly = Common.parseMessageDate(record.sentXmp) == null && Common.validSentDate(record.sentXmp);
      Calendar sent = localOnly ? null : org.apache.pdfbox.util.DateConverter.toCalendar(Common.nz(record.sentXmp));
      if (sent == null && !localOnly) sent = org.apache.pdfbox.util.DateConverter.toCalendar(Common.nz(record.rawDateHeader));
      if (sent == null && !localOnly) sent = org.apache.pdfbox.util.DateConverter.toCalendar(Common.nz(record.date));
      if (sent != null) ci.setDate(COSName.getPDFName("Sent"), sent);
      ci.setInt(COSName.getPDFName("Size"), PdfMailWriter.messageMetadataSize(record));
    }
    return ci;
  }

  static void rewriteTopLevelCollectionItems(PDDocument doc, List<PdfCollectionSupport.CollectionMember> members) throws Exception {
    PDDocumentNameDictionary names = doc.getDocumentCatalog().getNames();
    if (names == null) return;
    PDEmbeddedFilesNameTreeNode efs = names.getEmbeddedFiles();
    if (efs == null || efs.getNames() == null || efs.getNames().isEmpty()) return;
    Map<String, PDComplexFileSpecification> nameMap = new LinkedHashMap<>(efs.getNames());
    Map<String, PdfCollectionSupport.CollectionMember> memberByKey = new LinkedHashMap<>();
    Map<EmailRecord, PdfCollectionSupport.CollectionMember> canonical = new IdentityHashMap<>();
    for (PdfCollectionSupport.CollectionMember member : members) {
      String key = member.folderId > 0 ? ("<" + member.folderId + ">" + member.filename) : member.filename;
      memberByKey.put(key, member);
      if (member.records.size() == 1) canonical.putIfAbsent(member.records.get(0), member);
    }
    for (Map.Entry<String, PDComplexFileSpecification> entry : nameMap.entrySet()) {
      PdfCollectionSupport.CollectionMember member = memberByKey.get(entry.getKey());
      if (member == null) continue;
      if (member.records.size() == 1) member = canonical.get(member.records.get(0));
      entry.getValue().getCOSObject().setItem(COSName.getPDFName("CI"), topLevelCollectionItemDictionary(member));
    }
    efs.setNames(nameMap);
    names.setEmbeddedFiles(efs);
    doc.getDocumentCatalog().setNames(names);
  }

  static COSDictionary messageCollectionItemDictionary(PdfCollectionSupport.CollectionMember member) {
    COSDictionary ci = new COSDictionary();
    ci.setInt(COSName.getPDFName("adobe:Order"), member.orderIndex);
    ci.setString(COSName.getPDFName("Subject"), member.subject);
    ci.setString(COSName.getPDFName("Folder"), member.groupName);
    if (!Common.nz(member.sentDisplay).isBlank()) ci.setString(COSName.getPDFName("Sent"), member.sentDisplay);
    ci.setInt(COSName.getPDFName("Attachments"), member.attachmentCount);
    ci.setInt(COSName.getPDFName("Size"), member.pdfSize());
    if (!member.records.isEmpty()) {
      EmailRecord first = member.records.get(0);
      if (!Common.nz(first.from).isBlank()) ci.setString(COSName.getPDFName("From"), first.from);
      if (!Common.nz(first.to).isBlank()) ci.setString(COSName.getPDFName("To"), first.to);
      if (!Common.nz(first.cc).isBlank()) ci.setString(COSName.getPDFName("Cc"), first.cc);
      if (!Common.nz(first.bcc).isBlank()) ci.setString(COSName.getPDFName("Bcc"), first.bcc);
      if (!Common.nz(first.messageId).isBlank()) ci.setString(COSName.getPDFName("Message-ID"), first.messageId);
    }
    return ci;
  }
static class CollectionMember {
  final String groupName;
  final String subject;
  final String childProfile;
  final List<EmailRecord> records;
  final int folderId;
  final int orderIndex;
  final String filename;
  byte[] pdfBytes;
  Path pdfPath;
  int storedPdfSize;
  final int attachmentCount;
  final String sentDisplay;

  CollectionMember(String groupName, String subject, String childProfile, List<EmailRecord> records, int folderId, int orderIndex, String filename, byte[] pdfBytes,
                   int attachmentCount, String sentDisplay) {
    this.groupName = groupName;
    this.subject = subject;
    this.childProfile = childProfile;
    this.records = records;
    this.folderId = folderId;
    this.orderIndex = orderIndex;
    this.filename = filename;
    this.pdfBytes = pdfBytes;
    this.attachmentCount = attachmentCount;
    this.sentDisplay = sentDisplay;
  }

  void stage(PayloadStore store) throws IOException {
    if (store == null || pdfBytes == null) return;
    storedPdfSize = pdfBytes.length;
    pdfPath = store.write(pdfBytes);
    pdfBytes = null;
  }

  int pdfSize() {
    return pdfBytes != null ? pdfBytes.length : storedPdfSize;
  }
}
static class CollectionFolder {
  final String name;
  final int id;
  int messageCount;
  long totalSizeBytes;

  CollectionFolder(String name, int id, int messageCount) {
    this.name = name;
    this.id = id;
    this.messageCount = messageCount;
  }
}
static class FolderSection {
  final String folderName;
  final List<EmailRecord> records;

  FolderSection(String folderName, List<EmailRecord> records) {
    this.folderName = folderName;
    this.records = records;
  }
}
static class MessageSegment {
  final EmailRecord record;
  final int startPage;
  final int endPage;
  COSDictionary semanticTree;
  MessageSegment(EmailRecord record, int startPage, int endPage) {
    this.record = record;
    this.startPage = startPage;
    this.endPage = endPage;
  }
}
}
