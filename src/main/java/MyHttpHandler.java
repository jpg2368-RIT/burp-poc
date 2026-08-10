import burp.api.montoya.http.handler.*;
import burp.api.montoya.http.message.requests.HttpRequest;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public class MyHttpHandler implements HttpHandler {
    private String hash = "";

    public MyHttpHandler(String hashArg) {
        this.hash = hashArg;
    }

    public String getHash() {
        return hash;
    }

    @Override
    public RequestToBeSentAction handleHttpRequestToBeSent(HttpRequestToBeSent requestToBeSent) {
        if (!this.hash.isEmpty() && requestToBeSent.isInScope()) {
            LogManager.debug("MyHttpHandler: adding X-Hash to in-scope request to " + requestToBeSent.url());
            HttpRequest request = requestToBeSent.withAddedHeader("X-Hash", this.hash);
            return RequestToBeSentAction.continueWith(request);
        }
        return null;
    }

    @Override
    public ResponseReceivedAction handleHttpResponseReceived(HttpResponseReceived responseReceived) {
        if (responseReceived.initiatingRequest().isInScope()) {
            String input = "";
            if (responseReceived.hasHeader("Age")) {
                input += responseReceived.headerValue("Age");
            }
            if (responseReceived.hasHeader("Date")) {
                input += responseReceived.headerValue("Date");
            }

            LogManager.debug("MyHttpHandler: in-scope response, hash input=\"" + input + "\"");
            try {
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                digest.update(input.getBytes(StandardCharsets.UTF_8));
                this.hash = HexFormat.of().formatHex(digest.digest());
                LogManager.log("Hash generated: " + this.hash);
            } catch (NoSuchAlgorithmException e) {
                LogManager.error("MyHttpHandler: SHA-256 unavailable: " + e.getMessage());
                throw new RuntimeException(e);
            }
        }
        return null;
    }
}
