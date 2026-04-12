package com.codeinsight.util;

import java.io.*;
import java.nio.file.*;
import java.util.concurrent.*;

public class CodeRunner {

    private static final String TEMP_DIR    = System.getProperty("java.io.tmpdir") + File.separator + "codeinsight";
    private static final int    TIMEOUT_SEC = 5;

    public static class RunResult {
        public boolean success;
        public String  output;
        public String  error;
        public long    runtimeMs;
        public String  verdict;

        public RunResult(boolean success, String output, String error, long runtimeMs, String verdict) {
            this.success   = success;
            this.output    = output;
            this.error     = error;
            this.runtimeMs = runtimeMs;
            this.verdict   = verdict;
        }
    }

    // Run with no stdin (used for plain run)
    public static RunResult run(String code) {
        return run(code, "");
    }

    // Run with stdin input (used for judging test cases)
    public static RunResult run(String code, String input) {
        File tempDir = new File(TEMP_DIR);
        if (!tempDir.exists()) tempDir.mkdirs();

        String submissionId = "sub_" + System.currentTimeMillis() + "_" + Thread.currentThread().getId();
        File   subDir       = new File(TEMP_DIR + File.separator + submissionId);
        subDir.mkdirs();

        File javaFile = new File(subDir, "Solution.java");

        try {
            Files.writeString(javaFile.toPath(), code);

            RunResult compileResult = compile(javaFile, subDir);
            if (!compileResult.success) {
                cleanup(subDir);
                return compileResult;
            }

            RunResult execResult = execute(subDir, input == null ? "" : input);
            cleanup(subDir);
            return execResult;

        } catch (Exception e) {
            cleanup(subDir);
            return new RunResult(false, "", "Internal error: " + e.getMessage(), 0, "Runtime Error");
        }
    }

    private static RunResult compile(File javaFile, File subDir) {
        try {
            ProcessBuilder pb = new ProcessBuilder(
                "javac",
                "-cp", subDir.getAbsolutePath(),
                javaFile.getAbsolutePath()
            );
            pb.directory(subDir);
            pb.redirectErrorStream(true);

            Process process = pb.start();
            String output = readStream(process.getInputStream());
            boolean finished = process.waitFor(TIMEOUT_SEC, TimeUnit.SECONDS);

            if (!finished) {
                process.destroyForcibly();
                return new RunResult(false, "", "Compilation timed out.", 0, "Compilation Error");
            }

            if (process.exitValue() != 0) {
                String cleanError = output
                    .replace(javaFile.getAbsolutePath(), "Solution.java")
                    .replace(subDir.getAbsolutePath() + File.separator, "");
                return new RunResult(false, "", cleanError, 0, "Compilation Error");
            }

            return new RunResult(true, "", "", 0, "Compiled");

        } catch (Exception e) {
            return new RunResult(false, "", "Compiler not found: " + e.getMessage(), 0, "Compilation Error");
        }
    }

    private static RunResult execute(File subDir, String input) {
        try {
            ProcessBuilder pb = new ProcessBuilder(
                "java",
                "-cp", subDir.getAbsolutePath(),
                "-Xmx128m",
                "-Xss4m",
                "Solution"
            );
            pb.directory(subDir);
            pb.redirectErrorStream(false);

            long    start   = System.currentTimeMillis();
            Process process = pb.start();

            // Write stdin input to the process
            if (input != null && !input.isEmpty()) {
                try (OutputStream os = process.getOutputStream()) {
                    os.write(input.getBytes("UTF-8"));
                    os.flush();
                }
            } else {
                // Close stdin so code reading from Scanner doesn't block
                process.getOutputStream().close();
            }

            Future<String> stdoutFuture = readAsync(process.getInputStream());
            Future<String> stderrFuture = readAsync(process.getErrorStream());

            boolean finished = process.waitFor(TIMEOUT_SEC, TimeUnit.SECONDS);
            long    runtime  = System.currentTimeMillis() - start;

            if (!finished) {
                process.destroyForcibly();
                return new RunResult(false, "", "Time Limit Exceeded.", runtime, "TLE");
            }

            String stdout = stdoutFuture.get(2, TimeUnit.SECONDS);
            String stderr = stderrFuture.get(2, TimeUnit.SECONDS);

            if (process.exitValue() != 0 && !stderr.isEmpty()) {
                return new RunResult(false, stdout.trim(), stderr.trim(), runtime, "Runtime Error");
            }

            return new RunResult(true, stdout.trim(), stderr.trim(), runtime, "Executed");

        } catch (Exception e) {
            return new RunResult(false, "", "Execution error: " + e.getMessage(), 0, "Runtime Error");
        }
    }

    private static String readStream(InputStream is) throws IOException {
        StringBuilder sb  = new StringBuilder();
        BufferedReader br = new BufferedReader(new InputStreamReader(is));
        String line;
        while ((line = br.readLine()) != null) {
            sb.append(line).append("\n");
        }
        return sb.toString();
    }

    private static Future<String> readAsync(InputStream is) {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<String>  future   = executor.submit(() -> readStream(is));
        executor.shutdown();
        return future;
    }

    private static void cleanup(File dir) {
        try {
            if (dir != null && dir.exists()) {
                File[] files = dir.listFiles();
                if (files != null) {
                    for (File f : files) f.delete();
                }
                dir.delete();
            }
        } catch (Exception ignored) {}
    }
}
