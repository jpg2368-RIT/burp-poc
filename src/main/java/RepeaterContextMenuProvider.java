import burp.api.montoya.core.ToolType;
import burp.api.montoya.ui.contextmenu.ContextMenuEvent;
import burp.api.montoya.ui.contextmenu.ContextMenuItemsProvider;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.http.message.HttpRequestResponse;

import javax.swing.*;
import java.awt.Component;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class RepeaterContextMenuProvider implements ContextMenuItemsProvider {
    @Override
    public List<Component> provideMenuItems(ContextMenuEvent event) {
        List<Component> items = new ArrayList<>();

        if (!event.isFromTool(ToolType.REPEATER)) {
            return items;
        }

        String requestText = getRequestText(event);
        if (requestText == null) return items;

        JMenuItem menuItem = new JMenuItem("Send to POC Chat");
        menuItem.addActionListener(e -> SwingUtilities.invokeLater(() -> {
            if (MyExtension.chatInputBox != null) {
                MyExtension.chatInputBox.setText("Here is a request from the Repeater tab of Burp Suite by the user:\n```\n" + requestText + "\n```\n");
            }
        }));

        items.add(menuItem);
        return items;
    }

    private String getRequestText(ContextMenuEvent event) {
        Optional<?> msgOpt = event.messageEditorRequestResponse();
        if (msgOpt.isPresent()) {
            Object msg = msgOpt.get();
            if (msg instanceof burp.api.montoya.ui.contextmenu.MessageEditorHttpRequestResponse) {
                HttpRequestResponse reqRes = ((burp.api.montoya.ui.contextmenu.MessageEditorHttpRequestResponse) msg).requestResponse();
                if (reqRes != null && reqRes.request() != null) {
                    return reqRes.request().toString();
                }
            }
        }

        List<HttpRequestResponse> selections = event.selectedRequestResponses();
        if (!selections.isEmpty()) {
            HttpRequest req = selections.getFirst().request();
            if (req != null) {
                return req.toString();
            }
        }

        return null;
    }
}
