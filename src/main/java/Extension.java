import burp.api.montoya.BurpExtension;
import burp.api.montoya.MontoyaApi;
import burp.api.montoya.http.handler.*;

public class Extension implements BurpExtension {
    @Override
    public void initialize(MontoyaApi api) {
        api.extension().setName("Burp Suite POC Extension");
        api.logging().logToOutput("Extension successfully loaded :)");
        MyHttpHandler handler = new MyHttpHandler();
        api.http().registerHttpHandler(handler);
    }
}
