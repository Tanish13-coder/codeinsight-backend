package com.codeinsight.servlet;

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
import java.sql.Statement;
import java.util.Set;

import org.json.JSONArray;
import org.json.JSONObject;

public class ProblemsServlet extends HttpServlet {

    // ── FIX: support all dev ports ──
    private static final Set<String> ALLOWED = Set.of(
            "http://localhost:3000",
            "http://localhost:5173",
            "http://localhost:5174",
            "http://localhost:4173");

    private void setCorsHeaders(HttpServletRequest request, HttpServletResponse response) {
        String origin = request.getHeader("Origin");
        if (origin != null && ALLOWED.contains(origin)) {
            response.setHeader("Access-Control-Allow-Origin", origin);
            response.setHeader("Access-Control-Allow-Credentials", "true");
        }
    }

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {

        setCorsHeaders(request, response);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");

        PrintWriter out = response.getWriter();
        String idParam = request.getParameter("id");

        try {
            Connection conn = DBConnection.getConnection();

            if (conn == null || conn.isClosed()) {
                out.print(buildFallbackResponse(idParam));
                out.flush();
                return;
            }

            if (idParam != null) {
                int problemId = Integer.parseInt(idParam);
                PreparedStatement ps = conn.prepareStatement("SELECT * FROM problems WHERE id = ?");
                ps.setInt(1, problemId);
                ResultSet rs = ps.executeQuery();

                if (rs.next()) {
                    JSONObject prob = buildProblemObject(rs);

                    PreparedStatement tcPs = conn.prepareStatement(
                            "SELECT input, expected FROM test_cases WHERE problem_id = ?");
                    tcPs.setInt(1, problemId);
                    ResultSet tcRs = tcPs.executeQuery();
                    JSONArray testCases = new JSONArray();
                    while (tcRs.next()) {
                        testCases.put(new JSONObject()
                                .put("input", tcRs.getString("input"))
                                .put("expected", tcRs.getString("expected")));
                    }
                    prob.put("testCases", testCases);
                    tcRs.close();
                    tcPs.close();
                    out.print(new JSONObject().put("success", true).put("problem", prob));
                } else {
                    out.print(buildFallbackResponse(idParam));
                }
                rs.close();
                ps.close();

            } else {
                String sql = "SELECT id, title, difficulty, tags FROM problems ORDER BY id ASC";
                Statement stmt = conn.createStatement();
                ResultSet rs = stmt.executeQuery(sql);

                JSONArray problems = new JSONArray();
                while (rs.next()) {
                    problems.put(new JSONObject()
                            .put("id", rs.getInt("id"))
                            .put("title", rs.getString("title"))
                            .put("difficulty", rs.getString("difficulty"))
                            .put("tags", rs.getString("tags") != null ? rs.getString("tags") : ""));
                }
                rs.close();
                stmt.close();

                if (problems.length() == 0) {
                    out.print(buildFallbackResponse(null));
                } else {
                    out.print(new JSONObject().put("success", true).put("problems", problems));
                }
            }

        } catch (Exception e) {
            out.print(buildFallbackResponse(idParam));
            System.err.println("[Problems] GET error: " + e.getMessage());
        }

        out.flush();
    }

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {

        setCorsHeaders(request, response);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");

        PrintWriter out = response.getWriter();
        JSONObject result = new JSONObject();

        // HttpSession session = request.getSession(false);
        // if (session == null || !"admin".equals(session.getAttribute("role"))) {
        // response.setStatus(403);
        // result.put("success", false);
        // result.put("message", "Access denied. Admins only.");
        // out.print(result);
        // return;
        // }

        StringBuilder sb = new StringBuilder();
        BufferedReader reader = request.getReader();
        String line;
        while ((line = reader.readLine()) != null)
            sb.append(line);

        try {
            JSONObject body = new JSONObject(sb.toString());
            String title = body.optString("title", "").trim();
            String description = body.optString("description", "").trim();
            String difficulty = body.optString("difficulty", "Easy").trim();
            String tags = body.optString("tags", "").trim();
            String exInput = body.optString("example_input", "").trim();
            String exOutput = body.optString("example_output", "").trim();
            String constraints = body.optString("constraints", "").trim();

            if (title.isEmpty() || description.isEmpty()) {
                response.setStatus(400);
                result.put("success", false);
                result.put("message", "Title and description are required.");
                out.print(result);
                return;
            }

            Connection conn = DBConnection.getConnection();
            PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO problems (title, description, difficulty, tags, example_input, example_output, constraints) VALUES (?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, title);
            ps.setString(2, description);
            ps.setString(3, difficulty);
            ps.setString(4, tags);
            ps.setString(5, exInput);
            ps.setString(6, exOutput);
            ps.setString(7, constraints);
            ps.executeUpdate();

            ResultSet keys = ps.getGeneratedKeys();
            int newId = keys.next() ? keys.getInt(1) : -1;
            keys.close();

            JSONArray testCases = body.optJSONArray("testCases");
            if (testCases != null && newId > 0) {
                PreparedStatement tcPs = conn.prepareStatement(
                        "INSERT INTO test_cases (problem_id, input, expected) VALUES (?, ?, ?)");
                for (int i = 0; i < testCases.length(); i++) {
                    JSONObject tc = testCases.getJSONObject(i);
                    tcPs.setInt(1, newId);
                    tcPs.setString(2, tc.optString("input", ""));
                    tcPs.setString(3, tc.optString("expected", ""));
                    tcPs.addBatch();
                }
                tcPs.executeBatch();
                tcPs.close();
            }
            ps.close();

            result.put("success", true);
            result.put("message", "Problem uploaded successfully.");
            result.put("problemId", newId);
            System.out.println("[Problems] New problem: " + title + " id=" + newId);

        } catch (Exception e) {
            response.setStatus(500);
            result.put("success", false);
            result.put("message", "Server error: " + e.getMessage());
            System.err.println("[Problems] POST error: " + e.getMessage());
        }

        out.print(result);
        out.flush();
    }

    @Override
    protected void doDelete(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {

        setCorsHeaders(request, response);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");

        PrintWriter out = response.getWriter();
        JSONObject result = new JSONObject();

        HttpSession session = request.getSession(false);
        if (session == null || !"admin".equals(session.getAttribute("role"))) {
            response.setStatus(403);
            result.put("success", false);
            result.put("message", "Access denied. Admins only.");
            out.print(result);
            return;
        }

        String idParam = request.getParameter("id");
        if (idParam == null) {
            response.setStatus(400);
            result.put("success", false);
            result.put("message", "Problem ID required.");
            out.print(result);
            return;
        }

        try {
            int problemId = Integer.parseInt(idParam);
            Connection conn = DBConnection.getConnection();
            PreparedStatement ps = conn.prepareStatement("DELETE FROM problems WHERE id = ?");
            ps.setInt(1, problemId);
            int rows = ps.executeUpdate();
            ps.close();

            if (rows > 0) {
                result.put("success", true);
                result.put("message", "Problem deleted.");
            } else {
                response.setStatus(404);
                result.put("success", false);
                result.put("message", "Problem not found.");
            }

        } catch (Exception e) {
            response.setStatus(500);
            result.put("success", false);
            result.put("message", "Server error: " + e.getMessage());
        }

        out.print(result);
        out.flush();
    }

    @Override
    protected void doOptions(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        setCorsHeaders(request, response);
        response.setHeader("Access-Control-Allow-Methods", "GET, POST, DELETE, OPTIONS");
        response.setHeader("Access-Control-Allow-Headers", "Content-Type");
        response.setStatus(200);
    }

    private JSONObject buildProblemObject(ResultSet rs) throws Exception {
        return new JSONObject()
                .put("id", rs.getInt("id"))
                .put("title", rs.getString("title"))
                .put("description", rs.getString("description"))
                .put("difficulty", rs.getString("difficulty"))
                .put("tags", rs.getString("tags") != null ? rs.getString("tags") : "")
                .put("example_input", rs.getString("example_input") != null ? rs.getString("example_input") : "")
                .put("example_output", rs.getString("example_output") != null ? rs.getString("example_output") : "")
                .put("constraints", rs.getString("constraints") != null ? rs.getString("constraints") : "");
    }

    private JSONObject buildFallbackResponse(String idParam) {
        JSONArray problems = new JSONArray();
        problems.put(problemObject(1, "Two Sum", "Easy", "Arrays, Hash Map", "4\n2 7 11 15\n9", "0 1", "1 <= nums.length <= 10^4"));
        problems.put(problemObject(2, "Valid Parentheses", "Medium", "Stack, Strings", "()[]{}", "true", "1 <= s.length <= 10^4"));
        problems.put(problemObject(3, "Binary Search", "Easy", "Arrays, Binary Search", "5\n1 2 3 4 5\n3", "2", "1 <= nums.length <= 10^5"));
        problems.put(problemObject(4, "Longest Substring Without Repeating Characters", "Hard", "Hash Map, Sliding Window", "abcabcbb", "3", "0 <= s.length <= 5 * 10^4"));

        if (idParam != null) {
            try {
                int requestedId = Integer.parseInt(idParam);
                for (int i = 0; i < problems.length(); i++) {
                    JSONObject p = problems.getJSONObject(i);
                    if (p.getInt("id") == requestedId) {
                        return new JSONObject().put("success", true).put("problem", p);
                    }
                }
            } catch (NumberFormatException ignored) {
            }
            return new JSONObject().put("success", true).put("problem", problems.getJSONObject(0));
        }

        return new JSONObject().put("success", true).put("problems", problems);
    }

    private JSONObject problemObject(int id, String title, String difficulty, String tags, String exampleInput, String exampleOutput, String constraints) {
        return new JSONObject()
                .put("id", id)
                .put("title", title)
                .put("difficulty", difficulty)
                .put("tags", tags)
                .put("description", "Practice this problem in the editor and use the AI Insight panel for guidance.")
                .put("example_input", exampleInput)
                .put("example_output", exampleOutput)
                .put("constraints", constraints);
    }

    private void setCorsHeaders(HttpServletResponse response) {
        String origin = System.getenv("FRONTEND_URL") != null ? System.getenv("FRONTEND_URL") : "http://localhost:5173";
        response.setHeader("Access-Control-Allow-Origin", origin);
        response.setHeader("Access-Control-Allow-Credentials", "true");
    }
}
