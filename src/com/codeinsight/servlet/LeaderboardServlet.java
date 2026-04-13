package com.codeinsight.servlet;

import com.codeinsight.util.DBConnection;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

import java.io.IOException;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Set;

import org.json.JSONArray;
import org.json.JSONObject;

public class LeaderboardServlet extends HttpServlet {

    // ── FIX: support all dev ports, not just 3000 ──
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

        HttpSession session = request.getSession(false);
        if (session == null || session.getAttribute("userId") == null) {
            response.setStatus(401);
            out.print(new JSONObject().put("success", false).put("message", "Please log in."));
            return;
        }

        // ── FIX: safe cast ──
        Object rawId = session.getAttribute("userId");
        int currentUserId = (rawId instanceof Integer) ? (Integer) rawId : Integer.parseInt(rawId.toString());

        try {
            Connection conn = DBConnection.getConnection();

            // ── Fetch top 50 ──
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
                JSONObject entry = new JSONObject();
                entry.put("rank", rs.getInt("rank_pos"));
                entry.put("userId", uid);
                entry.put("username", rs.getString("username"));
                entry.put("score", rs.getInt("score"));
                entry.put("solved", rs.getInt("solved"));
                entry.put("isYou", uid == currentUserId);
                entries.put(entry);

                if (uid == currentUserId) {
                    currentUser = entry;
                    userRank = rs.getInt("rank_pos");
                }
            }
            rs.close();
            ps.close();

            // ── If current user not in top 50, fetch separately ──
            if (currentUser == null) {
                String rankSql = "SELECT score, solved, "
                        + "(SELECT COUNT(*) + 1 FROM leaderboard l2 WHERE l2.score > l.score) AS rank_pos "
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

            JSONObject result = new JSONObject();
            result.put("success", true);
            result.put("leaderboard", entries);
            result.put("userRank", userRank);
            if (currentUser != null)
                result.put("currentUser", currentUser);

            out.print(result);
            System.out.println("[Leaderboard] Fetched for userId=" + currentUserId);

        } catch (Exception e) {
            response.setStatus(500);
            out.print(new JSONObject().put("success", false).put("message", "Server error: " + e.getMessage()));
            System.err.println("[Leaderboard] Error: " + e.getMessage());
        }

        out.flush();
    }

    @Override
    protected void doOptions(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        setCorsHeaders(request, response);
        response.setHeader("Access-Control-Allow-Methods", "GET, OPTIONS");
        response.setHeader("Access-Control-Allow-Headers", "Content-Type");
        response.setStatus(200);
    }
}
