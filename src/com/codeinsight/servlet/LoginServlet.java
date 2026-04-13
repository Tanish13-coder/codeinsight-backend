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

import org.json.JSONObject;

@WebServlet("/Login") // URL is now /codeinsight/Login
public class LoginServlet extends HttpServlet {

    // Allow requests from both Vite (5173) and CRA (3000)
    private static final String[] ALLOWED_ORIGINS = {
            "http://localhost:5173",
            "http://localhost:3000",
            "http://localhost:5174"
    };

    private void setCORSHeaders(HttpServletRequest request, HttpServletResponse response) {
        String origin = request.getHeader("Origin");
        if (origin != null) {
            for (String allowed : ALLOWED_ORIGINS) {
                if (allowed.equalsIgnoreCase(origin)) {
                    response.setHeader("Access-Control-Allow-Origin", origin);
                    response.setHeader("Access-Control-Allow-Credentials", "true");
                    break;
                }
            }
        }
    }

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {

        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        setCORSHeaders(request, response);

        PrintWriter out = response.getWriter();
        JSONObject result = new JSONObject();

        // Read JSON body
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = request.getReader()) {
            String line;
            while ((line = reader.readLine()) != null)
                sb.append(line);
        }

        try {
            JSONObject body = new JSONObject(sb.toString());
            String username = body.optString("username", "").trim();
            String password = body.optString("password", "").trim();

            if (username.isEmpty() || password.isEmpty()) {
                response.setStatus(400);
                result.put("success", false);
                result.put("message", "Username and password are required.");
                out.print(result);
                return;
            }

            Connection conn = DBConnection.getConnection();
            String sql = "SELECT id, username, role FROM users WHERE username = ? AND password = ?";
            PreparedStatement ps = conn.prepareStatement(sql);
            ps.setString(1, username);
            ps.setString(2, password);
            ResultSet rs = ps.executeQuery();

            if (rs.next()) {
                int userId = rs.getInt("id");
                String uname = rs.getString("username");
                String role = rs.getString("role");

                HttpSession session = request.getSession(true);
                session.setAttribute("userId", userId);
                session.setAttribute("username", uname);
                session.setAttribute("role", role);
                session.setMaxInactiveInterval(60 * 60);

                result.put("success", true);
                result.put("message", "Login successful.");
                result.put("userId", userId);
                result.put("username", uname);
                result.put("role", role);

                System.out.println("[Login] Success: " + uname + " | Role: " + role);
            } else {
                response.setStatus(401);
                result.put("success", false);
                result.put("message", "Invalid username or password.");
            }

            rs.close();
            ps.close();

        } catch (Exception e) {
            response.setStatus(500);
            result.put("success", false);
            result.put("message", "Server error: " + e.getMessage());
            System.err.println("[Login] Error: " + e.getMessage());
        }

        out.print(result);
        out.flush();
    }

    @Override
    protected void doOptions(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        setCORSHeaders(request, response);
        response.setHeader("Access-Control-Allow-Methods", "POST, OPTIONS");
        response.setHeader("Access-Control-Allow-Headers", "Content-Type");
        response.setStatus(200);
    }
}
