import burp.api.montoya.http.handler.*;
import burp.api.montoya.http.message.requests.HttpRequest;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public class MyHttpHandler implements HttpHandler {
    private String hash = "";

    public MyHttpHandler() {

    }

    @Override
    public RequestToBeSentAction handleHttpRequestToBeSent(HttpRequestToBeSent requestToBeSent) {
        if (!this.hash.isEmpty()) {
            HttpRequest request = requestToBeSent.withAddedHeader("X-Hash", this.hash);
            return RequestToBeSentAction.continueWith(request);
        }
        return null;
    }

    @Override
    public ResponseReceivedAction handleHttpResponseReceived(HttpResponseReceived responseReceived) {
        String input = "";
        if (responseReceived.hasHeader("Age")){
            input += responseReceived.headerValue("Age");
        }
        if (responseReceived.hasHeader("Date")){
            input += responseReceived.headerValue("Date");
        }

        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(input.getBytes(StandardCharsets.UTF_8));
            this.hash = HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
        return null;
    }
}
