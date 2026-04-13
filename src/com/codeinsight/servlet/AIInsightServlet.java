package com.codeinsight.servlet;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

public class AIInsightServlet extends HttpServlet {

    private static final String GEMINI_API_KEY = initGeminiApiKey();
    private static final String GEMINI_URL =
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent?key="
                    + GEMINI_API_KEY;
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(25))
            .build();

    private static String initGeminiApiKey() {
        Optional<String> dotenvKey = findGeminiKeyInDotEnv();
        if (dotenvKey.isPresent()) {
            return dotenvKey.get();
        }
        String envKey = System.getenv("GEMINI_API_KEY");
        if (envKey != null && !envKey.isBlank()) {
            return envKey.trim();
        }
        return "AIzaSyAZgUiB1FVbZVegfjd0ySu7Om0tgzaPYrI";
    }

    private static Optional<String> findGeminiKeyInDotEnv() {
        Path path = Paths.get(".").toAbsolutePath().normalize();
        for (int i = 0; i < 5 && path != null; i++) {
            Path envFile = path.resolve(".env");
            if (Files.exists(envFile)) {
                try {
                    List<String> lines = Files.readAllLines(envFile);
                    for (String line : lines) {
                        String trimmed = line.trim();
                        if (trimmed.startsWith("GEMINI_API_KEY=")) {
                            String value = trimmed.substring("GEMINI_API_KEY=".length()).trim();
                            if (!value.isBlank()) {
                                return Optional.of(value);
                            }
                        }
                    }
                } catch (IOException ignored) {
                }
            }
            path = path.getParent();
        }
        return Optional.empty();
    }

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        setCorsHeaders(response);

        JSONObject result = new JSONObject();

        if (GEMINI_API_KEY == null || GEMINI_API_KEY.isBlank()) {
            response.setStatus(503);
            result.put("success", false);
            result.put("message", "AI service is not configured. GEMINI_API_KEY is missing.");
            response.getWriter().print(result);
            return;
        }

        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = request.getReader()) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
        }

        JSONObject body;
        try {
            body = new JSONObject(sb.toString());
        } catch (Exception e) {
            response.setStatus(400);
            result.put("success", false);
            result.put("message", "Invalid JSON body.");
            response.getWriter().print(result);
            return;
        }

        int userId = -1;
        String username = "unknown";
        HttpSession session = request.getSession(false);
        if (session != null && session.getAttribute("userId") != null) {
            Object raw = session.getAttribute("userId");
            userId = (raw instanceof Integer) ? (Integer) raw : Integer.parseInt(raw.toString());
            username = session.getAttribute("username") != null
                    ? session.getAttribute("username").toString()
                    : "unknown";
        } else {
            int bodyUserId = body.optInt("userId", -1);
            if (bodyUserId > 0) {
                userId = bodyUserId;
                username = body.optString("username", "unknown");
            }
        }

        if (userId == -1) {
            response.setStatus(401);
            result.put("success", false);
            result.put("message", "Please log in.");
            response.getWriter().print(result);
            return;
        }

        try {
            String code = body.optString("code", "").trim();
            String problem = body.optString("problem", "").trim();
            String verdict = body.optString("verdict", "").trim();

            if (code.isEmpty()) {
                response.setStatus(400);
                result.put("success", false);
                result.put("message", "Code is required.");
                response.getWriter().print(result);
                return;
            }

            String prompt = buildPrompt(code, problem, verdict);
            String geminiResponse = callGemini(prompt);
            JSONObject parsed = parseGeminiResponse(geminiResponse);

            result.put("success", true);
            result.put("explanation", parsed.optString("explanation", "No explanation available."));
            result.put("errorAnalysis", parsed.optString("errorAnalysis", ""));
            result.put("errorFix", parsed.optString("errorFix", ""));
            result.put("concepts", parsed.optString("concepts", ""));
            result.put("timeComplex", parsed.optString("timeComplex", ""));
            result.put("spaceComplex", parsed.optString("spaceComplex", ""));
            result.put("complexity", parsed.optString("complexity", ""));
            result.put("suggestions", parsed.optString("suggestions", ""));
            result.put("optimizedCode", parsed.optString("optimizedCode", ""));

            System.out.println("[AI] Insight generated for user: " + username);
        } catch (Exception e) {
            response.setStatus(500);
            result.put("success", false);
            result.put("message", "AI service error: " + e.getMessage());
            System.err.println("[AI] Error: " + e.getMessage());
        }

        response.getWriter().print(result);
        response.getWriter().flush();
    }

    private String buildPrompt(String code, String problem, String verdict) {
        boolean hasError = verdict != null && (
                verdict.contains("Error") || verdict.contains("TLE") || verdict.contains("Wrong")
        );

        String errorSection = hasError
                ? "IMPORTANT: The code has a verdict of \"" + verdict + "\". You MUST:\n"
                + "1. In \"errorAnalysis\": Clearly explain WHY this error is happening in simple words.\n"
                + "   If it is a Compilation Error — explain the syntax mistake.\n"
                + "   If it is a Runtime Error — explain what caused the crash (null pointer, array out of bounds, etc.).\n"
                + "   If it is TLE — explain why the code is too slow and what approach to use.\n"
                + "   If it is Wrong Answer — explain why the output does not match what was expected.\n"
                + "2. In \"errorFix\": Give the corrected code with comments explaining what was changed and why.\n\n"
                : "";

        String problemLine = problem.isEmpty() ? "Not specified" : problem;
        String verdictLine = verdict.isEmpty() ? "Not submitted yet" : verdict;

        return "You are a friendly coding teacher explaining Java code to a complete beginner.\n"
                + "Your goal is to make everything so simple that even someone who has never coded\n"
                + "before can understand it. Use simple words, real-life analogies, and examples.\n"
                + "Avoid technical jargon — if you must use a technical term, explain it immediately.\n\n"
                + "Problem: " + problemLine + "\n"
                + "Verdict: " + verdictLine + "\n\n"
                + errorSection
                + "Code to analyze:\n" + code + "\n\n"
                + "Respond with ONLY this JSON (no markdown, no extra text).\n"
                + "Use this exact structure:\n"
                + "{\n"
                + "  \"explanation\": \"...\",\n"
                + "  \"errorAnalysis\": \"...\",\n"
                + "  \"errorFix\": \"...\",\n"
                + "  \"concepts\": \"...\",\n"
                + "  \"timeComplex\": \"...\",\n"
                + "  \"spaceComplex\": \"...\",\n"
                + "  \"complexity\": \"...\",\n"
                + "  \"suggestions\": \"...\",\n"
                + "  \"optimizedCode\": \"...\"\n"
                + "}";
    }

    private String callGemini(String prompt) throws Exception {
        JSONObject requestBody = new JSONObject()
                .put("contents", new JSONArray()
                        .put(new JSONObject()
                                .put("parts", new JSONArray()
                                        .put(new JSONObject().put("text", prompt)))));

        HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(URI.create(GEMINI_URL))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(25))
                .POST(HttpRequest.BodyPublishers.ofString(requestBody.toString()))
                .build();

        HttpResponse<String> httpResponse = HTTP_CLIENT.send(
                httpRequest, HttpResponse.BodyHandlers.ofString());

        System.out.println("[AI] Gemini status: " + httpResponse.statusCode());

        if (httpResponse.statusCode() != 200) {
            throw new Exception("Gemini API returned HTTP " + httpResponse.statusCode()
                    + ": " + httpResponse.body());
        }

        return httpResponse.body();
    }

    private JSONObject parseGeminiResponse(String rawResponse) throws Exception {
        System.out.println("[AI] Raw response (first 500): "
                + rawResponse.substring(0, Math.min(500, rawResponse.length())));

        JSONObject root = new JSONObject(rawResponse);
        if (root.has("error")) {
            throw new Exception("Gemini error: " + root.getJSONObject("error").optString("message", rawResponse));
        }

        String text = root
                .getJSONArray("candidates")
                .getJSONObject(0)
                .getJSONObject("content")
                .getJSONArray("parts")
                .getJSONObject(0)
                .getString("text")
                .trim();

        text = text.replaceAll("(?s)```json\\s*", "").replaceAll("(?s)```", "").trim();

        int start = text.indexOf("{");
        int end = text.lastIndexOf("}");
        if (start == -1 || end == -1 || end <= start) {
            throw new Exception("Gemini response did not contain valid JSON. Got: "
                    + text.substring(0, Math.min(200, text.length())));
        }

        text = text.substring(start, end + 1);
        System.out.println("[AI] Parsed JSON (first 300): "
                + text.substring(0, Math.min(300, text.length())));

        return new JSONObject(text);
    }

    @Override
    protected void doOptions(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        setCorsHeaders(response);
        response.setHeader("Access-Control-Allow-Methods", "POST, OPTIONS");
        response.setHeader("Access-Control-Allow-Headers", "Content-Type");
        response.setStatus(200);
    }

    private void setCorsHeaders(HttpServletResponse response) {
        String origin = System.getenv("FRONTEND_URL") != null
                ? System.getenv("FRONTEND_URL")
                : "http://localhost:5173";
        response.setHeader("Access-Control-Allow-Origin", origin);
        response.setHeader("Access-Control-Allow-Credentials", "true");
    }
}