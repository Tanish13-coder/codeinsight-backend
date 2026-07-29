package com.codeinsight.servlet;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

public class AIOptimizeServlet extends HttpServlet {

    private static final String OPTIMIZE_SERVICE_URL;
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();

    static {
        String url = System.getenv("AI_OPTIMIZE_URL");
        OPTIMIZE_SERVICE_URL = (url != null && !url.isBlank()) ? url.trim() : "http://localhost:8001/analyze";
}

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
            result.put("message", "Login required.");
            out.print(result);
            return;
        }

        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = request.getReader()) {
            String line;
            while ((line = reader.readLine()) != null) sb.append(line);
        }

        JSONObject body;
        try {
            body = new JSONObject(sb.toString());
        } catch (Exception e) {
            response.setStatus(400);
            result.put("success", false);
            result.put("message", "Invalid JSON.");
            out.print(result);
            return;
        }

        String code = body.optString("code", "").trim();
        String language = body.optString("language", "Java").trim();

        if (code.isEmpty()) {
            response.setStatus(400);
            result.put("success", false);
            result.put("message", "Code is required.");
            out.print(result);
            return;
        }

        try {
            JSONObject payload = new JSONObject();
            payload.put("code", code);
            payload.put("language", language);

            HttpRequest httpRequest = HttpRequest.newBuilder()
                    .uri(URI.create(OPTIMIZE_SERVICE_URL))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(120))
                    .POST(HttpRequest.BodyPublishers.ofString(payload.toString()))
                    .build();

            HttpResponse<String> httpResponse = HTTP_CLIENT.send(
                    httpRequest, HttpResponse.BodyHandlers.ofString());

            System.out.println("[AIOptimize] Service status: " + httpResponse.statusCode());

            if (httpResponse.statusCode() != 200) {
                response.setStatus(502);
                result.put("success", false);
                result.put("message", "Optimization service returned HTTP " + httpResponse.statusCode()
                        + ". Make sure the Python service is running on " + OPTIMIZE_SERVICE_URL);
                out.print(result);
                return;
            }

            JSONObject serviceResult = new JSONObject(httpResponse.body());

            result.put("success", true);
            result.put("time_complexity", serviceResult.optString("time_complexity", ""));
            result.put("space_complexity", serviceResult.optString("space_complexity", ""));
            result.put("logic_faults", serviceResult.optJSONArray("logic_faults"));
            result.put("improvement_explanation", serviceResult.optString("improvement_explanation", ""));
            result.put("corrected_code", serviceResult.optString("corrected_code", ""));

            System.out.println("[AIOptimize] Analysis done for user: " + session.getAttribute("username"));

        } catch (java.net.ConnectException e) {
            response.setStatus(503);
            result.put("success", false);
            result.put("message", "Cannot connect to optimization service at " + OPTIMIZE_SERVICE_URL
                    + ". Start it with: uvicorn main:app --reload");
        } catch (Exception e) {
            response.setStatus(500);
            result.put("success", false);
            result.put("message", "Optimization service error: " + e.getMessage());
            System.err.println("[AIOptimize] Error: " + e.getMessage());
        }

        out.print(result);
        out.flush();
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
