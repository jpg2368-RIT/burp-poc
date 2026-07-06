import burp.api.montoya.MontoyaApi;
import burp.api.montoya.extension.ExtensionUnloadingHandler;

public class UnloadingHandler implements ExtensionUnloadingHandler {
    private MontoyaApi api;
    private MyHttpHandler handler;

    public UnloadingHandler(MontoyaApi apiArg, MyHttpHandler handlerArg) {
        this.api = apiArg;
        this.handler = handlerArg;
    }

    @Override
    public void extensionUnloaded() {
        this.api.persistence().preferences().setString("hash", this.handler.getHash());
    }
}
