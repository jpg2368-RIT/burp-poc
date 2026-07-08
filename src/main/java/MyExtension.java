import burp.api.montoya.BurpExtension;
import burp.api.montoya.MontoyaApi;

import javax.swing.*;
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

        JPanel extPanel = new JPanel();
        extPanel.setLayout(new BoxLayout(extPanel, BoxLayout.Y_AXIS));

        List<MenuItem> menuItems = new ArrayList<>();

        menuItems.add(new MenuItem("API Endpoint", new JTextField("https://www.example.com")));
        menuItems.add(new MenuItem("API Key", new JTextField("")));

        for (MenuItem menuItem: menuItems) {
            JPanel row = new JPanel();
            row.setLayout(new BoxLayout(row, BoxLayout.X_AXIS));

            JLabel label = new JLabel(menuItem.label);
            JComponent input = menuItem.input;

            row.add(label);
            row.add(input);

            extPanel.add(row);
        }
        api.userInterface().registerSuiteTab("Burp POC Extension Tab", extPanel);
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