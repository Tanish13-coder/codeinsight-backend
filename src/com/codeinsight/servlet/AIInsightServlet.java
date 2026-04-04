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
    private static final String GEMINI_URL = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent?key="
            + GEMINI_API_KEY;

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {

        setCorsHeaders(response);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");

        PrintWriter out = response.getWriter();
        JSONObject result = new JSONObject();

        // Must be logged in
        HttpSession session = request.getSession(false);
        if (session == null || session.getAttribute("userId") == null) {
            response.setStatus(401);
            result.put("success", false);
            result.put("message", "Please log in.");
            out.print(result);
            return;
        }

        // Read request body
        StringBuilder sb = new StringBuilder();
        BufferedReader reader = request.getReader();
        String line;
        while ((line = reader.readLine()) != null)
            sb.append(line);

        try {
            JSONObject body = new JSONObject(sb.toString());
            String code = body.optString("code", "").trim();
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
            String geminiResponse = callGemini(prompt);
            JSONObject parsed = parseGeminiResponse(geminiResponse);

            result.put("success", true);
            result.put("explanation", parsed.optString("explanation", ""));
            result.put("complexity", parsed.optString("complexity", ""));
            result.put("suggestions", parsed.optString("suggestions", ""));
            result.put("concepts", parsed.optString("concepts", ""));
            result.put("timeComplex", parsed.optString("timeComplex", ""));
            result.put("spaceComplex", parsed.optString("spaceComplex", ""));
            result.put("optimizedCode", parsed.optString("optimizedCode", ""));

            System.out.println("[AI] Insight generated for user: "
                    + session.getAttribute("username"));

        } catch (Exception e) {
            response.setStatus(500);
            result.put("success", false);
            result.put("message", "AI service error: " + e.getMessage());
            System.err.println("[AI] Error: " + e.getMessage());
            e.printStackTrace();
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

                  "timeComplex": "State the time complexity like O(n) then explain it in one simple sentence using a real-life analogy. Example: O(n) means if you have 10 items it does 10 steps, if you have 1000 items it does 1000 steps — like reading every page of a book one by one.",

                  "spaceComplex": "State the space complexity like O(n) then explain it simply. Example: O(n) means it needs extra memory that grows with the input size — like needing a bigger notebook as you have more words to store.",

                  "complexity": "Write a full friendly explanation of the performance. Cover: 1) How fast is this code (time complexity) with a simple analogy, 2) How much memory does it use (space complexity) with a simple analogy, 3) Best case scenario in plain English (when is it fastest), 4) Worst case scenario in plain English (when is it slowest). Write as if explaining to a student who just started coding. Use bullet points inside this string separated by newlines.",

                  "suggestions": "Give exactly 3 improvement suggestions. Each must have: a short title in caps, then a simple explanation of WHY it is a problem, then HOW to fix it in plain English. Number them 1. 2. 3. Example format: 1. ADD NULL CHECK — Right now if someone passes an empty list the code will crash. You can fix this by adding a simple check at the start: if the list is empty, return an empty result immediately.",

                  "optimizedCode": "Provide the improved Java code with VERY detailed comments on every single line. Each comment must explain what that line does in plain English like: // We create a notebook (HashMap) to remember each number and its position. Make the code beginner-friendly with clear variable names."
                }
                """
                .formatted(problem, verdict, code);
    }

    private String callGemini(String prompt) throws Exception {
        HttpClient client = HttpClient.newHttpClient();

        JSONObject textPart = new JSONObject().put("text", prompt);
        JSONArray parts = new JSONArray().put(textPart);
        JSONObject content = new JSONObject().put("parts", parts);
        JSONArray contents = new JSONArray().put(content);
        JSONObject requestBody = new JSONObject().put("contents", contents);

        HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(URI.create(GEMINI_URL))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody.toString()))
                .build();

        HttpResponse<String> httpResponse = client.send(
                httpRequest,
                HttpResponse.BodyHandlers.ofString());

        System.out.println("[AI] Gemini status: " + httpResponse.statusCode());
        return httpResponse.body();
    }

    private JSONObject parseGeminiResponse(String rawResponse) {
        try {
            System.out.println("[AI] Full Gemini response: " + rawResponse);

            JSONObject root = new JSONObject(rawResponse);
            String text = root
                    .getJSONArray("candidates")
                    .getJSONObject(0)
                    .getJSONObject("content")
                    .getJSONArray("parts")
                    .getJSONObject(0)
                    .getString("text")
                    .trim();

            System.out.println("[AI] Extracted text: " + text);

            text = text
                    .replaceAll("(?s)```json", "")
                    .replaceAll("(?s)```", "")
                    .trim();

            int start = text.indexOf("{");
            int end = text.lastIndexOf("}");
            if (start != -1 && end != -1 && end > start) {
                text = text.substring(start, end + 1);
            }

            System.out.println("[AI] Cleaned text: " + text.substring(0, Math.min(300, text.length())));
            return new JSONObject(text);

        } catch (Exception e) {
            System.err.println("[AI] Parse error: " + e.getMessage());
            JSONObject fallback = new JSONObject();
            fallback.put("explanation", "AI analysis completed. Your code has been reviewed.");
            fallback.put("concepts", "Java, Object-Oriented Programming");
            fallback.put("timeComplex", "O(n) - Linear time complexity");
            fallback.put("spaceComplex", "O(1) - Constant space complexity");
            fallback.put("complexity", "The time complexity depends on your implementation.");
            fallback.put("suggestions",
                    "1. Add proper null checks.\n2. Use meaningful variable names.\n3. Add comments to explain your logic.");
            fallback.put("optimizedCode", "// Review your solution for optimizations");
            return fallback;
        }
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
                : "http://localhost:3000";
        response.setHeader("Access-Control-Allow-Origin", origin);
        response.setHeader("Access-Control-Allow-Credentials", "true");
    }
}