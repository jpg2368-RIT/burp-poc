import burp.api.montoya.BurpExtension;
import burp.api.montoya.MontoyaApi;

import javax.swing.*;
import java.awt.*;


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
        String[] apiTypes = {
                "OpenAI-Compatible",
//                "Burp AI",
//                "Anthropic",
//                "Claude CLI",
//                "Codex CLI",
//                "Copilot CLI",
//                "Gemini CLI",
//                "LMStudio",
//                "Nvidia NIM",
//                "Ollama",
//                "OpenCode CLI",
//                "Perplexity"
        };
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

        // spacer
        gbc.gridx = 0;
        gbc.gridy = 4;
        gbc.gridwidth = 2;
        gbc.weighty = 1.0;
        extPanel.add(Box.createGlue(), gbc);

        // save button row
        gbc.gridy = 5;
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

        api.userInterface().registerSuiteTab("Settings POC", extPanel);

        // make chat tab
        JPanel chatTab = new JPanel();
        chatTab.setLayout(new BoxLayout(chatTab, BoxLayout.Y_AXIS));
        chatTab.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        // chat history
        JTextArea chatArea = new JTextArea();
        chatArea.setEditable(false);
        chatArea.setLineWrap(true);
        chatArea.setWrapStyleWord(true);

        JScrollPane chatScroll = new JScrollPane(chatArea);
        chatScroll.setAlignmentX(Component.LEFT_ALIGNMENT);
        chatScroll.setPreferredSize(new Dimension(0, 600));
        chatScroll.setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));

        chatTab.add(chatScroll);
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

        chatTab.add(inputParts);

        api.userInterface().registerSuiteTab("Chat POC", chatTab);
    }
}

