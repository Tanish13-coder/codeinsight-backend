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

public class UserServlet extends HttpServlet {

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

        Object raw = session.getAttribute("userId");
        int userId = (raw instanceof Integer) ? (Integer) raw : Integer.parseInt(raw.toString());

        try {
            Connection conn = DBConnection.getConnection();
            JSONObject result = new JSONObject();
            result.put("success", true);

            // ── Leaderboard stats ──
            PreparedStatement psLb = conn.prepareStatement(
                    "SELECT score, solved FROM leaderboard WHERE user_id = ?");
            psLb.setInt(1, userId);
            ResultSet rsLb = psLb.executeQuery();
            int score = 0, solved = 0;
            if (rsLb.next()) {
                score = rsLb.getInt("score");
                solved = rsLb.getInt("solved");
            }
            rsLb.close();
            psLb.close();
            result.put("score", score);
            result.put("solved", solved);

            // ── Rank ──
            PreparedStatement psRank = conn.prepareStatement(
                    "SELECT COUNT(*) + 1 AS rank_pos FROM leaderboard WHERE score > "
                            + "(SELECT COALESCE(score, 0) FROM leaderboard WHERE user_id = ?)");
            psRank.setInt(1, userId);
            ResultSet rsRank = psRank.executeQuery();
            result.put("rank", rsRank.next() ? rsRank.getInt("rank_pos") : 1);
            rsRank.close();
            psRank.close();

            // ── Total submissions + acceptance rate ──
            PreparedStatement psSub = conn.prepareStatement(
                    "SELECT COUNT(*) AS total, "
                            + "SUM(CASE WHEN verdict = 'Accepted' THEN 1 ELSE 0 END) AS accepted "
                            + "FROM submissions WHERE user_id = ?");
            psSub.setInt(1, userId);
            ResultSet rsSub = psSub.executeQuery();
            int totalSubs = 0;
            int acceptedSubs = 0;
            if (rsSub.next()) {
                totalSubs = rsSub.getInt("total");
                acceptedSubs = rsSub.getInt("accepted");
            }
            rsSub.close();
            psSub.close();
            result.put("totalSubmissions", totalSubs);
            result.put("acceptanceRate",
                    totalSubs > 0 ? Math.round((acceptedSubs * 100.0) / totalSubs) : 0);

            // ── Per-difficulty solved counts ──
            PreparedStatement psDiff = conn.prepareStatement(
                    "SELECT p.difficulty, COUNT(DISTINCT s.problem_id) AS cnt "
                            + "FROM submissions s "
                            + "JOIN problems p ON s.problem_id = p.id "
                            + "WHERE s.user_id = ? AND s.verdict = 'Accepted' "
                            + "GROUP BY p.difficulty");
            psDiff.setInt(1, userId);
            ResultSet rsDiff = psDiff.executeQuery();
            int easy = 0, medium = 0, hard = 0;
            while (rsDiff.next()) {
                switch (rsDiff.getString("difficulty")) {
                    case "Easy" -> easy = rsDiff.getInt("cnt");
                    case "Medium" -> medium = rsDiff.getInt("cnt");
                    case "Hard" -> hard = rsDiff.getInt("cnt");
                }
            }
            rsDiff.close();
            psDiff.close();
            result.put("easySolved", easy);
            result.put("mediumSolved", medium);
            result.put("hardSolved", hard);

            // ── Solved problem IDs ──
            PreparedStatement psSolved = conn.prepareStatement(
                    "SELECT DISTINCT problem_id FROM submissions "
                            + "WHERE user_id = ? AND verdict = 'Accepted'");
            psSolved.setInt(1, userId);
            ResultSet rsSolved = psSolved.executeQuery();
            JSONArray solvedIds = new JSONArray();
            while (rsSolved.next())
                solvedIds.put(rsSolved.getInt("problem_id"));
            rsSolved.close();
            psSolved.close();
            result.put("solvedProblemIds", solvedIds);

            // ── Recent activity (last 10) ──
            JSONArray activity = new JSONArray();
            try {
                PreparedStatement psAct = conn.prepareStatement(
                        "SELECT problem, verdict, lang, submitted_at "
                                + "FROM recent_activity WHERE user_id = ? "
                                + "ORDER BY submitted_at DESC LIMIT 10");
                psAct.setInt(1, userId);
                ResultSet rsAct = psAct.executeQuery();
                while (rsAct.next()) {
                    activity.put(new JSONObject()
                            .put("problem", rsAct.getString("problem"))
                            .put("verdict", rsAct.getString("verdict"))
                            .put("lang", rsAct.getString("lang"))
                            .put("time", rsAct.getString("submitted_at")));
                }
                rsAct.close();
                psAct.close();
            } catch (Exception ignored) {
                // recent_activity table may not exist yet — safe to skip
            }
            result.put("recentActivity", activity);

            out.print(result);
            System.out.println("[User] Stats fetched for userId=" + userId);

        } catch (Exception e) {
            response.setStatus(500);
            out.print(new JSONObject().put("success", false).put("message", "Server error: " + e.getMessage()));
            System.err.println("[User] Error: " + e.getMessage());
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
