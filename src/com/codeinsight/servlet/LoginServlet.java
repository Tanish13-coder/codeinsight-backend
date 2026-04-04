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
import java.sql.SQLException;

import org.json.JSONObject;

public class LoginServlet extends HttpServlet {

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {

        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Access-Control-Allow-Origin", "http://localhost:3000");
        response.setHeader("Access-Control-Allow-Credentials", "true");

        PrintWriter out = response.getWriter();

        // Read JSON body
        StringBuilder sb = new StringBuilder();
        BufferedReader reader = request.getReader();
        String line;
        while ((line = reader.readLine()) != null)
            sb.append(line);

        JSONObject result = new JSONObject();

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

                // Create session
                HttpSession session = request.getSession(true);
                session.setAttribute("userId", userId);
                session.setAttribute("username", uname);
                session.setAttribute("role", role);
                session.setMaxInactiveInterval(60 * 60); // 1 hour

                result.put("success", true);
                result.put("message", "Login successful.");
                result.put("userId", userId);
                result.put("username", uname);
                result.put("role", role);

                System.out.println("[Login] User logged in: " + uname + " | Role: " + role);
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
        response.setHeader("Access-Control-Allow-Origin", "http://localhost:3000");
        response.setHeader("Access-Control-Allow-Methods", "POST, OPTIONS");
        response.setHeader("Access-Control-Allow-Headers", "Content-Type");
        response.setHeader("Access-Control-Allow-Credentials", "true");
        response.setStatus(200);
    }
}