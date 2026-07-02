import burp.api.montoya.BurpExtension;
import burp.api.montoya.MontoyaApi;
import burp.api.montoya.http.handler.*;
import burp.api.montoya.http.message.requests.HttpRequest;

public class Extension implements BurpExtension, HttpHandler {
    @Override
    public void initialize(MontoyaApi api) {
        api.extension().setName("Burp Suite POC Extension");
        api.logging().logToOutput("Extension successfully loaded :)");
        api.http().registerHttpHandler(this);
    }

    @Override
    public RequestToBeSentAction handleHttpRequestToBeSent(HttpRequestToBeSent requestToBeSent) {
        HttpRequest request = requestToBeSent.withAddedHeader("New-Header", "MyHeaderValue");
        return RequestToBeSentAction.continueWith(request);
    }

    @Override
    public ResponseReceivedAction handleHttpResponseReceived(HttpResponseReceived responseReceived) {
        return null;
    }
}
