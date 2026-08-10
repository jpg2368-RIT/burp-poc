import burp.api.montoya.core.ToolType;
import burp.api.montoya.ui.contextmenu.ContextMenuEvent;
import burp.api.montoya.ui.contextmenu.ContextMenuItemsProvider;
import burp.api.montoya.http.HttpService;
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

        RequestAndService ras = getRequestAndService(event);
        if (ras == null) {
            LogManager.debug("RepeaterContextMenuProvider: no request text available, skipping menu");
            return items;
        }
        LogManager.debug("RepeaterContextMenuProvider: menu shown, request=" + ras.requestText.length() + " chars");

        JMenuItem menuItem = new JMenuItem("Send to POC Chat");
        menuItem.addActionListener(e -> SwingUtilities.invokeLater(() -> {
            LogManager.log("RepeaterContextMenuProvider: sending request to chat input box");
            if (ras.service != null) {
                MyExtension.lastRepeaterService = ras.service;
                LogManager.debug("RepeaterContextMenuProvider: captured original service "
                        + ras.service.host() + ":" + ras.service.port()
                        + (ras.service.secure() ? " (https)" : " (http)"));
            }
            if (MyExtension.chatInputBox != null) {
                MyExtension.chatInputBox.setText("Here is a request from the Repeater tab of Burp Suite from the user:\n```\n" + ras.requestText + "\n```\n");
            }
        }));

        items.add(menuItem);
        return items;
    }

    private static class RequestAndService {
        final String requestText;
        final HttpService service;

        RequestAndService(String requestText, HttpService service) {
            this.requestText = requestText;
            this.service = service;
        }
    }

    private RequestAndService getRequestAndService(ContextMenuEvent event) {
        Optional<?> msgOpt = event.messageEditorRequestResponse();
        if (msgOpt.isPresent()) {
            Object msg = msgOpt.get();
            if (msg instanceof burp.api.montoya.ui.contextmenu.MessageEditorHttpRequestResponse) {
                HttpRequestResponse reqRes = ((burp.api.montoya.ui.contextmenu.MessageEditorHttpRequestResponse) msg).requestResponse();
                if (reqRes != null && reqRes.request() != null) {
                    return new RequestAndService(reqRes.request().toString(), reqRes.request().httpService());
                }
            }
        }

        List<HttpRequestResponse> selections = event.selectedRequestResponses();
        if (!selections.isEmpty()) {
            HttpRequest req = selections.getFirst().request();
            if (req != null) {
                return new RequestAndService(req.toString(), req.httpService());
            }
        }

        return null;
    }
}