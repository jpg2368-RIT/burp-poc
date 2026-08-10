import burp.api.montoya.MontoyaApi;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public final class LogManager {
    public static final String LEVEL_OFF = "OFF";
    public static final String LEVEL_LOG = "LOG";
    public static final String LEVEL_DEBUG = "DEBUG";
    public static final String LEVEL_COMPLETE = "COMPLETE";

    private static final int OFF = 0;
    private static final int LOG = 1;
    private static final int DEBUG = 2;
    private static final int COMPLETE = 3;

    private static final Object LOCK = new Object();

    private static final DateTimeFormatter FILE_TS = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");
    private static final DateTimeFormatter LINE_TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    private static MontoyaApi api;
    private static int level = 0;
    private static Path logDir;
    private static Path logFile;
    private static PrintWriter writer;

    private LogManager() {
    }

    public static void initialize(MontoyaApi apiArg) {
        api = apiArg;
        level = 0;
        logDir = null;
        if (api != null) {
            if (api.persistence().preferences().stringKeys().contains("logLevel")) {
                level = parseLevel(api.persistence().preferences().getString("logLevel"));
            } else if (api.persistence().preferences().stringKeys().contains("debugEnabled")) {
                level = api.persistence().preferences().getString("debugEnabled").equals("true") ? DEBUG : 0;
            }
            if (api.persistence().preferences().stringKeys().contains("logDir")) {
                String dir = api.persistence().preferences().getString("logDir");
                if (dir != null && !dir.isBlank()) {
                    logDir = Path.of(dir);
                }
            }
        }
        if (level > 0) {
            openFile();
        }
    }

    public static boolean isLogEnabled() {
        return level >= LOG;
    }

    public static boolean isDebugEnabled() {
        return level >= DEBUG;
    }

    public static boolean isCompleteEnabled() {
        return level >= COMPLETE;
    }

    public static String levelName() {
        return switch (level) {
            case COMPLETE -> LEVEL_COMPLETE;
            case DEBUG -> LEVEL_DEBUG;
            case LOG -> LEVEL_LOG;
            default -> LEVEL_OFF;
        };
    }

    public static String logFilePath() {
        return logFile == null ? "" : logFile.toString();
    }

    public static String canonicalLevelName(String stored) {
        return levelName(parseLevel(stored));
    }

    public static void setLogLevel(String name) {
        int newLevel = parseLevel(name);
        synchronized (LOCK) {
            level = newLevel;
            if (level > 0) {
                if (writer == null) {
                    openFile();
                }
            } else {
                closeFile();
            }
        }
    }

    public static void setLogDirectory(String dir) {
        synchronized (LOCK) {
            logDir = (dir == null || dir.isBlank()) ? null : Path.of(dir);
            if (level > 0) {
                closeFile();
                openFile();
            }
        }
    }

    public static void log(String message) {
        if (level >= LOG) {
            writeLine(LEVEL_LOG, message);
            if (api != null) {
                api.logging().logToOutput(message);
            }
        }
    }

    public static void debug(String message) {
        if (level >= DEBUG) {
            writeLine(LEVEL_DEBUG, message);
        }
    }

    public static void complete(String message) {
        if (level >= COMPLETE) {
            writeLine(LEVEL_COMPLETE, message);
        }
    }

    public static void error(String message) {
        if (api != null) {
            api.logging().logToError(message);
        }
        if (level >= LOG) {
            writeLine("ERROR", message);
        }
    }

    public static void close() {
        synchronized (LOCK) {
            closeFile();
        }
    }

    private static void openFile() {
        Path dir = logDir != null ? logDir : Path.of(System.getProperty("java.io.tmpdir"));
        try {
            Files.createDirectories(dir);
            logFile = dir.resolve("burp_poc_" + LocalDateTime.now().format(FILE_TS) + ".log");
            writer = new PrintWriter(Files.newBufferedWriter(logFile, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND));
            writeLine("INFO", "Log file opened: " + logFile);
        } catch (IOException e) {
            logToApiError("LogManager: cannot open log file in " + dir + ": " + e.getMessage());
            logFile = null;
        }
    }

    private static void closeFile() {
        if (writer != null) {
            writer.flush();
            writer.close();
            writer = null;
        }
    }

    private static void writeLine(String levelTag, String message) {
        synchronized (LOCK) {
            if (writer == null) return;
            writer.println(LocalDateTime.now().format(LINE_TS) + " [" + levelTag + "] " + message);
            writer.flush();
        }
    }

    private static int parseLevel(String name) {
        if (name == null) return 0;
        return switch (name.trim().toUpperCase()) {
            case LEVEL_COMPLETE, "TRACE" -> COMPLETE;
            case LEVEL_DEBUG -> DEBUG;
            case LEVEL_LOG -> LOG;
            default -> 0;
        };
    }

    private static String levelName(int lvl) {
        return switch (lvl) {
            case COMPLETE -> LEVEL_COMPLETE;
            case DEBUG -> LEVEL_DEBUG;
            case LOG -> LEVEL_LOG;
            default -> LEVEL_OFF;
        };
    }

    private static void logToApiError(String message) {
        if (api != null) {
            api.logging().logToError(message);
        }
    }
}