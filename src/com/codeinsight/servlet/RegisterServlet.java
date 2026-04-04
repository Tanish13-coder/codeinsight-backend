package com.codeinsight.servlet;

import com.codeinsight.util.DBConnection;
import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import org.json.JSONObject;

public class RegisterServlet extends HttpServlet {

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {

        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Access-Control-Allow-Origin", "http://localhost:3000");
        response.setHeader("Access-Control-Allow-Credentials", "true");

        PrintWriter out = response.getWriter();
        JSONObject result = new JSONObject();

        // Read JSON body
        StringBuilder sb = new StringBuilder();
        BufferedReader reader = request.getReader();
        String line;
        while ((line = reader.readLine()) != null)
            sb.append(line);

        try {
            JSONObject body = new JSONObject(sb.toString());
            String username = body.optString("username", "").trim();
            String email = body.optString("email", "").trim();
            String password = body.optString("password", "").trim();

            // ── Validation ──
            if (username.isEmpty() || email.isEmpty() || password.isEmpty()) {
                response.setStatus(400);
                result.put("success", false);
                result.put("message", "All fields are required.");
                out.print(result);
                return;
            }

            if (username.length() < 3) {
                response.setStatus(400);
                result.put("success", false);
                result.put("message", "Username must be at least 3 characters.");
                out.print(result);
                return;
            }

            if (!email.matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")) {
                response.setStatus(400);
                result.put("success", false);
                result.put("message", "Invalid email address.");
                out.print(result);
                return;
            }

            if (password.length() < 6) {
                response.setStatus(400);
                result.put("success", false);
                result.put("message", "Password must be at least 6 characters.");
                out.print(result);
                return;
            }

            Connection conn = DBConnection.getConnection();

            // ── Check if username already exists ──
            String checkUser = "SELECT id FROM users WHERE username = ?";
            PreparedStatement psCheck = conn.prepareStatement(checkUser);
            psCheck.setString(1, username);
            ResultSet rsCheck = psCheck.executeQuery();
            if (rsCheck.next()) {
                response.setStatus(409);
                result.put("success", false);
                result.put("message", "Username already taken. Please choose another.");
                rsCheck.close();
                psCheck.close();
                out.print(result);
                return;
            }
            rsCheck.close();
            psCheck.close();

            // ── Check if email already exists ──
            String checkEmail = "SELECT id FROM users WHERE email = ?";
            PreparedStatement psEmail = conn.prepareStatement(checkEmail);
            psEmail.setString(1, email);
            ResultSet rsEmail = psEmail.executeQuery();
            if (rsEmail.next()) {
                response.setStatus(409);
                result.put("success", false);
                result.put("message", "Email already registered. Please sign in.");
                rsEmail.close();
                psEmail.close();
                out.print(result);
                return;
            }
            rsEmail.close();
            psEmail.close();

            // ── Insert new user ──
            String insert = "INSERT INTO users (username, email, password, role) VALUES (?, ?, ?, 'user')";
            PreparedStatement psInsert = conn.prepareStatement(insert);
            psInsert.setString(1, username);
            psInsert.setString(2, email);
            psInsert.setString(3, password);
            int rows = psInsert.executeUpdate();
            psInsert.close();

            if (rows > 0) {
                // ── Also create leaderboard entry ──
                String getUserId = "SELECT id FROM users WHERE username = ?";
                PreparedStatement psId = conn.prepareStatement(getUserId);
                psId.setString(1, username);
                ResultSet rsId = psId.executeQuery();
                if (rsId.next()) {
                    int newUserId = rsId.getInt("id");
                    String insertLb = "INSERT IGNORE INTO leaderboard (user_id, score, solved) VALUES (?, 0, 0)";
                    PreparedStatement psLb = conn.prepareStatement(insertLb);
                    psLb.setInt(1, newUserId);
                    psLb.executeUpdate();
                    psLb.close();
                }
                rsId.close();
                psId.close();

                result.put("success", true);
                result.put("message", "Account created successfully! Please sign in.");
                System.out.println("[Register] New user registered: " + username);
            } else {
                response.setStatus(500);
                result.put("success", false);
                result.put("message", "Registration failed. Please try again.");
            }

        } catch (Exception e) {
            response.setStatus(500);
            result.put("success", false);
            result.put("message", "Server error: " + e.getMessage());
            System.err.println("[Register] Error: " + e.getMessage());
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