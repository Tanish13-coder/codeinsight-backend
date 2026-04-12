package com.codeinsight.servlet;

import com.codeinsight.util.DBConnection;
import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

import java.io.IOException;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

import org.json.JSONArray;
import org.json.JSONObject;

public class LeaderboardServlet extends HttpServlet {

    // ── GET: fetch top N users from leaderboard ──
    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {

        setCorsHeaders(response);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");

        PrintWriter out = response.getWriter();

        // Must be logged in
        HttpSession session = request.getSession(false);
        if (session == null || session.getAttribute("userId") == null) {
            response.setStatus(401);
            out.print(new JSONObject()
                    .put("success", false)
                    .put("message", "Please log in."));
            return;
        }

        int currentUserId = (int) session.getAttribute("userId");

        try {
            Connection conn = DBConnection.getConnection();

            // ── Fetch top 50 leaderboard entries ──
            String sql = "SELECT l.user_id, u.username, l.score, l.solved, "
                    + "RANK() OVER (ORDER BY l.score DESC, l.solved DESC) AS rank_pos "
                    + "FROM leaderboard l "
                    + "JOIN users u ON l.user_id = u.id "
                    + "ORDER BY l.score DESC, l.solved DESC "
                    + "LIMIT 50";

            PreparedStatement ps = conn.prepareStatement(sql);
            ResultSet rs = ps.executeQuery();

            JSONArray entries = new JSONArray();
            JSONObject currentUser = null;
            int userRank = -1;

            while (rs.next()) {
                int uid = rs.getInt("user_id");
                String uname = rs.getString("username");
                int score = rs.getInt("score");
                int solved = rs.getInt("solved");
                int rankPos = rs.getInt("rank_pos");

                JSONObject entry = new JSONObject();
                entry.put("rank", rankPos);
                entry.put("userId", uid);
                entry.put("username", uname);
                entry.put("score", score);
                entry.put("solved", solved);
                entry.put("isYou", uid == currentUserId);

                entries.put(entry);

                if (uid == currentUserId) {
                    currentUser = entry;
                    userRank = rankPos;
                }
            }

            rs.close();
            ps.close();

            // ── If current user not in top 50, fetch their rank separately ──
            if (currentUser == null) {
                String rankSql = "SELECT score, solved, "
                        + "(SELECT COUNT(*) + 1 FROM leaderboard l2 "
                        + " JOIN users u2 ON l2.user_id = u2.id "
                        + " WHERE l2.score > l.score) AS rank_pos "
                        + "FROM leaderboard l WHERE l.user_id = ?";
                PreparedStatement rankPs = conn.prepareStatement(rankSql);
                rankPs.setInt(1, currentUserId);
                ResultSet rankRs = rankPs.executeQuery();

                if (rankRs.next()) {
                    currentUser = new JSONObject();
                    currentUser.put("rank", rankRs.getInt("rank_pos"));
                    currentUser.put("userId", currentUserId);
                    currentUser.put("username", session.getAttribute("username"));
                    currentUser.put("score", rankRs.getInt("score"));
                    currentUser.put("solved", rankRs.getInt("solved"));
                    currentUser.put("isYou", true);
                    userRank = rankRs.getInt("rank_pos");
                }
                rankRs.close();
                rankPs.close();
            }

            // ── Build response ──
            JSONObject result = new JSONObject();
            result.put("success", true);
            result.put("leaderboard", entries);
            result.put("userRank", userRank);
            if (currentUser != null) {
                result.put("currentUser", currentUser);
            }

            out.print(result);
            System.out.println("[Leaderboard] Fetched for userId=" + currentUserId);

        } catch (Exception e) {
            response.setStatus(500);
            out.print(new JSONObject()
                    .put("success", false)
                    .put("message", "Server error: " + e.getMessage()));
            System.err.println("[Leaderboard] Error: " + e.getMessage());
        }

        out.flush();
    }

    @Override
    protected void doOptions(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        setCorsHeaders(response);
        response.setHeader("Access-Control-Allow-Methods", "GET, OPTIONS");
        response.setHeader("Access-Control-Allow-Headers", "Content-Type");
        response.setStatus(200);
    }

    private void setCorsHeaders(HttpServletResponse response) {
        String origin = System.getenv("FRONTEND_URL") != null ? System.getenv("FRONTEND_URL") : "http://localhost:5173";
        response.setHeader("Access-Control-Allow-Origin", origin);
        response.setHeader("Access-Control-Allow-Credentials", "true");
    }
}