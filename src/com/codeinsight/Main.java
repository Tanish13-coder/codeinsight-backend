package com.codeinsight;

import org.apache.catalina.Context;
import org.apache.catalina.startup.Tomcat;
import org.apache.tomcat.util.descriptor.web.FilterDef;
import org.apache.tomcat.util.descriptor.web.FilterMap;

import com.codeinsight.servlet.*;
import com.codeinsight.filter.*;

import java.io.File;

public class Main {

    public static void main(String[] args) throws Exception {

        int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "8080"));

        Tomcat tomcat = new Tomcat();
        tomcat.setPort(port);
        tomcat.setBaseDir(System.getProperty("java.io.tmpdir"));

        // Use empty temp dir — servlets registered programmatically, not via web.xml
        String webappDir = System.getProperty("java.io.tmpdir");
        Context ctx = tomcat.addContext("/codeinsight", webappDir);

        Tomcat.addServlet(ctx, "LoginServlet", new LoginServlet());
        ctx.addServletMappingDecoded("/login", "LoginServlet");

        Tomcat.addServlet(ctx, "RegisterServlet", new RegisterServlet());
        ctx.addServletMappingDecoded("/Register", "RegisterServlet");

        Tomcat.addServlet(ctx, "ProblemsServlet", new ProblemsServlet());
        ctx.addServletMappingDecoded("/problems", "ProblemsServlet");

        Tomcat.addServlet(ctx, "SubmitServlet", new SubmitServlet());
        ctx.addServletMappingDecoded("/submit", "SubmitServlet");

        Tomcat.addServlet(ctx, "LeaderboardServlet", new LeaderboardServlet());
        ctx.addServletMappingDecoded("/leaderboard", "LeaderboardServlet");

        Tomcat.addServlet(ctx, "UserServlet", new UserServlet());
        ctx.addServletMappingDecoded("/user", "UserServlet");

        Tomcat.addServlet(ctx, "AIInsightServlet", new AIInsightServlet());
        ctx.addServletMappingDecoded("/ai-insight", "AIInsightServlet");

        FilterDef corsFilter = new FilterDef();
        corsFilter.setFilterName("CORSFilter");
        corsFilter.setFilter(new CORSFilter());
        ctx.addFilterDef(corsFilter);
        FilterMap corsMap = new FilterMap();
        corsMap.setFilterName("CORSFilter");
        corsMap.addURLPattern("/*");
        ctx.addFilterMap(corsMap);

        FilterDef encFilter = new FilterDef();
        encFilter.setFilterName("EncodingFilter");
        encFilter.setFilter(new EncodingFilter());
        ctx.addFilterDef(encFilter);
        FilterMap encMap = new FilterMap();
        encMap.setFilterName("EncodingFilter");
        encMap.addURLPattern("/*");
        ctx.addFilterMap(encMap);

        tomcat.getConnector();
        tomcat.start();
        System.out.println("CodeInsight running on port " + port);
        tomcat.getServer().await();
    }
}