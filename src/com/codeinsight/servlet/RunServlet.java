package com.codeinsight.servlet;

import com.codeinsight.util.CodeRunner;
import com.codeinsight.util.CodeRunner.RunResult;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintWriter;

import org.json.JSONObject;

public class RunServlet extends HttpServlet {

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
            result.put("message", "Please log in to run code.");
            out.print(result);
            return;
        }

        StringBuilder sb = new StringBuilder();
        BufferedReader reader = request.getReader();
        String line;
        while ((line = reader.readLine()) != null) sb.append(line);

        try {
            JSONObject body = new JSONObject(sb.toString());
            String code  = body.optString("code", "").trim();
            String input = body.optString("input", "");

            if (code.isEmpty()) {
                response.setStatus(400);
                result.put("success", false);
                result.put("message", "Code is required.");
                out.print(result);
                return;
            }

            RunResult runResult = CodeRunner.run(code, input);

            result.put("success", runResult.success);
            result.put("output",  runResult.output);
            result.put("error",   runResult.error);
            result.put("verdict", runResult.verdict);
            result.put("runtime", runResult.runtimeMs > 0 ? runResult.runtimeMs + " ms" : "-");

            System.out.println("[Run] User=" + session.getAttribute("username")
                    + " | Verdict=" + runResult.verdict
                    + " | Runtime=" + runResult.runtimeMs + "ms");

        } catch (Exception e) {
            response.setStatus(500);
            result.put("success", false);
            result.put("message", "Server error: " + e.getMessage());
            System.err.println("[Run] Error: " + e.getMessage());
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
