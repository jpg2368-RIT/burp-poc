import burp.api.montoya.BurpExtension;
import burp.api.montoya.MontoyaApi;

public class Extension implements BurpExtension {
    @Override
    public void initialize(MontoyaApi api) {
        api.extension().setName("Burp Suite POC Extension");
        api.logging().logToOutput("Extension successfully loaded :)");
    }
}
