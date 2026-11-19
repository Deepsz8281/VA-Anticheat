package dev.vaplus.anticheat.config;

import dev.vaplus.anticheat.testutil.Configs;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards against typos in message keys: every key referenced from production code must exist in
 * the bundled messages.yml (a missing key renders as the key itself in game).
 */
@DisplayName("Message keys used in code exist in messages.yml")
class MessageKeysTest {

    private static final Path SOURCE_ROOT = Paths.get("src", "main", "java");
    private static final Pattern CALL = Pattern.compile("\\b(render|renderLines|raw|kick)\\(");
    private static final Pattern STRING_LITERAL = Pattern.compile("\"([^\"\\\\]*)\"");
    private static final Pattern KEY_SHAPE = Pattern.compile("[a-z][a-z0-9.-]*");

    /** Keys chosen through a local variable, which the call scan cannot see. */
    private static final List<String> INDIRECT_KEYS = List.of("alert", "alert-experimental");

    @Test
    @DisplayName("every literal key passed to render/renderLines/raw/kick is defined")
    void allKeysDefined() throws IOException {
        YamlConfiguration messages = Configs.bundledMessages();
        Map<String, List<String>> usages = scanKeyUsages();

        List<String> missing = new ArrayList<>();
        for (Map.Entry<String, List<String>> entry : usages.entrySet()) {
            if (!messages.contains(entry.getKey()) || messages.isConfigurationSection(entry.getKey())) {
                missing.add(entry.getKey() + " (used in " + entry.getValue() + ")");
            }
        }
        assertTrue(missing.isEmpty(), "message keys missing from messages.yml: " + missing);
    }

    @Test
    @DisplayName("the scanner finds the known call sites (guards against a silently broken scan)")
    void scannerFindsKnownKeys() throws IOException {
        Map<String, List<String>> usages = scanKeyUsages();
        for (String expected : List.of("no-permission", "help", "usage", "kick-message", "staff-notice", "timeout-kick",
                "debug-line", "info", "info-violation", "profile-entry", "reload-warning", "alerts-enabled", "alerts-disabled",
                "debug-enabled", "debug-disabled", "profile-within-budget", "profile-over-budget")) {
            assertTrue(usages.containsKey(expected), "scanner did not find '" + expected + "' in " + usages.keySet());
        }
        assertTrue(usages.size() >= 25, "only " + usages.size() + " keys found: " + usages.keySet());
    }

    @Test
    @DisplayName("keys selected through variables (alert / alert-experimental) are defined")
    void indirectKeysDefined() {
        YamlConfiguration messages = Configs.bundledMessages();
        for (String key : INDIRECT_KEYS) {
            assertTrue(messages.isString(key), key);
        }
    }

    @Test
    @DisplayName("the bundled messages define a prefix and no message is blank")
    void bundledMessagesSane() {
        YamlConfiguration messages = Configs.bundledMessages();
        assertTrue(messages.isString("prefix"));
        for (String key : messages.getKeys(true)) {
            if (messages.isConfigurationSection(key)) {
                continue;
            }
            if (messages.isList(key)) {
                assertTrue(!messages.getStringList(key).isEmpty(), key + " is an empty list");
            } else {
                assertTrue(!messages.getString(key, "").isBlank(), key + " is blank");
            }
        }
    }

    @Test
    @DisplayName("scanner self-test: ternaries and multiple arguments")
    void scannerSelfTest() {
        Map<String, List<String>> usages = new TreeMap<>();
        collect("test", "sender.sendMessage(messages.render(enabled ? \"yes-key\" : \"no-key\", \"player\", \"Steve\"));", usages);
        collect("test", "context.punishments().kick(this, \"kick-key\");", usages);
        collect("test", "messages.renderTemplate(\"not-a-key\", \"x\", \"y\");", usages);
        assertEquals(List.of("kick-key", "no-key", "yes-key"), new ArrayList<>(usages.keySet()));
    }

    private static Map<String, List<String>> scanKeyUsages() throws IOException {
        Path root = SOURCE_ROOT.toAbsolutePath();
        assertTrue(Files.isDirectory(root), "expected production sources at " + root + " (surefire working dir = project dir)");
        Map<String, List<String>> usages = new TreeMap<>();
        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                String source = Files.readString(file, StandardCharsets.UTF_8);
                collect(root.relativize(file).toString().replace('\\', '/'), source, usages);
            }
        }
        return usages;
    }

    private static void collect(String fileName, String source, Map<String, List<String>> into) {
        Matcher matcher = CALL.matcher(source);
        while (matcher.find()) {
            String method = matcher.group(1);
            int argumentsStart = matcher.end();
            List<String> arguments = splitTopLevelArguments(source, argumentsStart);
            int keyArgument = method.equals("kick") ? 1 : 0;
            if (arguments.size() <= keyArgument) {
                continue;
            }
            Matcher literal = STRING_LITERAL.matcher(arguments.get(keyArgument));
            while (literal.find()) {
                String key = literal.group(1);
                if (KEY_SHAPE.matcher(key).matches()) {
                    into.computeIfAbsent(key, k -> new ArrayList<>()).add(fileName);
                }
            }
        }
    }

    /** Splits the text after an opening parenthesis into top-level arguments up to the matching close. */
    private static List<String> splitTopLevelArguments(String source, int start) {
        List<String> arguments = new ArrayList<>();
        int depth = 1;
        boolean inString = false;
        StringBuilder current = new StringBuilder();
        for (int i = start; i < source.length(); i++) {
            char c = source.charAt(i);
            if (inString) {
                current.append(c);
                if (c == '\\' && i + 1 < source.length()) {
                    current.append(source.charAt(++i));
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
                current.append(c);
            } else if (c == '(' || c == '[' || c == '{') {
                depth++;
                current.append(c);
            } else if (c == ')' || c == ']' || c == '}') {
                depth--;
                if (depth == 0) {
                    arguments.add(current.toString());
                    return arguments;
                }
                current.append(c);
            } else if (c == ',' && depth == 1) {
                arguments.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        arguments.add(current.toString());
        return arguments;
    }
}
