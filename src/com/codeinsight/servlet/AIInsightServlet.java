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

import org.json.JSONArray;
import org.json.JSONObject;

public class AIInsightServlet extends HttpServlet {

    private static final String GEMINI_API_KEY = System.getenv("GEMINI_API_KEY") != null
            ? System.getenv("GEMINI_API_KEY")
            : "AIzaSyDZFIO94SRY3KlYd1uzu-ifrZAZ-PVFYlc";

    private static final String GEMINI_URL =
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent?key="
            + GEMINI_API_KEY;

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {

        setCorsHeaders(response);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");

        PrintWriter out = response.getWriter();
        JSONObject result = new JSONObject();

        HttpSession session = request.getSession(false);
        if (session == null || session.getAttribute("userId") == null) {
            response.setStatus(401);
            result.put("success", false);
            result.put("message", "Please log in to use AI Insight.");
            out.print(result);
            return;
        }

        StringBuilder sb = new StringBuilder();
        BufferedReader reader = request.getReader();
        String line;
        while ((line = reader.readLine()) != null)
            sb.append(line);

        try {
            JSONObject body = new JSONObject(sb.toString());
            String code    = body.optString("code", "").trim();
            String problem = body.optString("problem", "").trim();
            String verdict = body.optString("verdict", "").trim();

            if (code.isEmpty()) {
                response.setStatus(400);
                result.put("success", false);
                result.put("message", "Code is required.");
                out.print(result);
                return;
            }

            String prompt = buildPrompt(code, problem, verdict);

            // Call Gemini — throws on non-200 response
            String geminiResponse = callGemini(prompt);

            // Parse — throws on bad JSON
            JSONObject parsed = parseGeminiResponse(geminiResponse);

            result.put("success", true);
            result.put("explanation",  parsed.optString("explanation",  ""));
            result.put("concepts",     parsed.optString("concepts",     ""));
            result.put("timeComplex",  parsed.optString("timeComplex",  ""));
            result.put("spaceComplex", parsed.optString("spaceComplex", ""));
            result.put("complexity",   parsed.optString("complexity",   ""));
            result.put("suggestions",  parsed.optString("suggestions",  ""));
            result.put("optimizedCode",parsed.optString("optimizedCode",""));

            System.out.println("[AI] Insight generated for user: " + session.getAttribute("username"));

        } catch (Exception e) {
            response.setStatus(500);
            result.put("success", false);
            result.put("message", "AI service error: " + e.getMessage());
            System.err.println("[AI] Error: " + e.getMessage());
        }

        out.print(result);
        out.flush();
    }

    private String buildPrompt(String code, String problem, String verdict) {
        return """
                You are a friendly coding teacher explaining Java code to a complete beginner.
                Your goal is to make everything so simple that even someone who has never coded
                before can understand it. Use simple words, real-life analogies, and examples.
                Avoid technical jargon — if you must use a technical term, explain it immediately.

                Problem: %s
                Verdict: %s

                Code to analyze:
                %s

                Respond with ONLY this JSON (no markdown, no extra text):
                {
                  "explanation": "Explain what this code does like you are telling a story to a 10-year-old. Start with 'This code...' and walk through it step by step in plain English. Use a real-life analogy (like comparing a HashMap to a notebook or dictionary). Mention what the main method does, what the logic does, and what the final answer/output is. Write 5-7 sentences minimum.",

                  "concepts": "List the concepts/data structures used. After each one write a simple bracket explanation. Example: HashMap (like a dictionary where you store word and its meaning), For Loop (like repeating a task until you are done), Array (like a row of boxes each holding one number)",

                  "timeComplex": "State the time complexity like O(n) then explain it in one simple sentence using a real-life analogy.",

                  "spaceComplex": "State the space complexity like O(n) then explain it simply.",

                  "complexity": "Write a full friendly explanation of the performance covering time complexity, space complexity, best case, and worst case. Use bullet points separated by newlines.",

                  "suggestions": "Give exactly 3 improvement suggestions numbered 1. 2. 3. Each must have a short title in caps, why it is a problem, and how to fix it.",

                  "optimizedCode": "Provide the improved Java code with detailed comments on every line explaining what it does in plain English."
                }
                """.formatted(problem, verdict, code);
    }

    private String callGemini(String prompt) throws Exception {
        HttpClient client = HttpClient.newHttpClient();

        JSONObject requestBody = new JSONObject()
                .put("contents", new JSONArray()
                        .put(new JSONObject()
                                .put("parts", new JSONArray()
                                        .put(new JSONObject().put("text", prompt)))));

        HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(URI.create(GEMINI_URL))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody.toString()))
                .build();

        HttpResponse<String> httpResponse = client.send(
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

        // Check for Gemini error payload
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

        // Strip markdown code fences if present
        text = text.replaceAll("(?s)```json\\s*", "").replaceAll("(?s)```", "").trim();

        // Extract outermost JSON object
        int start = text.indexOf("{");
        int end   = text.lastIndexOf("}");
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
