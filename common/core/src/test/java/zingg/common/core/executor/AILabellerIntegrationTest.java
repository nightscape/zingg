package zingg.common.core.executor;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Standalone integration test for the AI Labeller's LLM interaction.
 * Does NOT require Spark — purely tests prompt → API → parse pipeline.
 *
 * Configuration via environment variables:
 *   ZINGG_AI_ENDPOINT  — API endpoint (default: http://localhost:8000/v1/chat/completions)
 *   ZINGG_AI_MODEL     — model name (default: Ternary-Bonsai-8B-mlx-2bit)
 *   ZINGG_AI_KEY       — API key, empty for none (default: OMLX_API_KEY)
 *   ZINGG_AI_TIMEOUT   — request timeout seconds (default: 120)
 *
 * Run with: java AILabellerIntegrationTest.java
 */
public class AILabellerIntegrationTest {

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String ENDPOINT   = env("ZINGG_AI_ENDPOINT", "http://localhost:8000/v1/chat/completions");
    private static final String MODEL      = env("ZINGG_AI_MODEL",    "Ternary-Bonsai-8B-mlx-2bit");
    private static final String API_KEY    = env("ZINGG_AI_KEY",      "OMLX_API_KEY");
    private static final Duration REQUEST_TIMEOUT =
            Duration.ofSeconds(Integer.parseInt(env("ZINGG_AI_TIMEOUT", "120")));

    private static int passed = 0;
    private static int failed = 0;

    private static final String SYSTEM_PROMPT =
            "You are a record linkage expert. Determine if these two records refer to the same real-world entity.\n\n"
          + "Respond with EXACTLY one digit and nothing else:\n"
          + "  1 = SAME entity (MATCH)\n"
          + "  0 = DIFFERENT entities (NO MATCH)\n"
          + "  2 = NOT SURE / need more information";

    // ── test case DSL ──────────────────────────────────────────────

    record LinkageCase(String label, Record a, Record b, int expected) {}
    record Record(String name, String address, String phone, String email) {
        @Override public String toString() {
            var sb = new StringBuilder();
            if (name    != null && !name   .isEmpty()) sb.append("  name: "    ).append(name)   .append("\n");
            if (address != null && !address.isEmpty()) sb.append("  address: " ).append(address).append("\n");
            if (phone   != null && !phone  .isEmpty()) sb.append("  phone: "   ).append(phone)  .append("\n");
            if (email   != null && !email  .isEmpty()) sb.append("  email: "   ).append(email)  .append("\n");
            return sb.toString();
        }
    }

    // ── main ───────────────────────────────────────────────────────

    public static void main(String[] args) throws Exception {
        System.out.println("AI Labeller Integration Test");
        System.out.println("  endpoint: " + ENDPOINT);
        System.out.println("  model:    " + MODEL);

        testBasicLLMCall();
        testParseResponse();
        testRecordLinkage();

        System.out.println("\n==============================");
        System.out.printf("Results: %d passed, %d failed%n", passed, failed);
        if (failed > 0) System.exit(1);
    }

    // ── tests ──────────────────────────────────────────────────────

    /** Can we reach the LLM? */
    static void testBasicLLMCall() throws Exception {
        System.out.println("\n─── Test 1: Basic LLM connectivity ───");

        String response = callLLM("Say exactly the word 'hello' and nothing else.");
        String content = extractContent(response);
        System.out.println("  Response: " + content);

        check(content.toLowerCase().contains("hello"), "contains 'hello'");
    }

    /** Response parser correctness (no API calls). */
    static void testParseResponse() {
        System.out.println("\n─── Test 2: Response parsing ───");

        checkParse("1", 1, "bare digit 1");
        checkParse("0", 0, "bare digit 0");
        checkParse("2", 2, "bare digit 2");
        checkParse("  1  ", 1, "whitespace-wrapped");
        checkParse("Answer: 0", 0, "prefixed answer");
        checkParse("I think 2 because...", 2, "mid-sentence digit");
        checkParse("abc", 2, "unparseable → NOT SURE fallback");
        checkParse("", 2, "empty → NOT SURE fallback");
        checkParse("the answer is 1\n0", 1, "first digit wins multi-line");
        checkParse("No match. 0.", 0, "digit after text");
    }

    /** Full record-linkage scenarios against live LLM. */
    static void testRecordLinkage() throws Exception {
        System.out.println("\n─── Test 3: Record linkage scenarios ───");

        LinkageCase[] cases = {

            // ── SHOULD BE MATCH (1) ─────────────────────────────
            c("identical records", 1,
                rec("John Smith", "123 Main St, Springfield", "555-0100", "jsmith@mail.com"),
                rec("John Smith", "123 Main St, Springfield", "555-0100", "jsmith@mail.com")),

            c("minor typo in name", 1,
                rec("Jon Smith", "123 Main St", "555-0100", "jsmith@mail.com"),
                rec("John Smith", "123 Main Street", "555-0100", "jsmith@mail.com")),

            c("nickname", 1,
                rec("William Brown", "500 Oak Ave, Portland", "555-2000", "bill@mail.com"),
                rec("Bill Brown", "500 Oak Ave, Portland", "555-2000", "bill@mail.com")),

            c("abbreviated address", 1,
                rec("Robert Chen", "100 Technology Dr, San Jose CA 95110", "555-3000", "rchen@corp.com"),
                rec("Rob Chen", "100 Tech Drive, San Jose, CA 95110", "555-3000", "rchen@corp.com")),

            c("missing middle name", 1,
                rec("James T Kirk", "Starship Enterprise", "555-4000", "kirk@starfleet.org"),
                rec("James Kirk", "Starship Enterprise", "555-4000", "kirk@starfleet.org")),

            c("company with/without Inc suffix", 1,
                rec("Acme Corp", "1 Infinity Loop, Cupertino", "555-5000", "info@acme.com"),
                rec("Acme Corp Inc", "1 Infinity Loop, Cupertino", "555-5000", "info@acme.com")),

            c("reversed name order", 1,
                rec("Smith, Jane", "42 Elm St, Boston MA 02110", "555-6000", "jane.smith@mail.com"),
                rec("Jane Smith", "42 Elm St, Boston MA 02110", "555-6000", "jane.smith@mail.com")),

            // ── SHOULD BE NO MATCH (0) ─────────────────────────
            c("completely different people", 0,
                rec("Alice Johnson", "42 Oak Ave, Springfield", "555-7000", "alice@example.com"),
                rec("Bob Williams", "17 Pine Rd, Shelbyville", "555-8000", "bob@example.com")),

            c("same name, different person (address + phone)", 0,
                rec("Maria Garcia", "500 Broadway, New York NY 10012", "555-9000", "mgarcia@mail.com"),
                rec("Maria Garcia", "200 Main St, Los Angeles CA 90012", "555-1111", "maria.garcia@other.com")),

            c("transposed digits (data entry typo? ambiguous)", -1 /* either */,
                rec("David Lee", "10 Park Ave, Chicago IL 60601", "555-1234", "dlee@mail.com"),
                rec("David Lee", "10 Park Ave, Chicago IL 60601", "555-1243", "dlee@mail.com")),

            c("same name, same city, different street", 0,
                rec("Sarah Connor", "101 Cyberdyne Blvd, Los Angeles CA", "555-1500", "sarah@resistance.org"),
                rec("Sarah Connor", "202 Skynet Drive, Los Angeles CA", "555-1600", "sarah@resistance.org")),

            // ── AMBIGUOUS (0 or 2 acceptable) ──────────────────
            c("same name, only one field matches", 0 /* or 2 */,
                rec("Thomas Anderson", "123 Matrix St, New York NY", "555-1700", "neo@matrix.com"),
                rec("Thomas Anderson", "456 Reloaded Ave, Chicago IL", "555-1800", "neo@matrix.com")),

            c("married name change (address + phone + email match, different surname)", -1 /* either */,
                rec("Jennifer Taylor", "789 Garden Rd, Miami FL 33101", "555-1900", "jen@mail.com"),
                rec("Jennifer Williams", "789 Garden Rd, Miami FL 33101", "555-1900", "jen@mail.com")),

            c("one record missing phone", 1 /* model may say 2 */,
                rec("Paul Atreides", "1 Arrakis Way, Dune", "555-2000", "muaddib@fremen.org"),
                rec("Paul Atreides", "1 Arrakis Way, Dune", "", "muaddib@fremen.org")),

            c("international address format variation", 1,
                rec("Hans Mueller", "Hauptstrasse 10, 10115 Berlin", "555-2100", "hans@mueller.de"),
                rec("Hans Mueller", "10 Hauptstr., Berlin 10115", "555-2100", "hans@mueller.de")),
        };

        int llmCalls = 0;
        for (LinkageCase tc : cases) {
            String prompt = buildPrompt(tc);
            String response = callLLM(prompt);
            llmCalls++;
            int label = extractLabel(extractContent(response));

            boolean acceptable = switch (tc.expected) {
                case 0  -> label == 0 || label == 2;  // NO_MATCH or NOT_SURE ok
                case 1  -> label == 1 || label == 2;  // MATCH or NOT_SURE ok
                case -1 -> true;                       // genuinely ambiguous
                default -> true;
            };

            String status;
            if (label == tc.expected) {
                status = "PASS";
                passed++;
            } else if (acceptable) {
                status = "OK  ";  // acceptable alternative (NOT_SURE)
                passed++;
            } else {
                status = "FAIL";
                failed++;
            }

            String labelName = switch (label) { case 1 -> "MATCH"; case 0 -> "NO"; case 2 -> "NS"; default -> "??"; };
            System.out.printf("  %s  %s → %s  %s%n", status, labelName, expectedStr(tc.expected), tc.label);
        }
        System.out.println("  (" + llmCalls + " LLM calls)");
    }

    // ── helpers ────────────────────────────────────────────────────

    static LinkageCase c(String label, int expected, Record a, Record b) {
        return new LinkageCase(label, a, b, expected);
    }

    static Record rec(String name, String address, String phone, String email) {
        return new Record(name, address, phone, email);
    }

    static String expectedStr(int e) { return switch (e) { case 1 -> "MATCH"; case 0 -> "NO"; case -1 -> "EITH"; default -> "AMB"; }; }

    static void checkParse(String content, int expected, String description) {
        int actual = extractLabel(content);
        if (actual == expected) {
            passed++;
            System.out.println("  PASS: " + description + " → " + actual);
        } else {
            failed++;
            System.out.println("  FAIL: " + description + " — expected " + expected + " got " + actual);
        }
    }

    static void check(boolean condition, String description) {
        if (condition) { passed++; System.out.println("  PASS: " + description); }
        else           { failed++; System.out.println("  FAIL: " + description); }
    }

    static String env(String key, String defaultValue) {
        String val = System.getenv(key);
        return (val != null && !val.isEmpty()) ? val : defaultValue;
    }

    // ── prompt builder ─────────────────────────────────────────────

    static String buildPrompt(LinkageCase tc) {
        return SYSTEM_PROMPT + "\n\n"
                + "--- Record 1 (source: A) ---\n" + tc.a
                + "--- Record 2 (source: B) ---\n" + tc.b
                + "\nYour answer (0, 1, or 2):";
    }

    // ── API call (mirrors AILabeller) ──────────────────────────────

    static String callLLM(String prompt) throws Exception {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("model", MODEL);

        ArrayNode messages = body.putArray("messages");
        ObjectNode userMsg = messages.addObject();
        userMsg.put("role", "user");
        userMsg.put("content", prompt);

        body.put("temperature", 0.0);
        body.put("max_tokens", 100);

        String json = MAPPER.writeValueAsString(body);

        var builder = HttpRequest.newBuilder()
                .uri(URI.create(ENDPOINT))
                .header("Content-Type", "application/json")
                .timeout(REQUEST_TIMEOUT)
                .POST(HttpRequest.BodyPublishers.ofString(json));
        if (API_KEY != null && !API_KEY.isEmpty()) {
            builder.header("Authorization", "Bearer " + API_KEY);
        }
        HttpRequest request = builder.build();

        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new RuntimeException("HTTP " + response.statusCode() + ": " + response.body());
        }
        return response.body();
    }

    // ── response parser (mirrors AILabeller) ───────────────────────

    static String extractContent(String responseBody) throws Exception {
        ObjectNode response = (ObjectNode) MAPPER.readTree(responseBody);
        var message = response.get("choices").get(0).get("message");

        var reasoning = message.get("reasoning_content");
        if (reasoning != null && !reasoning.isNull() && !reasoning.asText().isBlank()) {
            return reasoning.asText().trim();
        }
        var reasoningAlt = message.get("reasoning");
        if (reasoningAlt != null && !reasoningAlt.isNull() && !reasoningAlt.asText().isBlank()) {
            return reasoningAlt.asText().trim();
        }
        return message.get("content").asText().trim();
    }

    static int extractLabel(String content) {
        for (char c : content.toCharArray()) {
            if (c >= '0' && c <= '2') return c - '0';
        }
        return 2; // NOT SURE fallback
    }
}
