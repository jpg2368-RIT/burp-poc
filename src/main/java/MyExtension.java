import burp.api.montoya.BurpExtension;
import burp.api.montoya.MontoyaApi;

import javax.swing.*;
import java.awt.*;
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
        JPanel extPanel = new JPanel();
        extPanel.setLayout(new BoxLayout(extPanel, BoxLayout.Y_AXIS));

        List<MenuItem> menuItems = new ArrayList<>();

        menuItems.add(new MenuItem("API Endpoint", new JTextField("")));
        menuItems.add(new MenuItem("API Key", new JTextField("")));

        for (MenuItem menuItem : menuItems) {
            JPanel row = new JPanel();
            row.setLayout(new BoxLayout(row, BoxLayout.X_AXIS));

            JLabel label = new JLabel(menuItem.label);
            JComponent input = menuItem.input;

            row.add(label);
            row.add(input);

            extPanel.add(row);
        }
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

class MenuItem {
    public String label;
    public JComponent input;

    public MenuItem(String labelText, JComponent inputComponent) {
        this.label = labelText;
        this.input = inputComponent;
    }
}