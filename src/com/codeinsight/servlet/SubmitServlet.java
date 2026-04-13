package com.codeinsight.servlet;

import com.codeinsight.util.DBConnection;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.*;

import java.io.*;
import java.nio.file.*;
import java.sql.*;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.json.JSONArray;
import org.json.JSONObject;

public class SubmitServlet extends HttpServlet {

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {

        response.setContentType("application/json");
        JSONObject result = new JSONObject();

        try {
            BufferedReader reader = request.getReader();
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null)
                sb.append(line);

            JSONObject body = new JSONObject(sb.toString());
            int problemId = body.getInt("problemId");
            String code = body.getString("code");

            Connection conn = DBConnection.getConnection();
            PreparedStatement ps = conn.prepareStatement(
                    "SELECT input, expected FROM test_cases WHERE problem_id = ?");
            ps.setInt(1, problemId);
            ResultSet rs = ps.executeQuery();

            JSONArray testCases = new JSONArray();
            while (rs.next()) {
                testCases.put(new JSONObject()
                        .put("input", rs.getString("input"))
                        .put("expected", rs.getString("expected")));
            }

            // get problem title
            PreparedStatement ps2 = conn.prepareStatement(
                    "SELECT title FROM problems WHERE id = ?");
            ps2.setInt(1, problemId);
            ResultSet rs2 = ps2.executeQuery();
            String problemTitle = rs2.next() ? rs2.getString("title") : "Problem";

            JudgeResult jr = judge(code, testCases);

            result.put("success", true);
            result.put("verdict", jr.verdict);
            result.put("message", jr.message);
            result.put("runtime", jr.runtimeMs + " ms");
            result.put("problemTitle", problemTitle);

        } catch (Exception e) {
            result.put("success", false);
            result.put("verdict", "Error");
            result.put("message", e.getMessage());
        }

        response.getWriter().print(result);
    }

    private JudgeResult judge(String userCode, JSONArray testCases) {
        try {
            Path tempDir = Files.createTempDirectory("judge_" + UUID.randomUUID());
            Path javaFile = tempDir.resolve("Solution.java");
            Files.writeString(javaFile, userCode);

            // Compile
            Process compile = new ProcessBuilder("javac", javaFile.toString())
                    .directory(tempDir.toFile())
                    .redirectErrorStream(true)
                    .start();

            if (!compile.waitFor(10, TimeUnit.SECONDS) || compile.exitValue() != 0) {
                String err = new String(compile.getInputStream().readAllBytes());
                return new JudgeResult("Compilation Error", err, 0);
            }

            long totalTime = 0;

            for (int i = 0; i < testCases.length(); i++) {
                JSONObject tc = testCases.getJSONObject(i);
                String rawInput = tc.getString("input").trim();
                String expected = tc.getString("expected").trim();

                long start = System.currentTimeMillis();

                Process run = new ProcessBuilder("java", "Solution")
                        .directory(tempDir.toFile())
                        .redirectErrorStream(false)
                        .start();

                BufferedWriter writer = new BufferedWriter(
                        new OutputStreamWriter(run.getOutputStream()));
                writer.write(rawInput);
                writer.newLine();
                writer.flush();
                writer.close();

                boolean finished = run.waitFor(10, TimeUnit.SECONDS);
                long time = System.currentTimeMillis() - start;
                totalTime += time;

                if (!finished) {
                    run.destroyForcibly();
                    return new JudgeResult("TLE", "Time Limit Exceeded on test " + (i + 1), (int) time);
                }

                String output = new String(run.getInputStream().readAllBytes()).trim();
                System.out.println("TC " + (i+1) + " INPUT: " + rawInput);
                System.out.println("TC " + (i+1) + " OUTPUT: " + output);
                System.out.println("TC " + (i+1) + " EXPECTED: " + expected);

                if (!normalize(output).equals(normalize(expected))) {
                    return new JudgeResult("Wrong Answer",
                            "Test " + (i + 1) + "\nExpected: " + expected + "\nGot: " + output,
                            (int) time);
                }
            }

            return new JudgeResult("Accepted", "All test cases passed",
                    (int) (totalTime / Math.max(testCases.length(), 1)));

        } catch (Exception e) {
            return new JudgeResult("Error", e.getMessage(), 0);
        }
    }

    private String normalize(String s) {
        return s.replaceAll("\\s+", "").toLowerCase();
    }

    private static class JudgeResult {
        String verdict;
        String message;
        int runtimeMs;

        JudgeResult(String v, String m, int r) {
            verdict = v;
            message = m;
            runtimeMs = r;
        }
    }
}