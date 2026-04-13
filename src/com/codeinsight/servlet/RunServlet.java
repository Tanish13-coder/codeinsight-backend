package com.codeinsight.servlet;

import com.codeinsight.util.CodeRunner;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.json.JSONObject;

import java.io.*;

public class RunServlet extends HttpServlet {

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {

        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");

        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = request.getReader()) {
            String line;
            while ((line = reader.readLine()) != null)
                sb.append(line);
        }

        PrintWriter out = response.getWriter();
        JSONObject result = new JSONObject();

        try {
            JSONObject body = new JSONObject(sb.toString());
            String code = body.optString("code", "");

            if (code.isEmpty()) {
                response.setStatus(400);
                result.put("verdict", "Error");
                result.put("error", "No code provided.");
                out.print(result);
                return;
            }

            CodeRunner.RunResult runResult = CodeRunner.run(code);

            if ("Compilation Error".equals(runResult.verdict)) {
                result.put("verdict", "Compilation Error");
                result.put("error", runResult.error);
            } else if ("Runtime Error".equals(runResult.verdict)) {
                result.put("verdict", "Runtime Error");
                result.put("error", runResult.error);
                result.put("output", runResult.output != null ? runResult.output : "");
            } else if ("TLE".equals(runResult.verdict)) {
                result.put("verdict", "TLE");
                result.put("error", "Time Limit Exceeded (5s)");
                result.put("output", runResult.output != null ? runResult.output : "");
            } else {
                result.put("verdict", "Success");
                result.put("output", runResult.output != null ? runResult.output : "");
                result.put("runtime", runResult.runtimeMs + "ms");
            }

        } catch (Exception e) {
            response.setStatus(500);
            result.put("verdict", "Error");
            result.put("error", "Server error: " + e.getMessage());
        }

        out.print(result);
        out.flush();
    }

    @Override
    protected void doOptions(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        response.setHeader("Access-Control-Allow-Methods", "POST, OPTIONS");
        response.setHeader("Access-Control-Allow-Headers", "Content-Type");
        response.setStatus(200);
    }
}