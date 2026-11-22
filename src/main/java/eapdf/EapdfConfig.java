package eapdf;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

class EapdfConfig {
  int configVersion = 1;
  boolean validateOnCreation = true;
  String validationWarning = "";
  static final String DEFAULT_CREATOR = "Thinkbox.DIGITAL";
  String creator = DEFAULT_CREATOR;

  String requireCreator() {
    if (creator == null || creator.isBlank()) {
      return DEFAULT_CREATOR;
    }
    if (!Common.stripInvalidXml(creator).equals(creator)) {
      throw new IllegalArgumentException("The creator name contains characters that cannot be preserved in XMP.");
    }
    return creator.strip();
  }

  private static volatile EapdfConfig INSTANCE;

  static EapdfConfig get() {
    EapdfConfig cached = INSTANCE;
    if (cached != null) return cached;
    synchronized (EapdfConfig.class) {
      if (INSTANCE == null) INSTANCE = loadDefault();
      return INSTANCE;
    }
  }

  static Path configurationPath() {
    String envPath = System.getenv("EAPDF_CONFIG");
    Path path = (envPath != null && !envPath.isBlank())
        ? Paths.get(envPath)
        : Common.resolveExistingPath("config/eapdf_config.json", "eapdf_config.json");
    return path == null ? Paths.get("config/eapdf_config.json") : path;
  }

  static EapdfConfig loadDefault() {
    String envPath = System.getenv("EAPDF_CONFIG");
    Path path = configurationPath();
    if (!Files.exists(path) && (envPath == null || envPath.isBlank())) return new EapdfConfig();
    return load(path);
  }

  static EapdfConfig load(Path path) {
    try (Reader reader = Files.newBufferedReader(path)) {
      JsonElement json = JsonParser.parseReader(reader);
      if (!json.isJsonObject()) throw new IllegalArgumentException("configuration must be a JSON object");
      validateKeys(json.getAsJsonObject());
      EapdfConfig loaded = new Gson().fromJson(json, EapdfConfig.class);
      if (loaded.configVersion != 1) throw new IllegalArgumentException("unsupported configVersion: " + loaded.configVersion + " (expected 1)");
      return loaded;
    } catch (Exception e) {
      throw new RuntimeException("failed to load config from " + path + ": " + e.getMessage(), e);
    }
  }

  private static void validateKeys(JsonObject json) {
    for (var entry : json.entrySet()) {
      String key = entry.getKey();
      java.lang.reflect.Field field;
      try {
        field = EapdfConfig.class.getDeclaredField(key);
        if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) throw new NoSuchFieldException();
      } catch (NoSuchFieldException ignored) {
        System.err.println("warning: unknown configuration setting: " + key);
        continue;
      }
      JsonElement value = entry.getValue();
      if (value.isJsonNull()) continue;
      if (field.getType() == boolean.class) {
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) throw new IllegalArgumentException(key + " must be a boolean");
      } else if (field.getType() == int.class) {
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber() || !value.getAsString().matches("[0-9]+")) throw new IllegalArgumentException(key + " must be an integer");
      } else if (field.getType() == String.class) {
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) throw new IllegalArgumentException(key + " must be a string");
      }
    }
  }
}
