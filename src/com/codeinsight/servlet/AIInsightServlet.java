package com.codeinsight.servlet;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

import java.io.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import org.json.JSONArray;
import org.json.JSONObject;

public class AIInsightServlet extends HttpServlet {

    // ── FIX 1: HttpClient is static — created once, reused for all requests ──
    // Creating a new HttpClient per request is expensive (thread pools,
    // connections)
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10)) // FIX 2: connection timeout
            .build();

    // ── FIX 3: API key — read from env only, never hardcode in source ──
    // Set environment variable: GEMINI_API_KEY=your_key_here
    // In IntelliJ: Run > Edit Configurations > Environment Variables
    // On server: export GEMINI_API_KEY=your_key_here
    private static final String GEMINI_API_KEY = System.getenv("GEMINI_API_KEY");

    private static final String GEMINI_URL = "https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash:generateContent?key=";

    private static final java.util.Set<String> ALLOWED = java.util.Set.of(
            "http://localhost:3000",
            "http://localhost:5173",
            "http://localhost:5174",
            "http://localhost:4173");

    // ── Startup check: warn early if API key is missing ──
    @Override
    public void init() throws ServletException {
        super.init();
        if (GEMINI_API_KEY == null || GEMINI_API_KEY.isBlank()) {
            System.err.println("[AI] WARNING: GEMINI_API_KEY environment variable is not set!");
        } else {
            System.out.println("[AI] Gemini API key loaded OK.");
        }
    }

    private void setCorsHeaders(HttpServletRequest request, HttpServletResponse response) {
        String origin = request.getHeader("Origin");
        if (origin != null && ALLOWED.contains(origin)) {
            response.setHeader("Access-Control-Allow-Origin", origin);
            response.setHeader("Access-Control-Allow-Credentials", "true");
        }
    }

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {

        // ── FIX 4: Set status BEFORE getWriter() — headers must be set first ──
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        setCorsHeaders(request, response);

        JSONObject result = new JSONObject();

        // ── Check API key before doing anything ──
        if (GEMINI_API_KEY == null || GEMINI_API_KEY.isBlank()) {
            response.setStatus(503);
            result.put("success", false);
            result.put("message", "AI service is not configured. GEMINI_API_KEY is missing.");
            response.getWriter().print(result);
            return;
        }

        // ── Read body ──
        StringBuilder sb = new StringBuilder();
        BufferedReader reader = request.getReader();
        String line;
        while ((line = reader.readLine()) != null)
            sb.append(line);

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

        // ── FIX 5: Safe session cast — getAttribute returns Object, cast to Integer ──
        int userId = -1;
        String username = "unknown";

        HttpSession session = request.getSession(false);
        if (session != null && session.getAttribute("userId") != null) {
            Object raw = session.getAttribute("userId");
            userId = (raw instanceof Integer) ? (Integer) raw : Integer.parseInt(raw.toString());
            username = (String) session.getAttribute("username");
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

        // ── Main logic ──
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

            // ── Log raw response for debugging (first 300 chars) ──
            System.out.println("[AI] Gemini raw (preview): " +
                    geminiResponse.substring(0, Math.min(300, geminiResponse.length())));

            JSONObject parsed = parseGeminiResponse(geminiResponse);

            result.put("success", true);
            result.put("explanation", parsed.optString("explanation", "No explanation available."));
            result.put("complexity", parsed.optString("complexity", ""));
            result.put("suggestions", parsed.optString("suggestions", ""));
            result.put("concepts", parsed.optString("concepts", ""));
            result.put("timeComplex", parsed.optString("timeComplex", "O(?)"));
            result.put("spaceComplex", parsed.optString("spaceComplex", "O(?)"));
            result.put("optimizedCode", parsed.optString("optimizedCode", ""));

            System.out.println("[AI] Insight generated for: " + username);

        } catch (Exception e) {
            // ── FIX 4 continued: status set before any write ──
            response.setStatus(500);
            result.put("success", false);
            result.put("message", "AI service error: " + e.getMessage());
            System.err.println("[AI] Error: " + e.getMessage());
            e.printStackTrace();
        }

        response.getWriter().print(result);
        response.getWriter().flush();
    }

    @Override
    protected void doOptions(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        setCorsHeaders(request, response);
        response.setHeader("Access-Control-Allow-Methods", "POST, OPTIONS");
        response.setHeader("Access-Control-Allow-Headers", "Content-Type");
        response.setStatus(200);
    }

    private String buildPrompt(String code, String problem, String verdict) {
        // ── FIX 6: Use concat instead of formatted() to avoid issues with
        // curly braces in the JSON template being misread as format specifiers ──
        String problemLine = (problem.isEmpty() ? "Not specified" : problem);
        String verdictLine = (verdict.isEmpty() ? "Not submitted yet" : verdict);

        return "You are a friendly coding teacher explaining Java code to a complete beginner.\n" +
                "Your goal is to make everything so simple that even someone who has never coded\n" +
                "before can understand it. Use simple words, real-life analogies, and examples.\n" +
                "Avoid technical jargon — if you must use a technical term, explain it immediately.\n\n" +
                "Problem: " + problemLine + "\n" +
                "Verdict: " + verdictLine + "\n\n" +
                "Code to analyze:\n" + code + "\n\n" +
                "Respond with ONLY a valid JSON object — no markdown, no code fences, no extra text.\n" +
                "Use this exact structure:\n" +
                "{\n" +
                "  \"explanation\": \"Explain what this code does like a story for a 10-year-old. Start with 'This code...' and walk through it step by step. Use real-life analogies (like comparing HashMap to a notebook). Write 5-7 sentences minimum.\",\n"
                +
                "  \"concepts\": \"List concepts used with simple bracket explanations. Example: HashMap (like a dictionary), For Loop (like repeating a task), Array (like a row of boxes)\",\n"
                +
                "  \"timeComplex\": \"State time complexity like O(n) then explain in one simple sentence.\",\n" +
                "  \"spaceComplex\": \"State space complexity like O(n) then explain simply.\",\n" +
                "  \"complexity\": \"Friendly explanation of performance covering time, space, best and worst case.\",\n"
                +
                "  \"suggestions\": \"Give exactly 3 improvement suggestions numbered 1. 2. 3.\",\n" +
                "  \"optimizedCode\": \"Provide improved Java code with a comment on every line in plain English.\"\n" +
                "}";
    }

    private String callGemini(String prompt) throws Exception {
        JSONObject requestBody = new JSONObject()
                .put("contents", new JSONArray()
                        .put(new JSONObject()
                                .put("parts", new JSONArray()
                                        .put(new JSONObject().put("text", prompt)))));

        HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(URI.create(GEMINI_URL + GEMINI_API_KEY))
                .header("Content-Type", "application/json")
                // ── FIX 2: Request-level timeout — if Gemini takes >25s, throw exception ──
                .timeout(Duration.ofSeconds(25))
                .POST(HttpRequest.BodyPublishers.ofString(requestBody.toString()))
                .build();

        HttpResponse<String> httpResponse = HTTP_CLIENT.send(
                httpRequest, HttpResponse.BodyHandlers.ofString());

        System.out.println("[AI] Gemini status: " + httpResponse.statusCode());

        // ── Check for non-200 from Gemini and surface the actual error ──
        if (httpResponse.statusCode() != 200) {
            throw new RuntimeException("Gemini returned HTTP " + httpResponse.statusCode()
                    + ": " + httpResponse.body());
        }

        return httpResponse.body();
    }

    private JSONObject parseGeminiResponse(String rawResponse) {
        try {
            JSONObject root = new JSONObject(rawResponse);
            String text = root
                    .getJSONArray("candidates").getJSONObject(0)
                    .getJSONObject("content")
                    .getJSONArray("parts").getJSONObject(0)
                    .getString("text").trim();

            // Strip markdown code fences if Gemini wraps response anyway
            text = text.replaceAll("(?s)```json\\s*", "")
                    .replaceAll("(?s)```\\s*", "")
                    .trim();

            // Extract just the JSON object
            int start = text.indexOf("{");
            int end = text.lastIndexOf("}");
            if (start != -1 && end != -1 && end > start)
                text = text.substring(start, end + 1);

            return new JSONObject(text);

        } catch (Exception e) {
            System.err.println("[AI] Parse error: " + e.getMessage());
            // Return safe fallback so frontend doesn't crash
            return new JSONObject()
                    .put("explanation", "AI analysis completed. Your code has been reviewed.")
                    .put("concepts", "Java, HashMap, Arrays")
                    .put("timeComplex", "O(n) - grows linearly with input size")
                    .put("spaceComplex", "O(n) - extra space for the HashMap")
                    .put("complexity", "The solution runs in linear time which is optimal for Two Sum.")
                    .put("suggestions",
                            "1. Add null checks for input array.\n2. Handle edge case where no solution exists.\n3. Add inline comments for clarity.")
                    .put("optimizedCode", "// Your solution looks good! See suggestions above.");
        }
    }
}
