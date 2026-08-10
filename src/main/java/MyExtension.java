import burp.api.montoya.BurpExtension;
import burp.api.montoya.MontoyaApi;
import burp.api.montoya.core.ByteArray;
import burp.api.montoya.http.message.responses.HttpResponse;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.time.Duration;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


/**
 * Main entry point of the extension. Implements {@link BurpExtension}.
 *
 * <p>Registers two suite tabs: "Settings POC" (LLM endpoint and logging
 * configuration) and "Chat POC" (the LLM chat panel). Also registers the
 * Repeater context-menu provider and the unload handler that closes the log
 * file.</p>
 *
 * <p>Chat requests are sent either through the Montoya HTTP client (non-streaming)
 * or over SSE with java.net.http.HttpClient (streaming). Streaming output is
 * re-rendered as Markdown on every delta.</p>
 *
 * <p>Request routing between Repeater and the chat panel works through two
 * static fields: {@link #chatInputBox} receives requests copied by the
 * context menu, and {@link #lastRepeaterService} remembers the target for
 * requests sent back to Repeater.</p>
 */
public class MyExtension implements BurpExtension {
    /** The chat input text area; filled by the Repeater context-menu provider. */
    static JTextArea chatInputBox;
    /** The last request service captured from Repeater via "Send to POC Chat". */
    static burp.api.montoya.http.HttpService lastRepeaterService;

    /** Accumulated Markdown of the message currently streaming in. */
    private final StringBuilder streamingMarkdown = new StringBuilder();
    /** Document offset where the streaming message starts. */
    private int streamingStartOffset = 0;
    /** Renders Markdown text into the chat pane's styled document. */
    private final MarkdownRenderer markdownRenderer = new MarkdownRenderer();
    /** Counter for chat runs; every send increments it. */
    private int chatRunCounter = 0;
    /** The run id of the currently active chat request. */
    private int currentRunId = 0;
    /** True while saved settings are being loaded into the settings UI. */
    private boolean restoringSettings = false;

    /**
     * Called once by Burp when the extension loads.
     *
     * <p>Initializes logging, builds the settings and chat tabs, loads saved
     * preferences, wires up all controls, refreshes the model list, and
     * registers the Repeater context-menu provider.</p>
     *
     * @param api the Montoya API instance
     */
    @Override
    public void initialize(MontoyaApi api) {
        LogManager.initialize(api);

        api.extension().setName("Burp Suite POC Extension");
        LogManager.log("Extension successfully loaded. Log level: " + LogManager.levelName());

        api.extension().registerUnloadingHandler(LogManager::close);

        // make settings tab
        JPanel extPanel = new JPanel(new GridBagLayout());
        extPanel.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));

        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(6, 6, 6, 6);
        gbc.anchor = GridBagConstraints.NORTHWEST;

        // section title
        gbc.gridx = 0;
        gbc.gridy = 0;
        gbc.gridwidth = 2;
        gbc.weightx = 1.0;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.insets = new Insets(0, 0, 10, 0);
        JLabel sectionTitle = new JLabel("LLM Provider Configuration");
        sectionTitle.setFont(sectionTitle.getFont().deriveFont(Font.BOLD, 14f));
        extPanel.add(sectionTitle, gbc);

        gbc.insets = new Insets(6, 6, 6, 6);
        gbc.gridwidth = 1;

        // api endpoint type
        gbc.gridx = 0;
        gbc.gridy = 1;
        gbc.weightx = 0;
        gbc.fill = GridBagConstraints.NONE;
        JLabel typeLabel = new JLabel("Endpoint Type:");
        typeLabel.setPreferredSize(new Dimension(120, 24));
        extPanel.add(typeLabel, gbc);

        JComboBox<String> apiEndpointDropdown = new JComboBox<>();
        String[] apiTypes = {"OpenAI-Compatible"};
        for (String i : apiTypes) {
            apiEndpointDropdown.addItem(i);
        }
        gbc.gridx = 1;
        gbc.weightx = 1.0;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        apiEndpointDropdown.setPreferredSize(new Dimension(300, 28));
        extPanel.add(apiEndpointDropdown, gbc);

        // api endpoint url
        gbc.gridx = 0;
        gbc.gridy = 2;
        gbc.weightx = 0;
        gbc.fill = GridBagConstraints.NONE;
        JLabel endpointLabel = new JLabel("Endpoint URL:");
        endpointLabel.setPreferredSize(new Dimension(120, 24));
        extPanel.add(endpointLabel, gbc);

        JTextField endpointField = new JTextField("");
        gbc.gridx = 1;
        gbc.weightx = 1.0;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        endpointField.setPreferredSize(new Dimension(300, 28));
        extPanel.add(endpointField, gbc);

        // api key
        gbc.gridx = 0;
        gbc.gridy = 3;
        gbc.weightx = 0;
        gbc.fill = GridBagConstraints.NONE;
        JLabel apiKeyLabel = new JLabel("API Key:");
        apiKeyLabel.setPreferredSize(new Dimension(120, 24));
        extPanel.add(apiKeyLabel, gbc);

        JPasswordField apiKeyField = new JPasswordField("");
        gbc.gridx = 1;
        gbc.weightx = 1.0;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        apiKeyField.setPreferredSize(new Dimension(300, 28));
        extPanel.add(apiKeyField, gbc);

        gbc.insets = new Insets(6, 6, 6, 6);

        // streaming checkbox
        gbc.gridx = 0;
        gbc.gridy = 4;
        gbc.gridwidth = 2;
        gbc.weightx = 1.0;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.anchor = GridBagConstraints.NORTHWEST;
        gbc.insets = new Insets(10, 0, 6, 0);
        JCheckBox streamingCheckbox = new JCheckBox("Response Streaming");
        streamingCheckbox.setSelected(true);
        extPanel.add(streamingCheckbox, gbc);

        gbc.insets = new Insets(6, 6, 6, 6);

        // api testing section title
        gbc.gridx = 0;
        gbc.gridy = 5;
        gbc.gridwidth = 2;
        gbc.weightx = 1.0;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.anchor = GridBagConstraints.NORTHWEST;
        gbc.insets = new Insets(10, 0, 6, 0);
        JLabel testingTitle = new JLabel("API Testing");
        testingTitle.setFont(testingTitle.getFont().deriveFont(Font.BOLD, 14f));
        extPanel.add(testingTitle, gbc);

        gbc.insets = new Insets(6, 6, 6, 6);

        // button row
        gbc.gridx = 0;
        gbc.gridy = 6;
        gbc.gridwidth = 2;
        gbc.weighty = 0;
        gbc.fill = GridBagConstraints.NONE;
        gbc.anchor = GridBagConstraints.WEST;
        JPanel buttonRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        buttonRow.setOpaque(false);

        JButton checkRateLimitButton = new JButton("Check Rate Limit");
        JButton listModelsButton = new JButton("List Models");
        JButton testChatButton = new JButton("Test Chat");
        buttonRow.add(checkRateLimitButton);
        buttonRow.add(listModelsButton);
        buttonRow.add(testChatButton);
        extPanel.add(buttonRow, gbc);

        // result text area
        gbc.gridx = 0;
        gbc.gridy = 7;
        gbc.gridwidth = 2;
        gbc.weightx = 1.0;
        gbc.weighty = 0;
        gbc.fill = GridBagConstraints.BOTH;
        gbc.anchor = GridBagConstraints.NORTHWEST;
        JTextArea resultArea = new JTextArea(6, 40);
        resultArea.setEditable(false);
        resultArea.setLineWrap(true);
        resultArea.setWrapStyleWord(true);
        resultArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        JScrollPane resultScroll = new JScrollPane(resultArea);
        resultScroll.setPreferredSize(new Dimension(0, 120));
        extPanel.add(resultScroll, gbc);

        // debug logging section title
        gbc.gridx = 0;
        gbc.gridy = 8;
        gbc.gridwidth = 2;
        gbc.weightx = 1.0;
        gbc.weighty = 0;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.anchor = GridBagConstraints.NORTHWEST;
        gbc.insets = new Insets(10, 0, 6, 0);
        JLabel debugTitle = new JLabel("Logging");
        debugTitle.setFont(debugTitle.getFont().deriveFont(Font.BOLD, 14f));
        extPanel.add(debugTitle, gbc);

        gbc.insets = new Insets(6, 6, 6, 6);

        // log level selector
        gbc.gridx = 0;
        gbc.gridy = 9;
        gbc.gridwidth = 1;
        gbc.weightx = 0;
        gbc.fill = GridBagConstraints.NONE;
        gbc.anchor = GridBagConstraints.NORTHWEST;
        JLabel logLevelLabel = new JLabel("Log Level:");
        logLevelLabel.setPreferredSize(new Dimension(120, 24));
        extPanel.add(logLevelLabel, gbc);

        JComboBox<String> logLevelDropdown = new JComboBox<>(
                new String[]{LogManager.LEVEL_OFF, LogManager.LEVEL_LOG, LogManager.LEVEL_DEBUG, LogManager.LEVEL_COMPLETE});
        logLevelDropdown.setSelectedItem(LogManager.levelName());
        gbc.gridx = 1;
        gbc.weightx = 1.0;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        logLevelDropdown.setPreferredSize(new Dimension(300, 28));
        extPanel.add(logLevelDropdown, gbc);

        gbc.insets = new Insets(6, 6, 6, 6);

        // log directory selection
        gbc.gridx = 0;
        gbc.gridy = 10;
        gbc.gridwidth = 1;
        gbc.weightx = 0;
        gbc.weighty = 0;
        gbc.fill = GridBagConstraints.NONE;
        gbc.anchor = GridBagConstraints.NORTHWEST;
        JLabel logDirLabel = new JLabel("Log Directory:");
        logDirLabel.setPreferredSize(new Dimension(120, 24));
        extPanel.add(logDirLabel, gbc);

        JPanel logDirRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        logDirRow.setOpaque(false);
        JTextField logDirField = new JTextField("");
        logDirField.setPreferredSize(new Dimension(280, 28));
        JButton browseLogButton = new JButton("Browse...");
        logDirRow.add(logDirField);
        logDirRow.add(browseLogButton);
        gbc.gridx = 1;
        gbc.weightx = 1.0;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        extPanel.add(logDirRow, gbc);

        // vertical spacer to push content to top
        gbc.gridy = 11;
        gbc.weighty = 1.0;
        gbc.fill = GridBagConstraints.BOTH;
        extPanel.add(Box.createGlue(), gbc);

        // load saved settings
        restoringSettings = true;
        if (api.persistence().preferences().stringKeys().contains("apiEndpointType")) {
            apiEndpointDropdown.setSelectedItem(api.persistence().preferences().getString("apiEndpointType"));
        }
        if (api.persistence().preferences().stringKeys().contains("apiEndpointUrl")) {
            endpointField.setText(api.persistence().preferences().getString("apiEndpointUrl"));
        }
        if (api.persistence().preferences().stringKeys().contains("apiKey")) {
            apiKeyField.setText(api.persistence().preferences().getString("apiKey"));
        }
        if (api.persistence().preferences().stringKeys().contains("streamEnabled")) {
            streamingCheckbox.setSelected(api.persistence().preferences().getString("streamEnabled").equals("true"));
        }
        if (api.persistence().preferences().stringKeys().contains("logLevel")) {
            logLevelDropdown.setSelectedItem(LogManager.canonicalLevelName(
                    api.persistence().preferences().getString("logLevel")));
        } else if (api.persistence().preferences().stringKeys().contains("debugEnabled")
                && api.persistence().preferences().getString("debugEnabled").equals("true")) {
            logLevelDropdown.setSelectedItem(LogManager.LEVEL_DEBUG);
        }
        if (api.persistence().preferences().stringKeys().contains("logDir")) {
            logDirField.setText(api.persistence().preferences().getString("logDir"));
        }
        restoringSettings = false;

        // auto-save on any setting change
        Runnable autoSave = () -> {
            if (restoringSettings) return;
            saveSettings(api, apiEndpointDropdown, endpointField, apiKeyField,
                    streamingCheckbox, logLevelDropdown, logDirField);
        };

        apiEndpointDropdown.addActionListener(e -> autoSave.run());
        streamingCheckbox.addActionListener(e -> autoSave.run());
        logLevelDropdown.addActionListener(e -> {
            LogManager.setLogLevel((String) logLevelDropdown.getSelectedItem());
            autoSave.run();
        });
        logDirField.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) { autoSave.run(); }
            @Override
            public void removeUpdate(DocumentEvent e) { autoSave.run(); }
            @Override
            public void changedUpdate(DocumentEvent e) { autoSave.run(); }
        });
        logDirField.addFocusListener(new FocusAdapter() {
            @Override
            public void focusLost(FocusEvent e) {
                LogManager.setLogDirectory(logDirField.getText());
            }
        });
        browseLogButton.addActionListener(e -> {
            JFileChooser chooser = new JFileChooser();
            chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            String current = logDirField.getText();
            if (!current.isBlank()) {
                File f = new File(current);
                if (f.isDirectory()) chooser.setCurrentDirectory(f);
            }
            if (chooser.showOpenDialog(extPanel) == JFileChooser.APPROVE_OPTION) {
                logDirField.setText(chooser.getSelectedFile().getAbsolutePath());
                LogManager.setLogDirectory(chooser.getSelectedFile().getAbsolutePath());
                autoSave.run();
            }
        });

        endpointField.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) { autoSave.run(); }
            @Override
            public void removeUpdate(DocumentEvent e) { autoSave.run(); }
            @Override
            public void changedUpdate(DocumentEvent e) { autoSave.run(); }
        });

        apiKeyField.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) { autoSave.run(); }
            @Override
            public void removeUpdate(DocumentEvent e) { autoSave.run(); }
            @Override
            public void changedUpdate(DocumentEvent e) { autoSave.run(); }
        });

        // check rate limit on button click TODO: this isn't working on API end?
        checkRateLimitButton.addActionListener(e -> {
            String endpoint = endpointField.getText().strip();
            String apiKey = new String(apiKeyField.getPassword()).strip();
            runSettingsTest(endpoint, apiKey, resultArea, "Checking rate limit...", () -> {
                HttpResponse response = sendApiRequest(api, endpoint, apiKey);

                StringBuilder allHeaders = new StringBuilder();
                for (burp.api.montoya.http.message.HttpHeader header : response.headers()) {
                    allHeaders.append(header.name()).append(": ").append(header.value()).append("\n");
                }
                LogManager.log("Rate limit check - HTTP " + response.statusCode());
                LogManager.debug("Rate limit headers:\n" + allHeaders);

                String limit = null, remaining = null, reset = null;
                for (burp.api.montoya.http.message.HttpHeader header : response.headers()) {
                    String name = header.name().toLowerCase();
                    switch (name) {
                        case "x-ai-ratelimit-limit-ai-proxy-openai-compatible" -> limit = header.value();
                        case "x-ai-ratelimit-remaining-ai-proxy-openai-compatible" -> remaining = header.value();
                        case "x-ai-ratelimit-reset-ai-proxy-openai-compatible" -> reset = header.value();
                    }
                }

                if (limit != null) {
                    resultArea.setText("Rate Limit:     " + limit + "\n"
                            + "Remaining:      " + remaining + "\n"
                            + "Reset (sec):    " + reset);
                } else {
                    resultArea.setText("HTTP " + response.statusCode() + " - No rate limit headers found.\n"
                            + "All response headers logged to Output tab.");
                }
            });
        });

        // list models on button click
        listModelsButton.addActionListener(e -> {
            String endpoint = endpointField.getText().strip();
            String apiKey = new String(apiKeyField.getPassword()).strip();
            runSettingsTest(endpoint, apiKey, resultArea, "Fetching models...", () -> {
                HttpResponse response = sendApiRequest(api, endpoint, apiKey);

                if (response.statusCode() == 200) {
                    resultArea.setText(response.bodyToString());
                } else {
                    resultArea.setText("HTTP " + response.statusCode() + "\n\n" + response.bodyToString());
                }
                LogManager.log("List models done - HTTP " + response.statusCode());
            });
        });

        // test chat on button click
        testChatButton.addActionListener(e -> {
            String endpoint = endpointField.getText().strip();
            String apiKey = new String(apiKeyField.getPassword()).strip();
            runSettingsTest(endpoint, apiKey, resultArea, "Sending test chat request...", () -> {
                String baseUrl = endpoint.replaceAll("/+$", "").replaceAll("/v1$", "");
                String json = "{\"model\":\"qwen3:latest\",\"messages\":[{\"role\":\"user\",\"content\":\"Hello\"}]}";

                burp.api.montoya.http.message.requests.HttpRequest request =
                        burp.api.montoya.http.message.requests.HttpRequest.httpRequestFromUrl(baseUrl + "/v1/chat/completions")
                                .withMethod("POST")
                                .withHeader("Authorization", "Bearer " + apiKey)
                                .withHeader("Content-Type", "application/json")
                                .withBody(json);

                HttpResponse response = api.http().sendRequest(request).response();

                if (response.statusCode() == 200) {
                    resultArea.setText(response.bodyToString());
                } else {
                    resultArea.setText("HTTP " + response.statusCode() + "\n\n" + response.bodyToString());
                }
                LogManager.log("Test chat done - HTTP " + response.statusCode());
            });
        });

        api.userInterface().registerSuiteTab("Settings POC", extPanel);

        // make chat tab
        JPanel chatTab = new JPanel(new BorderLayout());
        chatTab.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        // chat history
        JTextPane chatPane = new JTextPane();
        chatPane.setEditable(false);
        installChatPopup(chatPane, api);
        Color chatBg = chatPane.getBackground();
        if (chatBg != null && (chatBg.getRed() + chatBg.getGreen() + chatBg.getBlue()) / 3 < 128) {
            markdownRenderer.setLinkColor(new Color(110, 180, 255));
        }

        JScrollPane chatScroll = new JScrollPane(chatPane);

        // model selector row
        JPanel modelRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));

        JLabel modelLabel = new JLabel("Model:");
        JComboBox<String> modelDropdown = new JComboBox<>();
        JButton refreshModelsButton = new JButton("Refresh");
        JButton clearChatButton = new JButton("Clear Chat");

        modelRow.add(modelLabel);
        modelRow.add(modelDropdown);
        modelRow.add(refreshModelsButton);
        modelRow.add(clearChatButton);

        // progress bar
        JProgressBar chatProgress = new JProgressBar();
        chatProgress.setIndeterminate(true);
        chatProgress.setVisible(false);
        chatProgress.setMaximumSize(new Dimension(Integer.MAX_VALUE, 6));

        // top panel: pinned at NORTH
        JPanel topPanel = new JPanel();
        topPanel.setLayout(new BoxLayout(topPanel, BoxLayout.Y_AXIS));
        topPanel.add(modelRow);
        topPanel.add(Box.createVerticalStrut(4));
        topPanel.add(chatProgress);
        chatTab.add(topPanel, BorderLayout.NORTH);

        // input box
        JTextArea inputBox = new JTextArea(4, 40);
        chatInputBox = inputBox;
        inputBox.setLineWrap(true);
        inputBox.setWrapStyleWord(true);

        JScrollPane inputScroll = new JScrollPane(inputBox);
        inputScroll.setMinimumSize(new Dimension(0, 80));
        inputScroll.setPreferredSize(new Dimension(0, 80));

        // send button — fixed 90x80
        JButton sendButton = new JButton("SEND");
        sendButton.setPreferredSize(new Dimension(90, 80));
        sendButton.setMinimumSize(new Dimension(90, 80));
        sendButton.setMaximumSize(new Dimension(90, 80));

        // input panel: scroll expands, send button stays fixed
        JPanel inputPanel = new JPanel(new GridBagLayout());
        GridBagConstraints inputGbc = new GridBagConstraints();
        inputGbc.gridx = 0; inputGbc.gridy = 0;
        inputGbc.weightx = 1.0; inputGbc.weighty = 1.0;
        inputGbc.fill = GridBagConstraints.BOTH;
        inputPanel.add(inputScroll, inputGbc);
        inputGbc.gridx = 1; inputGbc.weightx = 0; inputGbc.weighty = 0;
        inputGbc.fill = GridBagConstraints.NONE;
        inputGbc.anchor = GridBagConstraints.SOUTHEAST;
        inputPanel.add(sendButton, inputGbc);

        // split pane for resizable input area
        JSplitPane chatSplit = new JSplitPane(JSplitPane.VERTICAL_SPLIT, chatScroll, inputPanel);
        chatSplit.setResizeWeight(1.0);
        chatSplit.setDividerSize(6);
        chatTab.add(chatSplit, BorderLayout.CENTER);

        inputBox.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke("ENTER"), "send");
        inputBox.getActionMap().put("send", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                sendButton.doClick();
            }
        });

        inputBox.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke("shift ENTER"), "newline");
        inputBox.getActionMap().put("newline", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                inputBox.replaceSelection("\n");
            }
        });

        List<String[]> chatHistory = new ArrayList<>();

        sendButton.addActionListener(e -> {
            String text = inputBox.getText();
            if (text.isBlank()) return;

            String model = (String) modelDropdown.getSelectedItem();
            if (model == null || model.isEmpty()) return;

            currentRunId = ++chatRunCounter;

            appendChatMessage(chatPane, "You", text);
            chatHistory.add(new String[]{"user", text});

            String streamPref = "true";
            if (api.persistence().preferences().stringKeys().contains("streamEnabled")) {
                streamPref = api.persistence().preferences().getString("streamEnabled");
            }
            boolean streaming = streamPref.equals("true");

            StringBuilder jsonBuilder = new StringBuilder();
            jsonBuilder.append("{\"model\":\"").append(escapeJson(model)).append("\",\"messages\":[");
            for (int i = 0; i < chatHistory.size(); i++) {
                if (i > 0) jsonBuilder.append(",");
                jsonBuilder.append("{\"role\":\"").append(chatHistory.get(i)[0]).append("\",\"content\":\"")
                        .append(escapeJson(chatHistory.get(i)[1])).append("\"}");
            }
            jsonBuilder.append("]");
            if (streaming) jsonBuilder.append(",\"stream\":true");
            jsonBuilder.append("}");
            String requestBody = jsonBuilder.toString();

            inputBox.setText("");

            chatProgress.setVisible(true);
            sendButton.setEnabled(false);
            inputBox.setEnabled(false);

            new Thread(() -> {
                try {
                    String endpoint = "";
                    String apiKey = "";
                    if (api.persistence().preferences().stringKeys().contains("apiEndpointUrl")) {
                        endpoint = api.persistence().preferences().getString("apiEndpointUrl");
                    }
                    if (api.persistence().preferences().stringKeys().contains("apiKey")) {
                        apiKey = api.persistence().preferences().getString("apiKey");
                    }

                    if (endpoint.isEmpty() || apiKey.isEmpty()) {
                        appendChatMessage(chatPane, "System", "Please configure API settings in Settings tab.");
                        return;
                    }

                    LogManager.log("Chat request body:\n" + requestBody);

                    String baseUrl = endpoint.replaceAll("/+$", "").replaceAll("/v1$", "");
                    LogManager.log("Chat request to " + baseUrl + "/v1/chat/completions (streaming=" + streaming + ")");

                    if (streaming) {
                        sendChatStreaming(apiKey, baseUrl, requestBody, model, chatPane, chatHistory,
                                chatProgress, sendButton, inputBox, streaming);
                    } else {
                        try {
                            burp.api.montoya.http.message.requests.HttpRequest request =
                                    burp.api.montoya.http.message.requests.HttpRequest.httpRequestFromUrl(baseUrl + "/v1/chat/completions")
                                            .withMethod("POST")
                                            .withHeader("Authorization", "Bearer " + apiKey)
                                            .withHeader("Content-Type", "application/json")
                                            .withBody(ByteArray.byteArray(requestBody.getBytes(StandardCharsets.UTF_8)));

                            HttpResponse response = api.http().sendRequest(request).response();

                            if (response.statusCode() == 200) {
                                String body = readResponseBody(response);
                                String content = extractContentFromResponse(body);
                                if (content != null) {
                                    LogManager.log("Chat response (run #" + currentRunId + "):\n" + content);
                                    appendChatMessage(chatPane, model, content);
                                    chatHistory.add(new String[]{"assistant", content});
                                } else {
                                    LogManager.error("Chat response parse failed, body:\n" + body);
                                    appendChatMessage(chatPane, "System", "Could not parse response content.\n" + body);
                                }
                            } else {
                                LogManager.error("Chat request HTTP " + response.statusCode() + ": " + readResponseBody(response));
                                appendChatMessage(chatPane, "System", "HTTP " + response.statusCode() + "\n" + readResponseBody(response));
                            }
                        } catch (Exception ex) {
                            LogManager.error("Chat request failed: " + ex.getMessage());
                            appendChatMessage(chatPane, "System", "Error - " + ex.getMessage());
                        }
                    }
                } finally {
                    SwingUtilities.invokeLater(() -> {
                        chatProgress.setVisible(false);
                        sendButton.setEnabled(true);
                        inputBox.setEnabled(true);
                    });
                }
            }).start();
        });

        // refresh models on button click
        refreshModelsButton.addActionListener(e -> new Thread(() -> refreshModels(modelDropdown, api)).start());

        clearChatButton.addActionListener(e -> {
            LogManager.log("Chat cleared by user");
            chatHistory.clear();
            chatPane.setText("");
            chatPane.getHighlighter().removeAllHighlights();
            streamingMarkdown.setLength(0);
            markdownRenderer.clearAnchors();
        });

        api.userInterface().registerSuiteTab("Chat POC", chatTab);

        // autopopulate models on load
        new Thread(() -> refreshModels(modelDropdown, api)).start();

        // register repeater context menu
        api.userInterface().registerContextMenuItemsProvider(new RepeaterContextMenuProvider());
    }

    /**
     * Sends a GET request to the configured LLM endpoint and returns the
     * response.
     *
     * <p>Used by the settings-testing tools and the model list refresh. The
     * request always targets the {@code /v1/models} endpoint, built from the
     * endpoint base URL with the trailing version segment and slashes
     * removed.</p>
     *
     * @param api      the Montoya API instance
     * @param endpoint the endpoint base URL, e.g. {@code https://api.example.com/v1}
     * @param apiKey   the API key for the Authorization header
     * @return the HTTP response
     */
    private HttpResponse sendApiRequest(MontoyaApi api, String endpoint, String apiKey) {
        String baseUrl = endpoint.replaceAll("/+$", "").replaceAll("/v1$", "");
        burp.api.montoya.http.message.requests.HttpRequest request =
                burp.api.montoya.http.message.requests.HttpRequest.httpRequestFromUrl(baseUrl + "/v1/models")
                        .withMethod("GET")
                        .withHeader("Authorization", "Bearer " + apiKey);

        return api.http().sendRequest(request).response();
    }

    /**
     * Appends a chat message to the chat pane.
     *
     * <p>Writes a bold {@code [speaker]:} header. Model messages are rendered
     * as Markdown and get code-box highlights; user and system messages are
     * written as plain text.</p>
     *
     * @param chatPane the chat text pane
     * @param speaker  the speaker name ("You", "System", or a model name)
     * @param message  the message text
     */
    private void appendChatMessage(JTextPane chatPane, String speaker, String message) {
        LogManager.debug("appendChatMessage: (run #" + currentRunId + ") speaker=" + speaker + ", "
                + message.length() + " chars");
        StyledDocument doc = chatPane.getStyledDocument();

        Style bold = chatPane.addStyle("bold", null);
        StyleConstants.setBold(bold, true);
        Style normal = chatPane.addStyle("normal", null);

        try {
            insertMessageSpacing(doc);
            doc.insertString(doc.getLength(), "[" + speaker + "]:\n", bold);
            if (isModelSpeaker(speaker)) {
                markdownRenderer.render(doc, message);
                refreshCodeBoxes(chatPane);
                doc.insertString(doc.getLength(), "\n\n", normal);
            } else {
                doc.insertString(doc.getLength(), message + "\n\n", normal);
            }
        } catch (BadLocationException e) {
            LogManager.error("appendChatMessage BadLocationException: " + e.getMessage());
        }

        chatPane.setCaretPosition(doc.getLength());
    }

    /**
     * Inserts one blank line between messages when the document is not empty.
     *
     * @param doc the chat document
     * @throws BadLocationException when the insert position is invalid
     */
    private void insertMessageSpacing(StyledDocument doc) throws BadLocationException {
        if (doc.getLength() == 0) return;
        doc.insertString(doc.getLength(), "\n", null);
    }

    /**
     * Repaints the highlight boxes around code blocks.
     *
     * <p>Scans the document for runs of text carrying the
     * {@link MarkdownRenderer#CODE_BLOCK_ATTR} attribute and adds a
     * {@link #codeBoxPainter} highlight over each run.</p>
     *
     * @param chatPane the chat text pane
     */
    private void refreshCodeBoxes(JTextPane chatPane) {
        StyledDocument doc = chatPane.getStyledDocument();
        chatPane.getHighlighter().removeAllHighlights();
        try {
            int len = doc.getLength();
            int i = 0;
            while (i < len) {
                Element el = doc.getCharacterElement(i);
                boolean isCode = el.getAttributes().isDefined(MarkdownRenderer.CODE_BLOCK_ATTR);
                int end = el.getEndOffset();
                if (isCode) {
                    while (end < len) {
                        Element next = doc.getCharacterElement(end);
                        if (!next.getAttributes().isDefined(MarkdownRenderer.CODE_BLOCK_ATTR)) break;
                        end = next.getEndOffset();
                    }
                    if (end > i) {
                        chatPane.getHighlighter().addHighlight(i, end, codeBoxPainter);
                    }
                }
                i = end;
            }
        } catch (BadLocationException e) {
            LogManager.error("refreshCodeBoxes BadLocationException: " + e.getMessage());
        }
    }

    /** Shared painter used to draw the code-box backgrounds. */
    private static final CodeBoxPainter codeBoxPainter = new CodeBoxPainter();

    /**
     * Paints a shaded rectangle with a border behind code-block text.
     */
    private static class CodeBoxPainter implements Highlighter.HighlightPainter {
        /** Color of the code-box border. */
        private static final Color BOX_BORDER = new Color(140, 140, 140);

        /**
         * Paints the highlight rectangle over the given text range.
         *
         * @param g      the graphics context
         * @param p0     the start offset of the highlighted range
         * @param p1     the end offset of the highlighted range
         * @param bounds the component bounds
         * @param c      the text component
         */
        @Override
        public void paint(Graphics g, int p0, int p1, Shape bounds, JTextComponent c) {
            if (p1 <= p0) return;
            try {
                java.awt.geom.Rectangle2D start2d = c.modelToView2D(p0);
                java.awt.geom.Rectangle2D end2d = c.modelToView2D(p1 - 1);
                if (start2d == null || end2d == null) return;
                Rectangle start = start2d.getBounds();
                Rectangle end = end2d.getBounds();
                Rectangle r = new Rectangle(start);
                r.add(end);
                r.grow(3, 3);
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setColor(c.getBackground().darker());
                g2.fillRect(0, r.y, c.getWidth(), r.height);
                g2.setColor(BOX_BORDER);
                g2.drawRect(1, r.y, c.getWidth() - 2, r.height - 1);
                g2.dispose();
            } catch (BadLocationException e) {
                LogManager.error("CodeBoxPainter BadLocationException: " + e.getMessage());
            }
        }
    }

    /**
     * Checks whether a speaker is a model (anything other than "You" or
     * "System").
     *
     * @param speaker the speaker name
     * @return true when the message should be rendered as Markdown
     */
    private boolean isModelSpeaker(String speaker) {
        return !"You".equals(speaker) && !"System".equals(speaker);
    }

    /**
     * Installs the mouse listeners on the chat pane: a popup menu for sending
     * selected text to Repeater, click handling for links, and a hover cursor
     * for links.
     *
     * @param chatPane the chat text pane
     * @param api      the Montoya API instance
     */
    private void installChatPopup(JTextPane chatPane, MontoyaApi api) {
        chatPane.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                if (e.isPopupTrigger()) showPopup(e, chatPane, api);
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                if (e.isPopupTrigger()) showPopup(e, chatPane, api);
            }

            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getButton() != MouseEvent.BUTTON1 || e.isPopupTrigger()) return;
                String url = linkUrlAt(chatPane, e.getPoint());
                if (url != null) {
                    LogManager.debug("Link clicked: " + url + (url.startsWith("#") ? " (anchor)" : " (external)"));
                    if (url.startsWith("#")) scrollToAnchor(chatPane, url.substring(1));
                    else openUrl(url);
                }
            }
        });
        chatPane.addMouseMotionListener(new MouseAdapter() {
            @Override
            public void mouseMoved(MouseEvent e) {
                String url = linkUrlAt(chatPane, e.getPoint());
                if (url != null) {
                    chatPane.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
                    chatPane.setToolTipText(url);
                } else {
                    chatPane.setCursor(Cursor.getDefaultCursor());
                    chatPane.setToolTipText(null);
                }
            }
        });
    }

    /**
     * Returns the link URL at a given point in the chat pane, if any.
     *
     * @param chatPane the chat text pane
     * @param point    the mouse position in component coordinates
     * @return the destination URL, or null when the position is not on a link
     */
    private String linkUrlAt(JTextPane chatPane, Point point) {
        StyledDocument doc = chatPane.getStyledDocument();
        int offset = chatPane.viewToModel2D(point);
        if (offset < 0 || offset >= doc.getLength()) return null;
        Object url = doc.getCharacterElement(offset).getAttributes().getAttribute(MarkdownRenderer.LINK_ATTR);
        return url instanceof String s ? s : null;
    }

    /** Extra margin above a heading when scrolling to an anchor link. */
    private static final int ANCHOR_SCROLL_MARGIN = 30;

    /**
     * Scrolls the chat pane to a registered heading anchor.
     *
     * <p>No-op when the anchor slug is unknown or its offset is outside the
     * document.</p>
     *
     * @param chatPane the chat text pane
     * @param slug     the anchor slug, e.g. {@code section-1}
     */
    private void scrollToAnchor(JTextPane chatPane, String slug) {
        Map<String, Integer> anchors = markdownRenderer.anchorOffsets();
        Integer offset = anchors.get(slug);
        if (offset == null) {
            LogManager.debug("scrollToAnchor: no target for #" + slug);
            return;
        }
        StyledDocument doc = chatPane.getStyledDocument();
        if (offset < 0 || offset >= doc.getLength()) {
            LogManager.debug("scrollToAnchor: offset " + offset + " out of range for #" + slug);
            return;
        }
        LogManager.debug("scrollToAnchor: #" + slug + " -> doc offset " + offset);
        chatPane.setCaretPosition(offset);
        try {
            Rectangle r = chatPane.modelToView2D(offset).getBounds();
            JScrollPane sp = (JScrollPane) SwingUtilities.getAncestorOfClass(JScrollPane.class, chatPane);
            if (sp != null) {
                sp.getVerticalScrollBar().setValue(Math.max(0, r.y - ANCHOR_SCROLL_MARGIN));
            } else {
                chatPane.scrollRectToVisible(r);
            }
        } catch (BadLocationException ex) {
            LogManager.error("scrollToAnchor BadLocationException: " + ex.getMessage());
        }
    }

    /**
     * Checks whether a URL may be opened in the system browser.
     *
     * <p>Only http:// and https:// URLs are allowed. This blocks schemes such
     * as file:, javascript:, and custom handlers that could be abused.</p>
     *
     * @param url the URL to check
     * @return true when the URL is safe to open
     */
    static boolean isSafeLinkUrl(String url) {
        if (url == null) return false;
        String lower = url.toLowerCase();
        return lower.startsWith("http://") || lower.startsWith("https://");
    }

    /**
     * Opens a URL in the system browser when it passes the safe-link check.
     *
     * @param url the URL to open
     */
    private void openUrl(String url) {
        if (!isSafeLinkUrl(url)) return;
        try {
            Desktop.getDesktop().browse(URI.create(url));
        } catch (Exception ex) {
            LogManager.error("openUrl failed: " + ex.getMessage());
        }
    }

    /**
     * Shows the chat-panel popup menu with the "Send to Repeater" item.
     *
     * <p>No-op when nothing is selected in the chat pane.</p>
     *
     * @param e        the mouse event that triggered the popup
     * @param chatPane the chat text pane
     * @param api      the Montoya API instance
     */
    private void showPopup(MouseEvent e, JTextPane chatPane, MontoyaApi api) {
        String selected = chatPane.getSelectedText();
        if (selected == null || selected.isBlank()) return;

        JPopupMenu menu = new JPopupMenu();
        JMenuItem sendItem = new JMenuItem("Send to Repeater");
        sendItem.addActionListener(ev -> sendSelectionToRepeater(chatPane, api, chatPane.getSelectedText()));
        menu.add(sendItem);
        menu.show(e.getComponent(), e.getX(), e.getY());
    }

    /**
     * Parses the selected text as an HTTP request and opens it in a new
     * Repeater tab.
     *
     * <p>Target resolution order:</p>
     * <ol>
     *   <li>Use the service captured from Repeater by "Send to POC Chat"
     *       ({@link #lastRepeaterService}), if any.</li>
     *   <li>Otherwise parse the {@code Host:} header from the selection via
     *       {@link #parseHostFromText}.</li>
     *   <li>Otherwise send the request without an explicit target.</li>
     * </ol>
     *
     * <p>Before parsing, the selection is normalized because chat text often
     * comes from model output: trailing whitespace is removed from each line,
     * then all line endings are converted to CRLF. Burp's raw request parser
     * needs CRLF delimiters; bare LF text is read as a single request line, so
     * headers are never parsed and the request is flagged as an HTTP/2
     * "kettled" request in Repeater. After parsing, stray CR/LF characters are
     * stripped from the method, path, and header values as a second line of
     * defense.</p>
     *
     * @param chatPane the chat text pane, used for error messages
     * @param api      the Montoya API instance
     * @param selected the selected text to parse
     */
    private void sendSelectionToRepeater(JTextPane chatPane, MontoyaApi api, String selected) {
        if (selected == null) return;

        String cleaned = normalizeLineEndings(normalizeRequestText(stripCodeFences(selected)));
        String textPreview = cleaned.length() > 120 ? cleaned.substring(0, 120) + "[...]" : cleaned;
        LogManager.debug("sendSelectionToRepeater: " + cleaned.length() + " chars, tab=\"" + repeaterTabCaption()
                + "\", text=\"" + textPreview.replace("\r", "\\r").replace("\n", "\\n") + "\"");
        try {
            burp.api.montoya.http.message.requests.HttpRequest request =
                    burp.api.montoya.http.message.requests.HttpRequest.httpRequest(cleaned);

            burp.api.montoya.http.HttpService service;
            String targetSource = null;
            if (MyExtension.lastRepeaterService != null) {
                service = MyExtension.lastRepeaterService;
                targetSource = "captured from Repeater";
            } else {
                service = parseHostFromText(cleaned);
                if (service != null) {
                    targetSource = "parsed from Host header";
                }
            }

            LogManager.debug("sendSelectionToRepeater: parsed request method=\""
                    + request.method().replace("\r", "\\r").replace("\n", "\\n")
                    + "\", path=\"" + request.path().replace("\r", "\\r").replace("\n", "\\n")
                    + "\", headers=" + request.headers().size()
                    + ", headerValue(Host)=" + request.headerValue("Host")
                    + ", httpService=" + (request.httpService() != null
                    ? request.httpService().host() + ":" + request.httpService().port()
                    + (request.httpService().secure() ? " (https)" : " (http)")
                    : "null"));

            if (service != null) {
                request = request.withService(service);
                LogManager.log("sendSelectionToRepeater: target set to " + service.host() + ":" + service.port()
                        + (service.secure() ? " (https)" : " (http)") + " [" + targetSource + "]");
            } else {
                LogManager.log("sendSelectionToRepeater: no target available (no captured service and no Host header in selection); sending without explicit target");
            }

            request = sanitizeRequestForRepeater(request);

            api.repeater().sendToRepeater(request, repeaterTabCaption());
        } catch (IllegalArgumentException ex) {
            LogManager.error("sendSelectionToRepeater: " + ex.getMessage());
            appendChatMessage(chatPane, "System", "Invalid HTTP request selected: " + ex.getMessage());
        }
    }

    /**
     * Removes CR and LF characters from the method, path, and header values of
     * a request, so Burp can display it without flagging it as an HTTP/2
     * "kettled" request.
     *
     * <p>Chat text often comes from model output, which may contain stray line
     * endings inside what should be a single path or header value. Burp cannot
     * represent a newline inside an HTTP/2 pseudo-header value in HTTP/1
     * syntax, so it marks such requests as kettled in the Repeater tab. This
     * step strips the stray characters while preserving the rest of the
     * request. Each changed part is logged at DEBUG level.</p>
     *
     * @param request the request to sanitize
     * @return the sanitized request; unchanged when no CR/LF is present
     */
    private burp.api.montoya.http.message.requests.HttpRequest sanitizeRequestForRepeater(
            burp.api.montoya.http.message.requests.HttpRequest request) {
        List<burp.api.montoya.http.message.HttpHeader> sanitizedHeaders = new ArrayList<>();
        boolean changed = false;
        for (burp.api.montoya.http.message.HttpHeader header : request.headers()) {
            String value = header.value();
            String cleanValue = stripCrLf(value);
            if (value != null && !cleanValue.equals(value)) {
                sanitizedHeaders.add(burp.api.montoya.http.message.HttpHeader.httpHeader(header.name(), cleanValue));
                changed = true;
                LogManager.debug("sendSelectionToRepeater: stripped CR/LF from header " + header.name());
            } else {
                sanitizedHeaders.add(header);
            }
        }

        String path = request.path();
        String cleanPath = stripCrLf(path);
        if (path != null && !cleanPath.equals(path)) {
            request = request.withPath(cleanPath);
            changed = true;
            LogManager.debug("sendSelectionToRepeater: stripped CR/LF from request path");
        }

        String method = request.method();
        String cleanMethod = stripCrLf(method);
        if (method != null && !cleanMethod.equals(method)) {
            request = request.withMethod(cleanMethod);
            changed = true;
            LogManager.debug("sendSelectionToRepeater: stripped CR/LF from request method");
        }

        if (changed) {
            request = request.withUpdatedHeaders(sanitizedHeaders);
        }
        return request;
    }

    /**
     * Removes all CR and LF characters from a string.
     *
     * @param s the string to clean; may be null
     * @return the string without CR/LF characters, or null when null
     */
    private String stripCrLf(String s) {
        if (s == null) return null;
        return s.replace("\r", "").replace("\n", "");
    }

    /** Matches a {@code Host:} header line and captures its value. */
    private static final Pattern HOST_HEADER_PATTERN =
            Pattern.compile("(?im)^[ \\t]*Host:[ \\t]*([^\\r\\n]+)");

    /**
     * Parses an {@link burp.api.montoya.http.HttpService} from the {@code Host:} header of a text.
     *
     * <p>Supports IPv4, hostnames, and bracketed IPv6, each with an optional
     * port. A missing port defaults to 80; port 443 implies https. The value
     * may be wrapped in trailing backticks, which are stripped.</p>
     *
     * @param text the text to search for a Host header
     * @return the parsed service, or null when no valid Host header exists
     */
    private burp.api.montoya.http.HttpService parseHostFromText(String text) {
        Matcher matcher = HOST_HEADER_PATTERN.matcher(text);
        if (!matcher.find()) {
            return null;
        }
        String hostHeader = matcher.group(1).trim();
        hostHeader = hostHeader.replaceAll("`+\\s*$", "").trim();
        if (hostHeader.isEmpty()) {
            return null;
        }

        String host;
        Integer port = null;
        if (hostHeader.startsWith("[")) {
            int close = hostHeader.indexOf(']');
            if (close < 0) return null;
            host = hostHeader.substring(1, close);
            String rest = hostHeader.substring(close + 1);
            if (rest.startsWith(":")) {
                port = parsePort(rest.substring(1));
            } else if (!rest.isEmpty()) {
                return null;
            }
        } else {
            int firstColon = hostHeader.indexOf(':');
            int lastColon = hostHeader.lastIndexOf(':');
            if (firstColon >= 0 && firstColon == lastColon) {
                host = hostHeader.substring(0, firstColon);
                port = parsePort(hostHeader.substring(firstColon + 1));
            } else if (firstColon >= 0) {
                return null;
            } else {
                host = hostHeader;
            }
        }

        if (host.isEmpty() || host.indexOf(' ') >= 0 || host.indexOf('\t') >= 0) {
            return null;
        }

        if (port == null) {
            return burp.api.montoya.http.HttpService.httpService(host, 80, false);
        }
        return burp.api.montoya.http.HttpService.httpService(host, port, port == 443);
    }

    /**
     * Parses and validates a port number.
     *
     * @param s the port text; null or empty returns null
     * @return the port number, or null when invalid or out of range
     */
    private Integer parsePort(String s) {
        if (s == null || s.isEmpty()) return null;
        try {
            int p = Integer.parseInt(s);
            if (p < 0 || p > 65535) return null;
            return p;
        } catch (NumberFormatException ex) {
            LogManager.debug("sendSelectionToRepeater: non-numeric port in Host header: \"" + s + "\"");
            return null;
        }
    }

    /**
     * Builds the tab caption for requests sent out of the chat panel.
     *
     * @return a caption with the current local time, e.g. "From Chat [2026-08-09 20:35:04]"
     */
    private String repeaterTabCaption() {
        return "From Chat [" + DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").format(LocalDateTime.now()) + "]";
    }

    /**
     * Removes an enclosing fenced-code-block marker from text.
     *
     * <p>Strips a leading ``` line and a trailing ``` line, then trims. Used
     * because the Repeater context menu wraps requests in a Markdown fence when
     * copying them into the chat input box.</p>
     *
     * @param text the text to clean
     * @return the cleaned text
     */
    private String stripCodeFences(String text) {
        String s = text.trim();
        if (s.startsWith("```")) {
            int newline = s.indexOf('\n');
            s = newline >= 0 ? s.substring(newline + 1) : "";
        }
        if (s.endsWith("```")) {
            int lastNewline = s.lastIndexOf('\n');
            s = lastNewline >= 0 ? s.substring(0, lastNewline) : "";
        }
        return s.trim();
    }

    /**
     * Removes trailing spaces and tabs from every line of a request text.
     *
     * <p>Model output often leaves trailing whitespace on the request line and
     * headers. The whitespace is harmless for what the user sees but can make
     * {@link burp.api.montoya.http.message.requests.HttpRequest} parsing
     * produce a request that Burp then flags as kettled. Stripping it before
     * parsing keeps the parsed request clean.</p>
     *
     * @param text the request text to normalize
     * @return the text with trailing per-line whitespace removed
     */
    private String normalizeRequestText(String text) {
        return text.replaceAll("(?m)[ \\t]+$", "");
    }

    /**
     * Converts all line endings in a request text to the CRLF sequence.
     *
     * <p>Burp's raw request parser expects CRLF-delimited HTTP text. Chat text
     * often uses bare {@code \n} (model output, Swing documents); given bare
     * {@code \n}, the whole selection is read as a single request line and
     * headers are never parsed, which later shows up as a kettled HTTP/2
     * request in Repeater. This method collapses existing {@code \r\n} pairs
     * and lone {@code \r} to {@code \n} first, then expands every {@code \n}
     * to {@code \r\n}.</p>
     *
     * @param text the request text to normalize
     * @return the text with CRLF line endings
     */
    private String normalizeLineEndings(String text) {
        return text.replace("\r\n", "\n").replace("\r", "\n").replace("\n", "\r\n");
    }

    /**
     * Reads the body of a Montoya response as a UTF-8 string.
     *
     * @param response the HTTP response
     * @return the response body text
     */
    private String readResponseBody(HttpResponse response) {
        return new String(response.body().getBytes(), StandardCharsets.UTF_8);
    }

    /**
     * Escapes a string for embedding inside a JSON string literal.
     *
     * <p>Escapes quotes, backslashes, and control characters that would break
     * the JSON structure.</p>
     *
     * @param s the string to escape
     * @return the escaped string
     */
    private String escapeJson(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\t' -> sb.append("\\t");
                case '\r' -> sb.append("\\r");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }

    /**
     * Unescapes the JSON string that starts at the given index.
     *
     * <p>Reads until the closing quote, decoding escaped quotes, backslashes,
     * newlines, tabs, carriage returns, and \\uXXXX sequences. Unknown escapes
     * are kept as-is.</p>
     *
     * @param s        the JSON text
     * @param startIdx the index of the first character after the opening quote
     * @return the unescaped string content
     */
    private static String unescapeJsonString(String s, int startIdx) {
        StringBuilder content = new StringBuilder();
        while (startIdx < s.length()) {
            char c = s.charAt(startIdx);
            if (c == '\\' && startIdx + 1 < s.length()) {
                char next = s.charAt(startIdx + 1);
                if (next == 'u' && startIdx + 5 < s.length() && isHexDigits(s, startIdx + 2)) {
                    content.append((char) Integer.parseInt(s.substring(startIdx + 2, startIdx + 6), 16));
                    startIdx += 6;
                } else {
                    switch (next) {
                        case '"' -> content.append('"');
                        case '\\' -> content.append('\\');
                        case 'n' -> content.append('\n');
                        case 't' -> content.append('\t');
                        case 'r' -> content.append('\r');
                        default -> content.append(c).append(next);
                    }
                    startIdx += 2;
                }
            } else if (c == '"') {
                break;
            } else {
                content.append(c);
                startIdx++;
            }
        }
        return content.toString();
    }

    /**
     * Checks whether the next four characters are all hexadecimal digits.
     *
     * @param s     the text to check
     * @param start the start index
     * @return true when all four characters in the range are hex digits
     */
    private static boolean isHexDigits(String s, int start) {
        for (int i = start; i < start + 4 && i < s.length(); i++) {
            char h = s.charAt(i);
            boolean digit = (h >= '0' && h <= '9') || (h >= 'a' && h <= 'f') || (h >= 'A' && h <= 'F');
            if (!digit) return false;
        }
        return true;
    }

    /**
     * Extracts the assistant message content from a chat-completion response.
     *
     * <p>Locates the first {@code choices[].message.content} string in the
     * response body and unescapes it.</p>
     *
     * @param body the raw response body
     * @return the extracted content, or null when not found
     */
    private String extractContentFromResponse(String body) {
        String choicesKey = "\"choices\":";
        int choicesIdx = body.indexOf(choicesKey);
        if (choicesIdx == -1) return null;

        String searchKey = "\"content\":\"";
        int idx = body.indexOf(searchKey, choicesIdx);
        if (idx == -1) return null;

        return unescapeJsonString(body, idx + searchKey.length());
    }

    /**
     * Sends a chat request with SSE streaming enabled and renders the response
     * live into the chat pane.
     *
     * <p>Runs on a background thread. Reads {@code data:} lines from the
     * stream, appends each content delta to the streaming buffer, and
     * re-renders the whole accumulated Markdown on the EDT. On success the
     * full response is added to the chat history.</p>
     *
     * @param apiKey       the API key for the Authorization header
     * @param baseUrl      the endpoint base URL without the version segment
     * @param requestBody  the JSON chat request body
     * @param model        the model name, used as the speaker label
     * @param chatPane     the chat text pane
     * @param chatHistory  the conversation history; receives the final response
     * @param chatProgress the progress bar to hide once the first chunk arrives
     * @param sendButton   the send button to re-enable
     * @param inputBox     the input box to re-enable
     * @param streaming    whether streaming is on (controls the request timeout)
     */
    private void sendChatStreaming(String apiKey, String baseUrl, String requestBody, String model,
            JTextPane chatPane, List<String[]> chatHistory, JProgressBar chatProgress,
            JButton sendButton, JTextArea inputBox, boolean streaming) {
        String url = baseUrl + "/v1/chat/completions";

        try (HttpClient client = HttpClient.newHttpClient()) {
            java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(streaming ? 120 : 300))
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8))
                    .build();

            java.net.http.HttpResponse<InputStream> rawResponse = client.send(request,
                    java.net.http.HttpResponse.BodyHandlers.ofInputStream());

            if (rawResponse.statusCode() != 200) {
                String errorBody;
                try (InputStream errBody = rawResponse.body()) {
                    errorBody = new String(errBody.readAllBytes(), StandardCharsets.UTF_8);
                }
                LogManager.error("Stream HTTP " + rawResponse.statusCode() + ": " + errorBody);
                SwingUtilities.invokeLater(() -> {
                    appendChatMessage(chatPane, "System", "HTTP " + rawResponse.statusCode() + "\n" + errorBody);
                    cleanupChatControls(chatProgress, sendButton, inputBox);
                });
                return;
            }

            LogManager.debug("sendChatStreaming: stream opened, awaiting SSE data");

            try (InputStream is = rawResponse.body();
                 BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {

                StringBuilder fullContent = new StringBuilder();
                String line;
                boolean firstChunk = true;
                int chunkCount = 0;

                while ((line = reader.readLine()) != null) {
                    if (line.startsWith("data: ")) {
                        String data = line.substring(6);
                        if (data.equals("[DONE]")) break;

                        String delta = extractDeltaContent(data);
                        if (!delta.isEmpty()) {
                            if (firstChunk) {
                                firstChunk = false;
                                SwingUtilities.invokeLater(() -> {
                                    chatProgress.setVisible(false);
                                    startStreamingMessage(chatPane, model);
                                });
                            }
                            fullContent.append(delta);
                            chunkCount++;
                            LogManager.complete("SSE delta +" + delta.length() + " chars (chunk " + chunkCount
                                    + ", total " + fullContent.length() + ")");
                            SwingUtilities.invokeLater(() -> appendStreamingContent(chatPane, delta));
                        }
                    }
                }

                LogManager.debug("Stream finished (run #" + currentRunId + "): " + chunkCount + " chunks, "
                        + fullContent.length() + " chars total");
                chatHistory.add(new String[]{"assistant", fullContent.toString()});
                SwingUtilities.invokeLater(() -> {
                    finishStreamingMessage(chatPane);
                    cleanupChatControls(chatProgress, sendButton, inputBox);
                });
            }

        } catch (Exception ex) {
            LogManager.error("sendChatStreaming exception: " + ex.getMessage());
            SwingUtilities.invokeLater(() -> {
                appendChatMessage(chatPane, "System", "Error - " + ex.getMessage());
                cleanupChatControls(chatProgress, sendButton, inputBox);
            });
        }
    }

    /**
     * Restores the chat controls after a chat request finishes or fails.
     *
     * @param chatProgress the progress bar to hide
     * @param sendButton   the send button to enable
     * @param inputBox     the input box to enable
     */
    private void cleanupChatControls(JProgressBar chatProgress, JButton sendButton, JTextArea inputBox) {
        chatProgress.setVisible(false);
        sendButton.setEnabled(true);
        inputBox.setEnabled(true);
    }

    /**
     * Saves the current settings values to Burp preferences.
     *
     * <p>Writes all settings, then logs a masked summary at DEBUG level and a
     * confirmation at LOG level.</p>
     *
     * @param api              the Montoya API instance
     * @param endpointDropdown the endpoint-type dropdown
     * @param endpointField    the endpoint URL field
     * @param apiKeyField      the API key field
     * @param streamingCheckbox the streaming checkbox
     * @param logLevelDropdown the log-level dropdown
     * @param logDirField      the log-directory field
     */
    private void saveSettings(MontoyaApi api, JComboBox<String> endpointDropdown,
            JTextField endpointField, JPasswordField apiKeyField, JCheckBox streamingCheckbox,
            JComboBox<String> logLevelDropdown, JTextField logDirField) {
        String apiKey = new String(apiKeyField.getPassword());
        api.persistence().preferences().setString("apiEndpointType", (String) endpointDropdown.getSelectedItem());
        api.persistence().preferences().setString("apiEndpointUrl", endpointField.getText());
        api.persistence().preferences().setString("apiKey", apiKey);
        api.persistence().preferences().setString("streamEnabled", Boolean.toString(streamingCheckbox.isSelected()));
        api.persistence().preferences().setString("logLevel", (String) logLevelDropdown.getSelectedItem());
        api.persistence().preferences().setString("logDir", logDirField.getText());
        LogManager.debug("Settings saved: endpointType=" + endpointDropdown.getSelectedItem()
                + ", endpointUrl=" + endpointField.getText()
                + ", apiKey=" + (apiKey.isEmpty() ? "(empty)" : maskApiKey(apiKey))
                + ", streamEnabled=" + streamingCheckbox.isSelected()
                + ", logLevel=" + logLevelDropdown.getSelectedItem()
                + ", logDir=" + (logDirField.getText().isBlank() ? "(default)" : logDirField.getText()));
        LogManager.log("Settings auto-saved.");
    }

    /**
     * Masks an API key for logging: first four and last four characters with
     * "..." in between.
     *
     * @param key the API key
     * @return the masked key, empty when blank, "****" when 8 characters or fewer
     */
    private String maskApiKey(String key) {
        if (key.isBlank()) return "";
        if (key.length() <= 8) return "****";
        return key.substring(0, 4) + "..." + key.substring(key.length() - 4);
    }

    /**
     * Runs one of the settings-testing tools on a background thread.
     *
     * <p>Shows a loading message first and requires both the endpoint and the
     * API key to be filled in. Exceptions from the task are shown in the result
     * area.</p>
     *
     * @param endpoint    the endpoint URL
     * @param apiKey      the API key
     * @param resultArea  the result text area
     * @param loadingText the text shown while the task runs
     * @param task        the task to run; must update resultArea itself
     */
    private void runSettingsTest(String endpoint, String apiKey, JTextArea resultArea,
            String loadingText, Runnable task) {
        if (endpoint.isEmpty() || apiKey.isEmpty()) {
            resultArea.setText("Please fill in Endpoint URL and API Key first.");
            return;
        }

        resultArea.setText(loadingText);
        resultArea.repaint();

        new Thread(() -> {
            try {
                task.run();
            } catch (Exception ex) {
                resultArea.setText("Error: " + ex.getMessage());
            }
            resultArea.repaint();
        }).start();
    }

    /**
     * Starts a streaming message: writes the bold speaker header, resets the
     * streaming buffer, and records the start offset.
     *
     * @param chatPane the chat text pane
     * @param speaker  the speaker name (the model name)
     */
    private void startStreamingMessage(JTextPane chatPane, String speaker) {
        StyledDocument doc = chatPane.getStyledDocument();
        Style bold = chatPane.addStyle("streamBold", null);
        StyleConstants.setBold(bold, true);
        try {
            insertMessageSpacing(doc);
            doc.insertString(doc.getLength(), "[" + speaker + "]:\n", bold);
        } catch (BadLocationException e) {
            LogManager.error("startStreamingMessage BadLocationException: " + e.getMessage());
        }
        streamingMarkdown.setLength(0);
        streamingStartOffset = doc.getLength();
        LogManager.debug("startStreamingMessage: (run #" + currentRunId + ") speaker=" + speaker
                + ", startOffset=" + streamingStartOffset);
        chatPane.setCaretPosition(doc.getLength());
    }

    /**
     * Appends a streaming delta: rebuilds the streaming message by removing the
     * previous content and re-rendering the full accumulated Markdown.
     *
     * @param chatPane the chat text pane
     * @param content  the new delta to append
     */
    private void appendStreamingContent(JTextPane chatPane, String content) {
        LogManager.complete("appendStreamingContent: +" + content.length() + " chars (total markdown "
                + (streamingMarkdown.length() + content.length()) + ")");
        StyledDocument doc = chatPane.getStyledDocument();
        streamingMarkdown.append(content);
        try {
            doc.remove(streamingStartOffset, doc.getLength() - streamingStartOffset);
        } catch (BadLocationException e) {
            LogManager.error("appendStreamingContent BadLocationException: " + e.getMessage());
            return;
        }
        markdownRenderer.render(doc, streamingMarkdown.toString());
        refreshCodeBoxes(chatPane);
        chatPane.setCaretPosition(doc.getLength());
    }

    /**
     * Finishes a streaming message: appends trailing blank lines, logs the full
     * response at LOG level, and clears the streaming buffer.
     *
     * @param chatPane the chat text pane
     */
    private void finishStreamingMessage(JTextPane chatPane) {
        StyledDocument doc = chatPane.getStyledDocument();
        Style normal = chatPane.addStyle("streamNormal", null);
        try {
            doc.insertString(doc.getLength(), "\n\n", normal);
        } catch (BadLocationException e) {
            LogManager.error("finishStreamingMessage BadLocationException: " + e.getMessage());
        }
        LogManager.log("Stream response (run #" + currentRunId + "):\n" + streamingMarkdown);
        streamingMarkdown.setLength(0);
        LogManager.debug("finishStreamingMessage: (run #" + currentRunId + ") doc length=" + doc.getLength());
        chatPane.setCaretPosition(doc.getLength());
    }

    /**
     * Extracts the content delta from one SSE data payload.
     *
     * @param data the payload text after the {@code data: } prefix
     * @return the unescaped delta content, or an empty string when absent
     */
    private String extractDeltaContent(String data) {
        String searchKey = "\"delta\":";
        int deltaIdx = data.indexOf(searchKey);
        if (deltaIdx == -1) return "";

        String contentKey = "\"content\":\"";
        int contentIdx = data.indexOf(contentKey, deltaIdx);
        if (contentIdx == -1) return "";

        return unescapeJsonString(data, contentIdx + contentKey.length());
    }

    /**
     * Populates the model dropdown from the endpoint's model list.
     *
     * <p>Reads the saved endpoint and API key from preferences and stops early
     * when either is missing. Model ids are extracted from the JSON response;
     * failures only log an error.</p>
     *
     * @param modelDropdown the model dropdown to fill
     * @param api           the Montoya API instance
     */
    private void refreshModels(JComboBox<String> modelDropdown, MontoyaApi api) {
        String endpoint = "";
        String apiKey = "";
        if (api.persistence().preferences().stringKeys().contains("apiEndpointUrl")) {
            endpoint = api.persistence().preferences().getString("apiEndpointUrl");
        }
        if (api.persistence().preferences().stringKeys().contains("apiKey")) {
            apiKey = api.persistence().preferences().getString("apiKey");
        }

        if (endpoint.isEmpty() || apiKey.isEmpty()) {
            return;
        }

        try {
            HttpResponse response = sendApiRequest(api, endpoint, apiKey);
            if (response.statusCode() == 200) {
                String body = response.bodyToString();
                modelDropdown.removeAllItems();
                int idx = 0;
                while ((idx = body.indexOf("\"id\":\"", idx)) != -1) {
                    int start = idx + 6;
                    int end = body.indexOf("\"", start);
                    if (end != -1) {
                        modelDropdown.addItem(body.substring(start, end));
                        idx = end + 1;
                    } else {
                        break;
                    }
                }
            }
            LogManager.log("refreshModels: loaded " + modelDropdown.getItemCount() + " models");
        } catch (Exception ex) {
            LogManager.error("Failed to refresh models: " + ex.getMessage());
        }
    }
}

