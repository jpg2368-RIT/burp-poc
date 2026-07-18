import burp.api.montoya.BurpExtension;
import burp.api.montoya.MontoyaApi;
import burp.api.montoya.http.message.responses.HttpResponse;

import javax.swing.*;
import javax.swing.text.*;
import java.awt.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;


public class MyExtension implements BurpExtension {
    @Override
    public void initialize(MontoyaApi api) {
        MAPI.initialize(api);

        api.extension().setName("Burp Suite POC Extension");
        api.logging().logToOutput("Extension successfully loaded :)");

        String hash = "";
        if (api.persistence().preferences().stringKeys().contains("hash")) {
            hash = api.persistence().preferences().getString("hash");
        }

        MyHttpHandler handler = new MyHttpHandler(hash);
        api.http().registerHttpHandler(handler);

        api.extension().registerUnloadingHandler(new UnloadingHandler(handler));

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

        // api testing section title
        gbc.gridx = 0;
        gbc.gridy = 4;
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
        gbc.gridy = 5;
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
        gbc.gridy = 6;
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

        // vertical spacer
        gbc.gridx = 0;
        gbc.gridy = 7;
        gbc.gridwidth = 2;
        gbc.weighty = 1.0;
        gbc.fill = GridBagConstraints.BOTH;
        extPanel.add(Box.createGlue(), gbc);

        // save button
        gbc.gridx = 0;
        gbc.gridy = 8;
        gbc.gridwidth = 2;
        gbc.weighty = 0;
        gbc.fill = GridBagConstraints.NONE;
        gbc.anchor = GridBagConstraints.SOUTHEAST;
        JButton saveButton = new JButton("Save Settings");
        extPanel.add(saveButton, gbc);

        // load saved settings
        if (api.persistence().preferences().stringKeys().contains("apiEndpointType")) {
            apiEndpointDropdown.setSelectedItem(api.persistence().preferences().getString("apiEndpointType"));
        }
        if (api.persistence().preferences().stringKeys().contains("apiEndpointUrl")) {
            endpointField.setText(api.persistence().preferences().getString("apiEndpointUrl"));
        }
        if (api.persistence().preferences().stringKeys().contains("apiKey")) {
            apiKeyField.setText(api.persistence().preferences().getString("apiKey"));
        }

        // save settings on button click
        saveButton.addActionListener(e -> {
            api.persistence().preferences().setString("apiEndpointType", (String) apiEndpointDropdown.getSelectedItem());
            api.persistence().preferences().setString("apiEndpointUrl", endpointField.getText());
            api.persistence().preferences().setString("apiKey", new String(apiKeyField.getPassword()));
            api.logging().logToOutput("Settings saved.");
        });

        // check rate limit on button click TODO: this isn't working on API end?
        checkRateLimitButton.addActionListener(e -> {
            String endpoint = endpointField.getText().strip();
            String apiKey = new String(apiKeyField.getPassword()).strip();

            if (endpoint.isEmpty() || apiKey.isEmpty()) {
                resultArea.setText("Please fill in Endpoint URL and API Key first.");
                return;
            }

            resultArea.setText("Checking rate limit...");
            resultArea.repaint();

            new Thread(() -> {
                try {
                    HttpResponse response = sendApiRequest(api, endpoint, apiKey, "/v1/models");

                    StringBuilder allHeaders = new StringBuilder();
                    for (burp.api.montoya.http.message.HttpHeader header : response.headers()) {
                        allHeaders.append(header.name()).append(": ").append(header.value()).append("\n");
                    }
                    api.logging().logToOutput("Rate limit check - HTTP " + response.statusCode() + "\nHeaders:\n" + allHeaders);

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
                } catch (Exception ex) {
                    resultArea.setText("Error: " + ex.getMessage());
                }
                resultArea.repaint();
            }).start();
        });

        // list models on button click
        listModelsButton.addActionListener(e -> {
            String endpoint = endpointField.getText().strip();
            String apiKey = new String(apiKeyField.getPassword()).strip();

            if (endpoint.isEmpty() || apiKey.isEmpty()) {
                resultArea.setText("Please fill in Endpoint URL and API Key first.");
                return;
            }

            resultArea.setText("Fetching models...");
            resultArea.repaint();

            new Thread(() -> {
                try {
                    HttpResponse response = sendApiRequest(api, endpoint, apiKey, "/v1/models");

                    if (response.statusCode() == 200) {
                        resultArea.setText(response.bodyToString());
                    } else {
                        resultArea.setText("HTTP " + response.statusCode() + "\n\n" + response.bodyToString());
                    }
                    api.logging().logToOutput("List models - HTTP " + response.statusCode());
                } catch (Exception ex) {
                    resultArea.setText("Error: " + ex.getMessage());
                }
                resultArea.repaint();
            }).start();
        });

        // test chat on button click
        testChatButton.addActionListener(e -> {
            String endpoint = endpointField.getText().strip();
            String apiKey = new String(apiKeyField.getPassword()).strip();

            if (endpoint.isEmpty() || apiKey.isEmpty()) {
                resultArea.setText("Please fill in Endpoint URL and API Key first.");
                return;
            }

            resultArea.setText("Sending test chat request...");
            resultArea.repaint();

            new Thread(() -> {
                try {
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
                    api.logging().logToOutput("Test chat - HTTP " + response.statusCode());
                } catch (Exception ex) {
                    resultArea.setText("Error: " + ex.getMessage());
                }
                resultArea.repaint();
            }).start();
        });

        api.userInterface().registerSuiteTab("Settings POC", extPanel);

        // make chat tab
        JPanel chatTab = new JPanel();
        chatTab.setLayout(new BoxLayout(chatTab, BoxLayout.Y_AXIS));
        chatTab.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        // chat history
        JTextPane chatPane = new JTextPane();
        chatPane.setEditable(false);

        JScrollPane chatScroll = new JScrollPane(chatPane);
        chatScroll.setAlignmentX(Component.LEFT_ALIGNMENT);
        chatScroll.setPreferredSize(new Dimension(0, 0));
        chatScroll.setMinimumSize(new Dimension(0, 100));
        chatScroll.setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));

        chatTab.add(chatScroll);
        chatTab.add(Box.createVerticalStrut(10));

        // model selector row
        JPanel modelRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        modelRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        modelRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, 40));

        JLabel modelLabel = new JLabel("Model:");
        JComboBox<String> modelDropdown = new JComboBox<>();
        JButton refreshModelsButton = new JButton("Refresh");

        modelRow.add(modelLabel);
        modelRow.add(modelDropdown);
        modelRow.add(refreshModelsButton);
        chatTab.add(modelRow);
        chatTab.add(Box.createVerticalStrut(10));

        // input row
        JPanel inputParts = new JPanel();
        inputParts.setLayout(new BoxLayout(inputParts, BoxLayout.X_AXIS));
        inputParts.setAlignmentX(Component.LEFT_ALIGNMENT);
        inputParts.setMaximumSize(new Dimension(Integer.MAX_VALUE, 80));

        // input box
        JTextArea inputBox = new JTextArea(4, 40);
        inputBox.setLineWrap(true);
        inputBox.setWrapStyleWord(true);

        JScrollPane inputScroll = new JScrollPane(inputBox);
        inputScroll.setPreferredSize(new Dimension(0, 80));
        inputScroll.setMaximumSize(new Dimension(Integer.MAX_VALUE, 80));

        inputParts.add(inputScroll);
        inputParts.add(Box.createHorizontalStrut(8));

        // send button
        JButton sendButton = new JButton("SEND");
        sendButton.setPreferredSize(new Dimension(90, 80));
        sendButton.setMinimumSize(new Dimension(90, 80));
        sendButton.setMaximumSize(new Dimension(90, 80));

        inputParts.add(sendButton);

        List<String[]> chatHistory = new ArrayList<>();

        sendButton.addActionListener(e -> {
            String text = inputBox.getText();
            if (text.isBlank()) return;

            String model = (String) modelDropdown.getSelectedItem();
            if (model == null || model.isEmpty()) return;

            appendChatMessage(chatPane, "You", text);
            chatHistory.add(new String[]{"user", text});

            StringBuilder jsonBuilder = new StringBuilder();
            jsonBuilder.append("{\"model\":\"").append(escapeJson(model)).append("\",\"messages\":[");
            for (int i = 0; i < chatHistory.size(); i++) {
                if (i > 0) jsonBuilder.append(",");
                jsonBuilder.append("{\"role\":\"").append(chatHistory.get(i)[0]).append("\",\"content\":\"")
                        .append(escapeJson(chatHistory.get(i)[1])).append("\"}");
            }
            jsonBuilder.append("]}");
            String requestBody = jsonBuilder.toString();

            inputBox.setText("");

            new Thread(() -> {
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

                try {
                    String baseUrl = endpoint.replaceAll("/+$", "").replaceAll("/v1$", "");
                    burp.api.montoya.http.message.requests.HttpRequest request =
                            burp.api.montoya.http.message.requests.HttpRequest.httpRequestFromUrl(baseUrl + "/v1/chat/completions")
                                    .withMethod("POST")
                                    .withHeader("Authorization", "Bearer " + apiKey)
                                    .withHeader("Content-Type", "application/json")
                                    .withBody(requestBody);

                    HttpResponse response = api.http().sendRequest(request).response();

                    if (response.statusCode() == 200) {
                        String body = readResponseBody(response);
                        String content = extractContentFromResponse(body);
                        if (content != null) {
                            appendChatMessage(chatPane, model, content);
                            chatHistory.add(new String[]{"assistant", content});
                        } else {
                            appendChatMessage(chatPane, "System", "Could not parse response content.\n" + body);
                        }
                    } else {
                        appendChatMessage(chatPane, "System", "HTTP " + response.statusCode() + "\n" + readResponseBody(response));
                    }
                } catch (Exception ex) {
                    appendChatMessage(chatPane, "System", "Error - " + ex.getMessage());
                }
            }).start();
        });

        chatTab.add(inputParts);

        // refresh models on button click
        refreshModelsButton.addActionListener(e -> new Thread(() -> refreshModels(modelDropdown, api)).start());

        api.userInterface().registerSuiteTab("Chat POC", chatTab);

        // autopopulate models on load
        new Thread(() -> refreshModels(modelDropdown, api)).start();
    }

    private HttpResponse sendApiRequest(MontoyaApi api, String endpoint, String apiKey, String path) {
        String baseUrl = endpoint.replaceAll("/+$", "").replaceAll("/v1$", "");
        burp.api.montoya.http.message.requests.HttpRequest request =
                burp.api.montoya.http.message.requests.HttpRequest.httpRequestFromUrl(baseUrl + path)
                        .withMethod("GET")
                        .withHeader("Authorization", "Bearer " + apiKey);

        return api.http().sendRequest(request).response();
    }

    private void appendChatMessage(JTextPane chatPane, String speaker, String message) {
        StyledDocument doc = chatPane.getStyledDocument();

        Style bold = chatPane.addStyle("bold", null);
        StyleConstants.setBold(bold, true);
        Style normal = chatPane.addStyle("normal", null);

        try {
            doc.insertString(doc.getLength(), "[" + speaker + "]: ", bold);
            doc.insertString(doc.getLength(), message + "\n\n", normal);
        } catch (BadLocationException e) {
            // shouldn't happen
        }

        chatPane.setCaretPosition(doc.getLength());
    }

    private String readResponseBody(HttpResponse response) {
        return new String(response.body().getBytes(), StandardCharsets.UTF_8);
    }

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

    private String extractContentFromResponse(String body) {
        String searchKey = "\"content\":\"";
        int idx = body.indexOf(searchKey);
        if (idx == -1) return null;
        idx += searchKey.length();
        StringBuilder content = new StringBuilder();
        while (idx < body.length()) {
            char c = body.charAt(idx);
            if (c == '\\' && idx + 1 < body.length()) {
                char next = body.charAt(idx + 1);
                switch (next) {
                    case '"' -> content.append('"');
                    case '\\' -> content.append('\\');
                    case 'n' -> content.append('\n');
                    case 't' -> content.append('\t');
                    case 'r' -> content.append('\r');
                    default -> content.append(c).append(next);
                }
                idx += 2;
            } else if (c == '"') {
                break;
            } else {
                content.append(c);
                idx++;
            }
        }
        return content.toString();
    }

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
            HttpResponse response = sendApiRequest(api, endpoint, apiKey, "/v1/models");
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
        } catch (Exception ex) {
            api.logging().logToOutput("Failed to refresh models: " + ex.getMessage());
        }
    }
}

