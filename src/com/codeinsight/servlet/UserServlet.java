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

public class UserServlet extends HttpServlet {

    // ── GET: fetch current user profile + stats + recent activity ──
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

        int userId = (int) session.getAttribute("userId");
        String username = (String) session.getAttribute("username");
        String role = (String) session.getAttribute("role");

        try {
            Connection conn = DBConnection.getConnection();
            JSONObject result = new JSONObject();

            // ── Basic user info ──
            result.put("success", true);
            result.put("userId", userId);
            result.put("username", username);
            result.put("role", role);

            // ── Leaderboard stats ──
            String lbSql = "SELECT score, solved FROM leaderboard WHERE user_id = ?";
            PreparedStatement lbPs = conn.prepareStatement(lbSql);
            lbPs.setInt(1, userId);
            ResultSet lbRs = lbPs.executeQuery();

            int score = 0;
            int solved = 0;
            if (lbRs.next()) {
                score = lbRs.getInt("score");
                solved = lbRs.getInt("solved");
            }
            lbRs.close();
            lbPs.close();

            result.put("score", score);
            result.put("solved", solved);

            // ── Global rank ──
            String rankSql = "SELECT COUNT(*) + 1 AS rank_pos "
                    + "FROM leaderboard "
                    + "WHERE score > (SELECT COALESCE(score, 0) FROM leaderboard WHERE user_id = ?)";
            PreparedStatement rankPs = conn.prepareStatement(rankSql);
            rankPs.setInt(1, userId);
            ResultSet rankRs = rankPs.executeQuery();
            int rank = 9999;
            if (rankRs.next())
                rank = rankRs.getInt("rank_pos");
            rankRs.close();
            rankPs.close();
            result.put("rank", rank);

            // ── Total submissions ──
            String totalSql = "SELECT COUNT(*) AS total FROM submissions WHERE user_id = ?";
            PreparedStatement totalPs = conn.prepareStatement(totalSql);
            totalPs.setInt(1, userId);
            ResultSet totalRs = totalPs.executeQuery();
            int totalSubmissions = 0;
            if (totalRs.next())
                totalSubmissions = totalRs.getInt("total");
            totalRs.close();
            totalPs.close();
            result.put("totalSubmissions", totalSubmissions);

            // ── Acceptance rate ──
            String accSql = "SELECT COUNT(*) AS accepted FROM submissions "
                    + "WHERE user_id = ? AND verdict = 'Accepted'";
            PreparedStatement accPs = conn.prepareStatement(accSql);
            accPs.setInt(1, userId);
            ResultSet accRs = accPs.executeQuery();
            int accepted = 0;
            if (accRs.next())
                accepted = accRs.getInt("accepted");
            accRs.close();
            accPs.close();

            int acceptanceRate = totalSubmissions > 0
                    ? (int) Math.round((accepted * 100.0) / totalSubmissions)
                    : 0;
            result.put("acceptanceRate", acceptanceRate);

            // ── Solved by difficulty ──
            String diffSql = "SELECT p.difficulty, COUNT(DISTINCT s.problem_id) AS cnt "
                    + "FROM submissions s "
                    + "JOIN problems p ON s.problem_id = p.id "
                    + "WHERE s.user_id = ? AND s.verdict = 'Accepted' "
                    + "GROUP BY p.difficulty";
            PreparedStatement diffPs = conn.prepareStatement(diffSql);
            diffPs.setInt(1, userId);
            ResultSet diffRs = diffPs.executeQuery();

            int easySolved = 0, mediumSolved = 0, hardSolved = 0;
            while (diffRs.next()) {
                String diff = diffRs.getString("difficulty");
                int cnt = diffRs.getInt("cnt");
                if ("Easy".equals(diff))
                    easySolved = cnt;
                else if ("Medium".equals(diff))
                    mediumSolved = cnt;
                else if ("Hard".equals(diff))
                    hardSolved = cnt;
            }
            diffRs.close();
            diffPs.close();

            result.put("easySolved", easySolved);
            result.put("mediumSolved", mediumSolved);
            result.put("hardSolved", hardSolved);

            // ── Total problems by difficulty ──
            String totDiffSql = "SELECT difficulty, COUNT(*) AS cnt FROM problems GROUP BY difficulty";
            PreparedStatement totDiffPs = conn.prepareStatement(totDiffSql);
            ResultSet totDiffRs = totDiffPs.executeQuery();

            int easyTotal = 0, mediumTotal = 0, hardTotal = 0;
            while (totDiffRs.next()) {
                String diff = totDiffRs.getString("difficulty");
                int cnt = totDiffRs.getInt("cnt");
                if ("Easy".equals(diff))
                    easyTotal = cnt;
                else if ("Medium".equals(diff))
                    mediumTotal = cnt;
                else if ("Hard".equals(diff))
                    hardTotal = cnt;
            }
            totDiffRs.close();
            totDiffPs.close();

            result.put("easyTotal", easyTotal);
            result.put("mediumTotal", mediumTotal);
            result.put("hardTotal", hardTotal);

            // ── Recent activity (last 10 submissions) ──
            String actSql = "SELECT p.title, s.verdict, s.language, s.runtime_ms, s.created_at "
                    + "FROM submissions s "
                    + "JOIN problems p ON s.problem_id = p.id "
                    + "WHERE s.user_id = ? "
                    + "ORDER BY s.created_at DESC LIMIT 10";
            PreparedStatement actPs = conn.prepareStatement(actSql);
            actPs.setInt(1, userId);
            ResultSet actRs = actPs.executeQuery();

            JSONArray activity = new JSONArray();
            while (actRs.next()) {
                JSONObject act = new JSONObject();
                act.put("problem", actRs.getString("title"));
                act.put("verdict", actRs.getString("verdict"));
                act.put("lang", actRs.getString("language"));
                act.put("runtime", actRs.getInt("runtime_ms") > 0
                        ? actRs.getInt("runtime_ms") + " ms"
                        : "-");
                act.put("time", timeAgo(actRs.getTimestamp("created_at").getTime()));
                activity.put(act);
            }
            actRs.close();
            actPs.close();

            result.put("recentActivity", activity);

            // ── Solved problem IDs (so frontend can mark them) ──
            String solvedSql = "SELECT DISTINCT problem_id FROM submissions "
                    + "WHERE user_id = ? AND verdict = 'Accepted'";
            PreparedStatement solvedPs = conn.prepareStatement(solvedSql);
            solvedPs.setInt(1, userId);
            ResultSet solvedRs = solvedPs.executeQuery();

            JSONArray solvedIds = new JSONArray();
            while (solvedRs.next()) {
                solvedIds.put(solvedRs.getInt("problem_id"));
            }
            solvedRs.close();
            solvedPs.close();

            result.put("solvedProblemIds", solvedIds);

            out.print(result);
            System.out.println("[User] Profile fetched for userId=" + userId);

        } catch (Exception e) {
            response.setStatus(500);
            out.print(new JSONObject()
                    .put("success", false)
                    .put("message", "Server error: " + e.getMessage()));
            System.err.println("[User] Error: " + e.getMessage());
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

    // ── Helper: convert timestamp to "X ago" string ──
    private String timeAgo(long timeMs) {
        long diff = System.currentTimeMillis() - timeMs;
        long minutes = diff / 60000;
        long hours = minutes / 60;
        long days = hours / 24;

        if (minutes < 1)
            return "just now";
        if (minutes < 60)
            return minutes + "m ago";
        if (hours < 24)
            return hours + "h ago";
        return days + "d ago";
    }

    private void setCorsHeaders(HttpServletResponse response) {
        String origin = System.getenv("FRONTEND_URL") != null ? System.getenv("FRONTEND_URL") : "http://localhost:5173";
        response.setHeader("Access-Control-Allow-Origin", origin);
        response.setHeader("Access-Control-Allow-Credentials", "true");
    }
}