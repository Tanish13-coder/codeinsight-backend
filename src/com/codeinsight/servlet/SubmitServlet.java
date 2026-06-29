
package com.codeinsight.servlet;

import com.codeinsight.util.CodeRunner;
import com.codeinsight.util.CodeRunner.RunResult;
import com.codeinsight.util.DBConnection;
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
import java.sql.SQLException;
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
            result.put("message", "Please log in to submit code.");
            out.print(result);
            out.flush();
            return;
        }

        int userId = (int) session.getAttribute("userId");
        String username = session.getAttribute("username") != null
                ? session.getAttribute("username").toString()
                : "unknown";

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
            out.print(result);
            out.flush();
            return;
        }

        int problemId = body.optInt("problemId", -1);
        String code = body.optString("code", "").trim();
        String language = body.optString("language", "java").trim();

        if (problemId <= 0 || code.isEmpty()) {
            response.setStatus(400);
            result.put("success", false);
            result.put("message", "problemId and code are required.");
            out.print(result);
            out.flush();
            return;
        }

        try (Connection conn = DBConnection.getConnection()) {
            if (conn == null) {
                response.setStatus(500);
                result.put("success", false);
                result.put("message", "Database connection unavailable.");
                out.print(result);
                out.flush();
                return;
            }

            String problemTitle = null;
            PreparedStatement probPs = conn.prepareStatement(
                    "SELECT title FROM problems WHERE id = ?");
            probPs.setInt(1, problemId);
            ResultSet probRs = probPs.executeQuery();
            if (probRs.next()) {
                problemTitle = probRs.getString("title");
            }
            probRs.close();
            probPs.close();

            if (problemTitle == null) {
                response.setStatus(404);
                result.put("success", false);
                result.put("message", "Problem not found.");
                out.print(result);
                out.flush();
                return;
            }

            JSONArray testCases = new JSONArray();
            PreparedStatement tcPs = conn.prepareStatement(
                    "SELECT input, expected FROM test_cases WHERE problem_id = ?");
            tcPs.setInt(1, problemId);
            ResultSet tcRs = tcPs.executeQuery();
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
            String verdict = "Accepted";
            String runOutput = "";
            String runError = "";
            long totalRuntime = 0;

            if (total == 0) {
                RunResult runResult = CodeRunner.run(code, "");
                totalRuntime = runResult.runtimeMs;
                runError = runResult.error;
                runOutput = runResult.output;
                if (!runResult.success) {
                    verdict = runResult.verdict;
                }
            } else {
                for (int i = 0; i < total; i++) {
                    JSONObject tc = testCases.getJSONObject(i);
                    String inputValue = tc.optString("input", "");
                    String expected = tc.optString("expected", "");

                    RunResult runResult = CodeRunner.run(code, inputValue);
                    totalRuntime += runResult.runtimeMs;
                    runOutput = runResult.output;
                    runError = runResult.error;

                    if (!runResult.success) {
                        verdict = runResult.verdict;
                        break;
                    }

                    if (!normalize(runResult.output).equals(normalize(expected))) {
                        verdict = "Wrong Answer";
                        runOutput = "Expected: " + expected + "\nGot: " + runResult.output;
                        break;
                    }
                    passed++;
                }
            }

            if ("Accepted" .equals(verdict) && total > 0 && passed < total) {
                verdict = "Wrong Answer";
            }

            PreparedStatement insertPs = conn.prepareStatement(
                    "INSERT INTO submissions (user_id, problem_id, code, language, verdict, runtime_ms) VALUES (?, ?, ?, ?, ?, ?)");
            insertPs.setInt(1, userId);
            insertPs.setInt(2, problemId);
            insertPs.setString(3, code);
            insertPs.setString(4, language);
            insertPs.setString(5, verdict);
            insertPs.setLong(6, totalRuntime);
            insertPs.executeUpdate();
            insertPs.close();

            if ("Accepted".equals(verdict) && isFirstAcceptedSubmission(conn, userId, problemId)) {
                updateLeaderboard(conn, userId);
            }

            result.put("success", true);
            result.put("verdict", verdict);
            result.put("message", buildMessage(verdict, passed, total, totalRuntime));
            result.put("runtime", totalRuntime > 0 ? totalRuntime + " ms" : "-");
            result.put("passed", passed);
            result.put("total", total);
            result.put("problemTitle", problemTitle);
            result.put("output", runOutput);
            result.put("error", runError);
        } catch (SQLException e) {
            response.setStatus(500);
            result.put("success", false);
            result.put("message", "Database error: " + e.getMessage());
            System.err.println("[Submit] SQL error: " + e.getMessage());
        } catch (Exception e) {
            response.setStatus(500);
            result.put("success", false);
            result.put("message", "Server error: " + e.getMessage());
            System.err.println("[Submit] Error: " + e.getMessage());
        }

        out.print(result);
        out.flush();
    }

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response)
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
            result.put("message", "Please log in.");
            out.print(result);
            out.flush();
            return;
        }

        int userId = (int) session.getAttribute("userId");
        String role = session.getAttribute("role") != null
                ? session.getAttribute("role").toString()
                : "user";

        try (Connection conn = DBConnection.getConnection()) {
            if (conn == null) {
                response.setStatus(500);
                result.put("success", false);
                result.put("message", "Database connection unavailable.");
                out.print(result);
                out.flush();
                return;
            }

            String sql;
            PreparedStatement ps;
            if ("admin".equals(role)) {
                sql = "SELECT s.id, u.username, p.title AS problem, p.difficulty, "
                        + "s.verdict, s.language, s.runtime_ms, s.created_at "
                        + "FROM submissions s "
                        + "JOIN users u ON s.user_id = u.id "
                        + "JOIN problems p ON s.problem_id = p.id "
                        + "ORDER BY s.created_at DESC LIMIT 200";
                ps = conn.prepareStatement(sql);
            } else {
                sql = "SELECT s.id, u.username, p.title AS problem, p.difficulty, "
                        + "s.verdict, s.language, s.runtime_ms, s.created_at "
                        + "FROM submissions s "
                        + "JOIN users u ON s.user_id = u.id "
                        + "JOIN problems p ON s.problem_id = p.id "
                        + "WHERE s.user_id = ? "
                        + "ORDER BY s.created_at DESC LIMIT 50";
                ps = conn.prepareStatement(sql);
                ps.setInt(1, userId);
            }

            ResultSet rs = ps.executeQuery();
            JSONArray submissions = new JSONArray();
            while (rs.next()) {
                submissions.put(new JSONObject()
                        .put("id", rs.getInt("id"))
                        .put("username", rs.getString("username"))
                        .put("problem", rs.getString("problem"))
                        .put("difficulty", rs.getString("difficulty"))
                        .put("verdict", rs.getString("verdict"))
                        .put("language", rs.getString("language"))
                        .put("runtime", rs.getString("runtime_ms"))
                        .put("createdAt", rs.getString("created_at")));
            }
            rs.close();
            ps.close();

            result.put("success", true);
            result.put("submissions", submissions);
        } catch (SQLException e) {
            response.setStatus(500);
            result.put("success", false);
            result.put("message", "Database error: " + e.getMessage());
            System.err.println("[Submit] SQL error: " + e.getMessage());
        }

        out.print(result);
        out.flush();
    }

    private boolean isFirstAcceptedSubmission(Connection conn, int userId, int problemId) throws SQLException {
        String sql = "SELECT COUNT(*) AS cnt FROM submissions "
                + "WHERE user_id = ? AND problem_id = ? AND verdict = 'Accepted'";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, userId);
            ps.setInt(2, problemId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getInt("cnt") == 0;
            }
        }
    }

    private void updateLeaderboard(Connection conn, int userId) throws SQLException {
        String sql = "INSERT INTO leaderboard (user_id, score, solved) VALUES (?, 100, 1) "
                + "ON DUPLICATE KEY UPDATE score = score + 100, solved = solved + 1";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, userId);
            ps.executeUpdate();
        }
    }

    private String buildMessage(String verdict, int passed, int total, long runtime) {
        if ("Accepted".equals(verdict)) {
            return total > 0
                    ? "All " + total + " test cases passed! Runtime: " + runtime + " ms"
                    : "Code compiled and ran successfully!";
        }
        if ("Wrong Answer".equals(verdict)) {
            return "Your solution did not match the expected output.";
        }
        if ("Compilation Error".equals(verdict)) {
            return "Your code failed to compile. Please check your syntax and try again.";
        }
        if ("TLE".equals(verdict)) {
            return "Time Limit Exceeded. Try to optimize your code.";
        }
        if ("Runtime Error".equals(verdict)) {
            return "Your code threw a runtime exception. Check for invalid operations or null values.";
        }
        return "Submission processed. Verdict: " + verdict;
    }

    private String normalize(String s) {
        return s == null ? "" : s.replaceAll("\\s+", "").toLowerCase();
    }

    private void setCorsHeaders(HttpServletResponse response) {
        String origin = System.getenv("FRONTEND_URL") != null
                ? System.getenv("FRONTEND_URL")
                : "http://localhost:5173";
        response.setHeader("Access-Control-Allow-Origin", origin);
        response.setHeader("Access-Control-Allow-Credentials", "true");
    }
}
