import burp.api.montoya.MontoyaApi;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Writes leveled log output to the Burp Output/Errors tabs and to a timestamped
 * file on disk.
 *
 * <p>Levels form a hierarchy: OFF = 0, LOG = 1, DEBUG = 2, COMPLETE = 3. Each
 * level includes all levels below it. All state is static; the extension uses
 * this class as a global logging service.</p>
 *
 * <p>All file access is synchronized on {@link #LOCK} so that concurrent
 * threads never interleave log lines.</p>
 */
public final class LogManager {
    /** Level name for no logging. */
    public static final String LEVEL_OFF = "OFF";
    /** Level name for general activity. */
    public static final String LEVEL_LOG = "LOG";
    /** Level name for detailed activity. */
    public static final String LEVEL_DEBUG = "DEBUG";
    /** Level name for everything, including every SSE delta. */
    public static final String LEVEL_COMPLETE = "COMPLETE";

    /** Numeric value for {@link #LEVEL_OFF}. */
    private static final int OFF = 0;
    /** Numeric value for {@link #LEVEL_LOG}. */
    private static final int LOG = 1;
    /** Numeric value for {@link #LEVEL_DEBUG}. */
    private static final int DEBUG = 2;
    /** Numeric value for {@link #LEVEL_COMPLETE}. */
    private static final int COMPLETE = 3;

    /** Guards the log level, the log file path, and the writer. */
    private static final Object LOCK = new Object();

    /** Timestamp format used in log file names. */
    private static final DateTimeFormatter FILE_TS = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");
    /** Timestamp format used in log line prefixes. */
    private static final DateTimeFormatter LINE_TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    /** The Montoya API instance, used for Burp Output/Errors tab writes. */
    private static MontoyaApi api;
    /** Current numeric log level (see the OFF/LOG/DEBUG/COMPLETE constants). */
    private static int level = 0;
    /** Directory for log files; null means the system temp directory. */
    private static Path logDir;
    /** Path of the currently open log file; null when no file is open. */
    private static Path logFile;
    /** Writer for the open log file; null when no file is open. */
    private static PrintWriter writer;

    /** Private constructor: this class is a static utility. */
    private LogManager() {
    }

    /**
     * Stores the Montoya API and reads the saved log level and log directory
     * from Burp preferences.
     *
     * <p>Supports both the modern "logLevel" preference and the legacy
     * "debugEnabled" preference.</p>
     *
     * @param apiArg the Montoya API instance; may be null
     */
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

    /**
     * Checks whether LOG level output is enabled.
     *
     * @return true when the current level is at least LOG
     */
    public static boolean isLogEnabled() {
        return level >= LOG;
    }

    /**
     * Checks whether DEBUG level output is enabled.
     *
     * @return true when the current level is at least DEBUG
     */
    public static boolean isDebugEnabled() {
        return level >= DEBUG;
    }

    /**
     * Checks whether COMPLETE level output is enabled.
     *
     * @return true when the current level is at least COMPLETE
     */
    public static boolean isCompleteEnabled() {
        return level >= COMPLETE;
    }

    /**
     * Returns the name of the current level.
     *
     * @return one of LEVEL_OFF, LEVEL_LOG, LEVEL_DEBUG, LEVEL_COMPLETE
     */
    public static String levelName() {
        return switch (level) {
            case COMPLETE -> LEVEL_COMPLETE;
            case DEBUG -> LEVEL_DEBUG;
            case LOG -> LEVEL_LOG;
            default -> LEVEL_OFF;
        };
    }

    /**
     * Returns the path of the open log file.
     *
     * @return the log file path, or an empty string when no file is open
     */
    public static String logFilePath() {
        return logFile == null ? "" : logFile.toString();
    }

    /**
     * Normalizes a stored level name to one of the LEVEL_* constants.
     *
     * @param stored the level name from preferences; may be null
     * @return the canonical level name matching the value, or LEVEL_OFF
     */
    public static String canonicalLevelName(String stored) {
        return levelName(parseLevel(stored));
    }

    /**
     * Changes the current log level and opens or closes the log file as needed.
     *
     * @param name the level name, one of the LEVEL_* constants
     */
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

    /**
     * Changes the log directory and reopens the log file when logging is on.
     *
     * @param dir the directory path; null or blank means the system temp directory
     */
    public static void setLogDirectory(String dir) {
        synchronized (LOCK) {
            logDir = (dir == null || dir.isBlank()) ? null : Path.of(dir);
            if (level > 0) {
                closeFile();
                openFile();
            }
        }
    }

    /**
     * Writes a LOG level line to the log file and to the Burp Output tab.
     *
     * @param message the message to write
     */
    public static void log(String message) {
        if (level >= LOG) {
            writeLine(LEVEL_LOG, message);
            if (api != null) {
                api.logging().logToOutput(message);
            }
        }
    }

    /**
     * Writes a DEBUG level line to the log file only.
     *
     * @param message the message to write
     */
    public static void debug(String message) {
        if (level >= DEBUG) {
            writeLine(LEVEL_DEBUG, message);
        }
    }

    /**
     * Writes a COMPLETE level line to the log file only.
     *
     * @param message the message to write
     */
    public static void complete(String message) {
        if (level >= COMPLETE) {
            writeLine(LEVEL_COMPLETE, message);
        }
    }

    /**
     * Writes an error to the Burp Errors tab and, when LOG level is on, to the
     * log file.
     *
     * @param message the error message
     */
    public static void error(String message) {
        if (api != null) {
            api.logging().logToError(message);
        }
        if (level >= LOG) {
            writeLine("ERROR", message);
        }
    }

    /**
     * Flushes and closes the log file. Used as the extension unload handler.
     */
    public static void close() {
        synchronized (LOCK) {
            closeFile();
        }
    }

    /**
     * Opens a new log file in the log directory (or the system temp directory
     * when none is set) and writes an INFO header line.
     */
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

    /**
     * Flushes and closes the log file when one is open.
     */
    private static void closeFile() {
        if (writer != null) {
            writer.flush();
            writer.close();
            writer = null;
        }
    }

    /**
     * Writes one timestamped line to the log file and flushes it.
     *
     * @param levelTag the level tag for the line, e.g. "DEBUG"
     * @param message  the message to write
     */
    private static void writeLine(String levelTag, String message) {
        synchronized (LOCK) {
            if (writer == null) return;
            writer.println(LocalDateTime.now().format(LINE_TS) + " [" + levelTag + "] " + message);
            writer.flush();
        }
    }

    /**
     * Converts a level name to a numeric level.
     *
     * @param name the level name; may be null
     * @return the numeric level, or 0 when the name is unknown
     */
    private static int parseLevel(String name) {
        if (name == null) return 0;
        return switch (name.trim().toUpperCase()) {
            case LEVEL_COMPLETE, "TRACE" -> COMPLETE;
            case LEVEL_DEBUG -> DEBUG;
            case LEVEL_LOG -> LOG;
            default -> 0;
        };
    }

    /**
     * Converts a numeric level to a level name.
     *
     * @param lvl the numeric level
     * @return the matching LEVEL_* constant
     */
    private static String levelName(int lvl) {
        return switch (lvl) {
            case COMPLETE -> LEVEL_COMPLETE;
            case DEBUG -> LEVEL_DEBUG;
            case LOG -> LEVEL_LOG;
            default -> LEVEL_OFF;
        };
    }

    /**
     * Writes a message to the Burp Errors tab when the API is available.
     *
     * @param message the error message
     */
    private static void logToApiError(String message) {
        if (api != null) {
            api.logging().logToError(message);
        }
    }
}