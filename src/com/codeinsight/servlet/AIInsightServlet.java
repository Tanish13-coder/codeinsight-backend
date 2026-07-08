package com.codeinsight.servlet;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Proxies AI Insight requests to a locally-running Flask service backed by
 * Ollama (see backend/ai-service/). This replaced an earlier version that
 * called the Gemini API directly.
 *
 * Configure the target with the AI_INSIGHT_URL environment variable;
 * defaults to http://localhost:8001/insight (see ai-service/README.md for
 * setup instructions).
 */
public class AIInsightServlet extends HttpServlet {

    private static final String INSIGHT_SERVICE_URL;
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build();

    static {
        String url = System.getenv("AI_INSIGHT_URL");
        INSIGHT_SERVICE_URL = (url != null && !url.isBlank()) ? url.trim() : "http://localhost:8001/insight";
    }

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        setCorsHeaders(response);

        JSONObject result = new JSONObject();

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

        try {
            JSONObject payload = new JSONObject();
            payload.put("code", code);
            payload.put("problem", problem);
            payload.put("verdict", verdict);

            HttpRequest httpRequest = HttpRequest.newBuilder()
                    .uri(URI.create(INSIGHT_SERVICE_URL))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(120))
                    .POST(HttpRequest.BodyPublishers.ofString(payload.toString()))
                    .build();

            HttpResponse<String> httpResponse = HTTP_CLIENT.send(
                    httpRequest, HttpResponse.BodyHandlers.ofString());

            System.out.println("[AI] Insight service status: " + httpResponse.statusCode());

            JSONObject serviceResult = new JSONObject(httpResponse.body());

            if (httpResponse.statusCode() != 200 || !serviceResult.optBoolean("success", false)) {
                response.setStatus(httpResponse.statusCode() != 200 ? httpResponse.statusCode() : 502);
                result.put("success", false);
                result.put("message", serviceResult.optString("message",
                        "AI insight service returned an error."));
                response.getWriter().print(result);
                return;
            }

            result.put("success", true);
            result.put("explanation", serviceResult.optString("explanation", "No explanation available."));
            result.put("errorAnalysis", serviceResult.optString("errorAnalysis", ""));
            result.put("errorFix", serviceResult.optString("errorFix", ""));
            result.put("concepts", serviceResult.optString("concepts", ""));
            result.put("timeComplex", serviceResult.optString("timeComplex", ""));
            result.put("spaceComplex", serviceResult.optString("spaceComplex", ""));
            result.put("complexity", serviceResult.optString("complexity", ""));
            result.put("suggestions", serviceResult.optString("suggestions", ""));
            result.put("optimizedCode", serviceResult.optString("optimizedCode", ""));

            System.out.println("[AI] Insight generated for user: " + username);

        } catch (java.net.ConnectException e) {
            result = buildFallbackResult(code, problem, verdict, "The local AI service is currently unavailable, so a local fallback analysis is being returned.");
            response.setStatus(200);
            System.err.println("[AI] Falling back because the AI service is unreachable: " + e.getMessage());
        } catch (Exception e) {
            result = buildFallbackResult(code, problem, verdict, "The AI service returned an error, so a local fallback analysis is being returned.");
            response.setStatus(200);
            System.err.println("[AI] Error: " + e.getMessage());
        }

        response.getWriter().print(result);
        response.getWriter().flush();
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

    private JSONObject buildFallbackResult(String code, String problem, String verdict, String note) {
        JSONObject fallback = new JSONObject();
        fallback.put("success", true);
        fallback.put("source", "local-fallback");
        fallback.put("explanation", buildExplanation(code, problem, verdict));
        fallback.put("errorAnalysis", buildErrorAnalysis(verdict, note));
        fallback.put("errorFix", buildErrorFix(code));
        fallback.put("concepts", "Input handling, loops, conditionals, output formatting");
        fallback.put("timeComplex", "Estimate the runtime by counting dominant loops and recursive calls.");
        fallback.put("spaceComplex", "Check whether the solution uses extra arrays, maps, or recursion that could be reduced.");
        fallback.put("complexity", "Focus on simplifying the main logic and removing unnecessary repeated work.");
        fallback.put("suggestions", "1. Validate edge cases\n2. Confirm the input/output format\n3. Simplify repeated work");
        fallback.put("optimizedCode", buildErrorFix(code));
        fallback.put("note", note);
        return fallback;
    }

    private String buildExplanation(String code, String problem, String verdict) {
        String problemText = (problem == null || problem.isBlank()) ? "the current coding challenge" : problem;
        if (code == null || code.isBlank()) {
            return "The editor is empty. Start by outlining the solution for " + problemText + " and then ask for analysis again.";
        }
        if (verdict != null && !verdict.isBlank()) {
            return "Your code is being reviewed for " + problemText + ". The current verdict suggests the solution needs a closer look at logic, edge cases, or input/output handling.";
        }
        return "Your code is being reviewed for " + problemText + ". The main goal is to confirm the approach is clear, efficient, and correctly handles the problem requirements.";
    }

    private String buildErrorAnalysis(String verdict, String note) {
        if (verdict != null && !verdict.isBlank()) {
            return "The current verdict indicates the implementation needs debugging. Review the main logic, check for off-by-one mistakes, boundary cases, and confirm the output format matches the expected result. " + note;
        }
        return "No specific error verdict was provided. Review the core logic and test the solution with a few representative inputs. " + note;
    }

    private String buildErrorFix(String code) {
        return "import java.util.*;\n\npublic class Solution {\n    public static void main(String[] args) {\n        Scanner sc = new Scanner(System.in);\n\n        // 1) Read the input safely\n        // 2) Solve the problem step by step\n        // 3) Print the final answer once\n\n        sc.close();\n    }\n}";
    }
}