package com.codeinsight.servlet;

import com.codeinsight.util.DBConnection;
import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
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

import org.json.JSONArray;
import org.json.JSONObject;

public class ProblemsServlet extends HttpServlet {

    // ── GET: fetch all problems (for users) or single problem by id ──
    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {

        setCorsHeaders(response);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");

        PrintWriter out = response.getWriter();
        String idParam = request.getParameter("id");

        try {
            Connection conn = DBConnection.getConnection();

            // Single problem fetch
            if (idParam != null) {
                int problemId = Integer.parseInt(idParam);

                String sql = "SELECT * FROM problems WHERE id = ?";
                PreparedStatement ps = conn.prepareStatement(sql);
                ps.setInt(1, problemId);
                ResultSet rs = ps.executeQuery();

                if (rs.next()) {
                    JSONObject prob = buildProblemObject(rs);

                    // Fetch test cases for this problem
                    String tcSql = "SELECT input, expected FROM test_cases WHERE problem_id = ?";
                    PreparedStatement tcPs = conn.prepareStatement(tcSql);
                    tcPs.setInt(1, problemId);
                    ResultSet tcRs = tcPs.executeQuery();

                    JSONArray testCases = new JSONArray();
                    while (tcRs.next()) {
                        JSONObject tc = new JSONObject();
                        tc.put("input", tcRs.getString("input"));
                        tc.put("expected", tcRs.getString("expected"));
                        testCases.put(tc);
                    }
                    prob.put("testCases", testCases);
                    tcRs.close();
                    tcPs.close();

                    out.print(prob);
                } else {
                    response.setStatus(404);
                    out.print(new JSONObject()
                            .put("success", false)
                            .put("message", "Problem not found."));
                }
                rs.close();
                ps.close();

            } else {
                // All problems list
                String difficulty = request.getParameter("difficulty");
                String search = request.getParameter("search");

                StringBuilder sql = new StringBuilder("SELECT id, title, difficulty, tags FROM problems WHERE 1=1");
                if (difficulty != null && !difficulty.equals("All")) {
                    sql.append(" AND difficulty = '").append(difficulty).append("'");
                }
                if (search != null && !search.isEmpty()) {
                    sql.append(" AND title LIKE '%").append(search.replace("'", "''")).append("%'");
                }
                sql.append(" ORDER BY id ASC");

                Statement stmt = conn.createStatement();
                ResultSet rs = stmt.executeQuery(sql.toString());

                JSONArray problems = new JSONArray();
                while (rs.next()) {
                    JSONObject p = new JSONObject();
                    p.put("id", rs.getInt("id"));
                    p.put("title", rs.getString("title"));
                    p.put("difficulty", rs.getString("difficulty"));
                    p.put("tags", rs.getString("tags") != null
                            ? rs.getString("tags")
                            : "");
                    problems.put(p);
                }
                rs.close();
                stmt.close();

                JSONObject result = new JSONObject();
                result.put("success", true);
                result.put("problems", problems);
                out.print(result);
            }

        } catch (Exception e) {
            response.setStatus(500);
            out.print(new JSONObject()
                    .put("success", false)
                    .put("message", "Server error: " + e.getMessage()));
            System.err.println("[Problems] GET error: " + e.getMessage());
        }

        out.flush();
    }

    // ── POST: admin uploads a new problem ──
    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {

        setCorsHeaders(response);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");

        PrintWriter out = response.getWriter();
        JSONObject result = new JSONObject();

        // Check admin session
        HttpSession session = request.getSession(false);
        if (session == null || !"admin".equals(session.getAttribute("role"))) {
            response.setStatus(403);
            result.put("success", false);
            result.put("message", "Access denied. Admins only.");
            out.print(result);
            return;
        }

        // Read body
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

            String sql = "INSERT INTO problems (title, description, difficulty, tags, example_input, example_output, constraints) "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?)";
            PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, title);
            ps.setString(2, description);
            ps.setString(3, difficulty);
            ps.setString(4, tags);
            ps.setString(5, exInput);
            ps.setString(6, exOutput);
            ps.setString(7, constraints);
            ps.executeUpdate();

            // Get generated problem ID
            ResultSet keys = ps.getGeneratedKeys();
            int newId = -1;
            if (keys.next())
                newId = keys.getInt(1);
            keys.close();

            // Insert test cases if provided
            JSONArray testCases = body.optJSONArray("testCases");
            if (testCases != null && newId > 0) {
                String tcSql = "INSERT INTO test_cases (problem_id, input, expected) VALUES (?, ?, ?)";
                PreparedStatement tcPs = conn.prepareStatement(tcSql);
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
            System.out.println("[Problems] New problem uploaded: " + title + " (id=" + newId + ")");

        } catch (Exception e) {
            response.setStatus(500);
            result.put("success", false);
            result.put("message", "Server error: " + e.getMessage());
            System.err.println("[Problems] POST error: " + e.getMessage());
        }

        out.print(result);
        out.flush();
    }

    // ── DELETE: admin deletes a problem ──
    @Override
    protected void doDelete(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {

        setCorsHeaders(response);
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
            result.put("message", "Problem ID is required.");
            out.print(result);
            return;
        }

        try {
            int problemId = Integer.parseInt(idParam);
            Connection conn = DBConnection.getConnection();

            String sql = "DELETE FROM problems WHERE id = ?";
            PreparedStatement ps = conn.prepareStatement(sql);
            ps.setInt(1, problemId);
            int rows = ps.executeUpdate();
            ps.close();

            if (rows > 0) {
                result.put("success", true);
                result.put("message", "Problem deleted.");
                System.out.println("[Problems] Deleted problem id=" + problemId);
            } else {
                response.setStatus(404);
                result.put("success", false);
                result.put("message", "Problem not found.");
            }

        } catch (Exception e) {
            response.setStatus(500);
            result.put("success", false);
            result.put("message", "Server error: " + e.getMessage());
            System.err.println("[Problems] DELETE error: " + e.getMessage());
        }

        out.print(result);
        out.flush();
    }

    @Override
    protected void doOptions(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        setCorsHeaders(response);
        response.setHeader("Access-Control-Allow-Methods", "GET, POST, DELETE, OPTIONS");
        response.setHeader("Access-Control-Allow-Headers", "Content-Type");
        response.setStatus(200);
    }

    // ── Helpers ──
    private JSONObject buildProblemObject(ResultSet rs) throws Exception {
        JSONObject p = new JSONObject();
        p.put("id", rs.getInt("id"));
        p.put("title", rs.getString("title"));
        p.put("description", rs.getString("description"));
        p.put("difficulty", rs.getString("difficulty"));
        p.put("tags", rs.getString("tags") != null ? rs.getString("tags") : "");
        p.put("example_input", rs.getString("example_input") != null ? rs.getString("example_input") : "");
        p.put("example_output", rs.getString("example_output") != null ? rs.getString("example_output") : "");
        p.put("constraints", rs.getString("constraints") != null ? rs.getString("constraints") : "");
        return p;
    }

    private void setCorsHeaders(HttpServletResponse response) {
        response.setHeader("Access-Control-Allow-Origin", "http://localhost:3000");
        response.setHeader("Access-Control-Allow-Credentials", "true");
    }
}