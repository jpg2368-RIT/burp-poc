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

/**
 * Adds the "Send to POC Chat" entry to the right-click menu in the Repeater tool.
 *
 * <p>The menu item copies the displayed request into the chat input box and
 * stores the request's {@link HttpService} in {@link MyExtension#lastRepeaterService}.
 * The stored service is later used by
 * {@link MyExtension#sendSelectionToRepeater} to set the target on requests
 * sent back to Repeater from the chat panel.</p>
 *
 * <p>Keep the stored service sticky across menu invocations: each "Send to POC
 * Chat" click overwrites the previous value, and the value stays until the next
 * click.</p>
 */
public class RepeaterContextMenuProvider implements ContextMenuItemsProvider {
    /**
     * Builds the context-menu items for a right-click event.
     *
     * <p>Shows "Send to POC Chat" only when the event comes from the Repeater
     * tool and a request text is available.</p>
     *
     * @param event the context-menu event from Burp
     * @return the menu items to show; empty when the menu should not appear
     */
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

    /**
     * Pairs a request's text with its original {@link HttpService}.
     *
     * @param requestText the request text to copy into the chat input box
     * @param service     the request's service; may be null
     */
    private static class RequestAndService {
        /** The request text to copy into the chat input box. */
        final String requestText;
        /** The request's service; may be null. */
        final HttpService service;

        /**
         * Creates a request/service pair.
         *
         * @param requestText the request text
         * @param service     the request's service; may be null
         */
        RequestAndService(String requestText, HttpService service) {
            this.requestText = requestText;
            this.service = service;
        }
    }

    /**
     * Extracts the request text and its service from a context-menu event.
     *
     * <p>Reads the request shown in the message editor first. When the event has
     * no message editor (for example a table selection), falls back to the first
     * selected request.</p>
     *
     * @param event the context-menu event from Burp
     * @return the request/service pair, or null when no request is available
     */
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