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

import org.json.JSONArray;
import org.json.JSONObject;

public class ReviewServlet extends HttpServlet {

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {

        setCorsHeaders(response);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");

        PrintWriter out = response.getWriter();
        JSONObject result = new JSONObject();

        try {
            Connection conn = DBConnection.getConnection();
            String sql = "SELECT id, username, rating, text, role, created_at FROM reviews WHERE approved = true ORDER BY created_at DESC";
            PreparedStatement ps = conn.prepareStatement(sql);
            ResultSet rs = ps.executeQuery();

            JSONArray reviews = new JSONArray();
            while (rs.next()) {
                JSONObject r = new JSONObject();
                r.put("id", rs.getInt("id"));
                r.put("username", rs.getString("username"));
                r.put("rating", rs.getInt("rating"));
                r.put("text", rs.getString("text"));
                r.put("role", rs.getString("role") != null ? rs.getString("role") : "");
                r.put("created_at", rs.getString("created_at") != null ? rs.getString("created_at").substring(0, 10) : "");
                reviews.put(r);
            }
            rs.close();
            ps.close();

            result.put("success", true);
            result.put("reviews", reviews);

        } catch (Exception e) {
            response.setStatus(500);
            result.put("success", false);
            result.put("message", "Server error: " + e.getMessage());
            System.err.println("[Reviews] GET error: " + e.getMessage());
        }

        out.print(result);
        out.flush();
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
            result.put("message", "Login required to submit a review.");
            out.print(result);
            out.flush();
            return;
        }

        String username = (String) session.getAttribute("username");

        StringBuilder sb = new StringBuilder();
        BufferedReader reader = request.getReader();
        String line;
        while ((line = reader.readLine()) != null) sb.append(line);

        try {
            JSONObject body = new JSONObject(sb.toString());
            int rating = body.optInt("rating", 0);
            String text = body.optString("text", "").trim();
            String role = body.optString("role", "").trim();

            if (rating < 1 || rating > 5) {
                response.setStatus(400);
                result.put("success", false);
                result.put("message", "Rating must be between 1 and 5.");
                out.print(result);
                out.flush();
                return;
            }

            if (text.isEmpty()) {
                response.setStatus(400);
                result.put("success", false);
                result.put("message", "Review text cannot be empty.");
                out.print(result);
                out.flush();
                return;
            }

            if (text.length() > 500) {
                response.setStatus(400);
                result.put("success", false);
                result.put("message", "Review text must be 500 characters or fewer.");
                out.print(result);
                out.flush();
                return;
            }

            Connection conn = DBConnection.getConnection();
            String sql = "INSERT INTO reviews (username, rating, text, role, approved) VALUES (?, ?, ?, ?, false)";
            PreparedStatement ps = conn.prepareStatement(sql);
            ps.setString(1, username);
            ps.setInt(2, rating);
            ps.setString(3, text);
            ps.setString(4, role);
            ps.executeUpdate();
            ps.close();

            result.put("success", true);
            result.put("message", "Review submitted for approval.");
            System.out.println("[Reviews] New review submitted by: " + username);

        } catch (Exception e) {
            response.setStatus(500);
            result.put("success", false);
            result.put("message", "Server error: " + e.getMessage());
            System.err.println("[Reviews] POST error: " + e.getMessage());
        }

        out.print(result);
        out.flush();
    }

    @Override
    protected void doPut(HttpServletRequest request, HttpServletResponse response)
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
            out.flush();
            return;
        }

        StringBuilder sb = new StringBuilder();
        BufferedReader reader = request.getReader();
        String line;
        while ((line = reader.readLine()) != null) sb.append(line);

        try {
            JSONObject body = new JSONObject(sb.toString());
            int id = body.optInt("id", -1);

            if (id < 1) {
                response.setStatus(400);
                result.put("success", false);
                result.put("message", "Review ID is required.");
                out.print(result);
                out.flush();
                return;
            }

            Connection conn = DBConnection.getConnection();
            String sql = "UPDATE reviews SET approved = true WHERE id = ?";
            PreparedStatement ps = conn.prepareStatement(sql);
            ps.setInt(1, id);
            int rows = ps.executeUpdate();
            ps.close();

            if (rows > 0) {
                result.put("success", true);
                result.put("message", "Review approved.");
                System.out.println("[Reviews] Approved review id=" + id);
            } else {
                response.setStatus(404);
                result.put("success", false);
                result.put("message", "Review not found.");
            }

        } catch (Exception e) {
            response.setStatus(500);
            result.put("success", false);
            result.put("message", "Server error: " + e.getMessage());
            System.err.println("[Reviews] PUT error: " + e.getMessage());
        }

        out.print(result);
        out.flush();
    }

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
            out.flush();
            return;
        }

        String idParam = request.getParameter("id");
        if (idParam == null) {
            response.setStatus(400);
            result.put("success", false);
            result.put("message", "Review ID is required.");
            out.print(result);
            out.flush();
            return;
        }

        try {
            int id = Integer.parseInt(idParam);
            Connection conn = DBConnection.getConnection();

            String sql = "DELETE FROM reviews WHERE id = ?";
            PreparedStatement ps = conn.prepareStatement(sql);
            ps.setInt(1, id);
            int rows = ps.executeUpdate();
            ps.close();

            if (rows > 0) {
                result.put("success", true);
                result.put("message", "Review deleted.");
                System.out.println("[Reviews] Deleted review id=" + id);
            } else {
                response.setStatus(404);
                result.put("success", false);
                result.put("message", "Review not found.");
            }

        } catch (Exception e) {
            response.setStatus(500);
            result.put("success", false);
            result.put("message", "Server error: " + e.getMessage());
            System.err.println("[Reviews] DELETE error: " + e.getMessage());
        }

        out.print(result);
        out.flush();
    }

    @Override
    protected void doOptions(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        setCorsHeaders(response);
        response.setHeader("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS");
        response.setHeader("Access-Control-Allow-Headers", "Content-Type");
        response.setStatus(200);
    }

    private void setCorsHeaders(HttpServletResponse response) {
        String origin = System.getenv("FRONTEND_URL") != null ? System.getenv("FRONTEND_URL") : "http://localhost:5173";
        response.setHeader("Access-Control-Allow-Origin", origin);
        response.setHeader("Access-Control-Allow-Credentials", "true");
    }
}
