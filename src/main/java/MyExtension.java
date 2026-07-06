import burp.api.montoya.BurpExtension;
import burp.api.montoya.MontoyaApi;

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
    }
}
