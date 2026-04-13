package com.codeinsight.servlet;

import com.codeinsight.util.DBConnection;
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
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

import org.json.JSONArray;
import org.json.JSONObject;

public class SubmitServlet extends HttpServlet {

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
            result.put("message", "Please log in to submit.");
            out.print(result);
            return;
        }

        int userId = (int) session.getAttribute("userId");
        String username = (String) session.getAttribute("username");

        StringBuilder sb = new StringBuilder();
        BufferedReader reader = request.getReader();
        String line;
        while ((line = reader.readLine()) != null)
            sb.append(line);

        try {
            JSONObject body = new JSONObject(sb.toString());
            int problemId = body.optInt("problemId", -1);
            String code = body.optString("code", "").trim();
            String language = body.optString("language", "Java").trim();

            if (problemId == -1 || code.isEmpty()) {
                response.setStatus(400);
                result.put("success", false);
                result.put("message", "Problem ID and code are required.");
                out.print(result);
                return;
            }

            Connection conn = DBConnection.getConnection();

            // ── Fetch problem ──
            String probSql = "SELECT id, title, difficulty FROM problems WHERE id = ?";
            PreparedStatement probPs = conn.prepareStatement(probSql);
            probPs.setInt(1, problemId);
            ResultSet probRs = probPs.executeQuery();

            if (!probRs.next()) {
                response.setStatus(404);
                result.put("success", false);
                result.put("message", "Problem not found.");
                probRs.close();
                probPs.close();
                out.print(result);
                return;
            }

            String problemTitle = probRs.getString("title");
            probRs.close();
            probPs.close();

            // ── Fetch test cases ──
            String tcSql = "SELECT input, expected FROM test_cases WHERE problem_id = ?";
            PreparedStatement tcPs = conn.prepareStatement(tcSql);
            tcPs.setInt(1, problemId);
            ResultSet tcRs = tcPs.executeQuery();

            JSONArray testCases = new JSONArray();
            while (tcRs.next()) {
                JSONObject tc = new JSONObject();
                tc.put("input", tcRs.getString("input") != null ? tcRs.getString("input") : "");
                tc.put("expected", tcRs.getString("expected") != null ? tcRs.getString("expected") : "");
                testCases.put(tc);
            }
            tcRs.close();
            tcPs.close();

            int total = testCases.length();
            int passed = 0;
            String verdict = "Wrong Answer";
            long totalRuntime = 0;
            String runOutput = "";
            String runError = "";

            System.out.println("[Submit] Judging: " + problemTitle
                    + " | User: " + username
                    + " | Test cases: " + total);

            if (total == 0) {
                // No test cases — compile and run, accept if no error
                RunResult runResult = CodeRunner.run(code, "");
                totalRuntime = runResult.runtimeMs;
                runError = runResult.error;
                if (!runResult.success) {
                    verdict = runResult.verdict;
                    runOutput = runResult.output;
                } else {
                    verdict = "Accepted";
                    runOutput = runResult.output;
                }

            } else {
                // ── Run once per test case with its specific input ──
                for (int i = 0; i < total; i++) {
                    JSONObject tc = testCases.getJSONObject(i);
                    String tcInput    = tc.getString("input").trim();
                    String tcExpected = tc.getString("expected").trim();

                    RunResult runResult = CodeRunner.run(code, tcInput);
                    totalRuntime += runResult.runtimeMs;

                    System.out.println("[Submit] TC " + (i + 1)
                            + " | Input: " + tcInput
                            + " | Expected: " + tcExpected
                            + " | Got: " + runResult.output.trim()
                            + " | Verdict: " + runResult.verdict);

                    if (!runResult.success) {
                        verdict    = runResult.verdict;
                        runError   = runResult.error;
                        runOutput  = runResult.output;
                        break;
                    }

                    String actual = runResult.output.trim();
                    if (actual.equals(tcExpected)) {
                        passed++;
                    } else {
                        verdict   = "Wrong Answer";
                        runOutput = "Test Case " + (i + 1) + "\nInput:    " + tcInput
                                  + "\nExpected: " + tcExpected
                                  + "\nGot:      " + actual;
                        break;
                    }
                }

                if (passed == total) {
                    verdict = "Accepted";
                }
            }

            System.out.println("[Submit] Final: " + verdict
                    + " | Passed: " + passed + "/" + total
                    + " | Runtime: " + totalRuntime + "ms");

            // ── Save submission ──
            String insertSql = "INSERT INTO submissions "
                    + "(user_id, problem_id, code, language, verdict, runtime_ms) "
                    + "VALUES (?, ?, ?, ?, ?, ?)";
            PreparedStatement insertPs = conn.prepareStatement(insertSql);
            insertPs.setInt(1, userId);
            insertPs.setInt(2, problemId);
            insertPs.setString(3, code);
            insertPs.setString(4, language);
            insertPs.setString(5, verdict);
            insertPs.setLong(6, totalRuntime);
            insertPs.executeUpdate();
            insertPs.close();

            // ── Update leaderboard on first Accepted ──
            if ("Accepted".equals(verdict)) {
                updateLeaderboard(conn, userId, problemId);
            }

            result.put("success", true);
            result.put("verdict", verdict);
            result.put("runtime", totalRuntime > 0 ? totalRuntime + " ms" : "-");
            result.put("passed", passed);
            result.put("total", total);
            result.put("problemTitle", problemTitle);
            result.put("output", runOutput);
            result.put("error", runError);
            result.put("message", buildMessage(verdict, passed, total, totalRuntime));

        } catch (Exception e) {
            response.setStatus(500);
            result.put("success", false);
            result.put("message", "Server error: " + e.getMessage());
            System.err.println("[Submit] Error: " + e.getMessage());
            e.printStackTrace();
        }

        out.print(result);
        out.flush();
    }

    // ── GET: fetch submissions (admin sees all, user sees own) ──
    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {

        setCorsHeaders(response);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");

        PrintWriter out = response.getWriter();

        HttpSession session = request.getSession(false);
        if (session == null || session.getAttribute("userId") == null) {
            response.setStatus(401);
            out.print(new JSONObject()
                    .put("success", false)
                    .put("message", "Please log in."));
            return;
        }

        int userId = (int) session.getAttribute("userId");
        String role = (String) session.getAttribute("role");

        try {
            Connection conn = DBConnection.getConnection();
            PreparedStatement ps;

            if ("admin".equals(role)) {
                String sql = "SELECT s.id, u.username, p.title AS problem, "
                        + "p.difficulty, s.code, s.verdict, s.language, "
                        + "s.runtime_ms, s.created_at "
                        + "FROM submissions s "
                        + "JOIN users u    ON s.user_id    = u.id "
                        + "JOIN problems p ON s.problem_id = p.id "
                        + "ORDER BY s.created_at DESC LIMIT 200";
                ps = conn.prepareStatement(sql);
            } else {
                String sql = "SELECT s.id, u.username, p.title AS problem, "
                        + "p.difficulty, s.code, s.verdict, s.language, "
                        + "s.runtime_ms, s.created_at "
                        + "FROM submissions s "
                        + "JOIN users u    ON s.user_id    = u.id "
                        + "JOIN problems p ON s.problem_id = p.id "
                        + "WHERE s.user_id = ? "
                        + "ORDER BY s.created_at DESC LIMIT 50";
                ps = conn.prepareStatement(sql);
                ps.setInt(1, userId);
            }

            ResultSet rs = ps.executeQuery();
            JSONArray submissions = new JSONArray();

            while (rs.next()) {
                JSONObject sub = new JSONObject();
                sub.put("id", rs.getInt("id"));
                sub.put("username", rs.getString("username"));
                sub.put("problem", rs.getString("problem"));
                sub.put("difficulty", rs.getString("difficulty"));
                sub.put("code", rs.getString("code"));
                sub.put("verdict", rs.getString("verdict"));
                sub.put("language", rs.getString("language"));
                sub.put("runtime", rs.getInt("runtime_ms") > 0
                        ? rs.getInt("runtime_ms") + " ms"
                        : "-");
                sub.put("time", timeAgo(rs.getTimestamp("created_at").getTime()));
                submissions.put(sub);
            }

            rs.close();
            ps.close();

            out.print(new JSONObject()
                    .put("success", true)
                    .put("submissions", submissions));

        } catch (Exception e) {
            response.setStatus(500);
            out.print(new JSONObject()
                    .put("success", false)
                    .put("message", "Server error: " + e.getMessage()));
            System.err.println("[Submit] GET error: " + e.getMessage());
        }

        out.flush();
    }

    @Override
    protected void doOptions(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        setCorsHeaders(response);
        response.setHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        response.setHeader("Access-Control-Allow-Headers", "Content-Type");
        response.setStatus(200);
    }

    private void updateLeaderboard(Connection conn, int userId, int problemId) {
        try {
            // Count how many Accepted submissions exist for this user+problem AFTER this insert
            String checkSql = "SELECT COUNT(*) AS cnt FROM submissions "
                    + "WHERE user_id = ? AND problem_id = ? AND verdict = 'Accepted'";
            PreparedStatement checkPs = conn.prepareStatement(checkSql);
            checkPs.setInt(1, userId);
            checkPs.setInt(2, problemId);
            ResultSet checkRs = checkPs.executeQuery();

            int count = 0;
            if (checkRs.next()) count = checkRs.getInt("cnt");
            checkRs.close();
            checkPs.close();

            // Only award points on the FIRST accepted submission for this problem
            if (count == 1) {
                String upsert = "INSERT INTO leaderboard (user_id, score, solved) "
                        + "VALUES (?, 100, 1) "
                        + "ON DUPLICATE KEY UPDATE "
                        + "score = score + 100, solved = solved + 1";
                PreparedStatement lbPs = conn.prepareStatement(upsert);
                lbPs.setInt(1, userId);
                lbPs.executeUpdate();
                lbPs.close();
                System.out.println("[Submit] Leaderboard +100pts for userId=" + userId
                        + " problemId=" + problemId);
            }
        } catch (Exception e) {
            System.err.println("[Submit] Leaderboard error: " + e.getMessage());
        }
    }

    private String buildMessage(String verdict, int passed, int total, long runtime) {
        switch (verdict) {
            case "Accepted":
                return total > 0
                    ? "All " + total + " test cases passed! Runtime: " + runtime + " ms"
                    : "Code compiled and ran successfully! Runtime: " + runtime + " ms";
            case "Wrong Answer":
                return "Failed on test case " + (passed + 1) + " of " + total + ".";
            case "Compilation Error":
                return "Your code failed to compile. Check for syntax errors.";
            case "TLE":
                return "Time Limit Exceeded. Optimize your solution.";
            case "Runtime Error":
                return "Your code threw a runtime exception.";
            default:
                return "Submission failed. Please try again.";
        }
    }

    private String timeAgo(long timeMs) {
        long diff = System.currentTimeMillis() - timeMs;
        long minutes = diff / 60000;
        long hours = minutes / 60;
        long days = hours / 24;
        if (minutes < 1) return "just now";
        if (minutes < 60) return minutes + "m ago";
        if (hours < 24)   return hours + "h ago";
        return days + "d ago";
    }

    private void setCorsHeaders(HttpServletResponse response) {
        String origin = System.getenv("FRONTEND_URL") != null
                ? System.getenv("FRONTEND_URL")
                : "http://localhost:5173";
        response.setHeader("Access-Control-Allow-Origin", origin);
        response.setHeader("Access-Control-Allow-Credentials", "true");
    }
}
