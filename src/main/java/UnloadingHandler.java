import burp.api.montoya.extension.ExtensionUnloadingHandler;

public class UnloadingHandler implements ExtensionUnloadingHandler {
    private MyHttpHandler handler;

    public UnloadingHandler(MyHttpHandler handlerArg) {
        this.handler = handlerArg;
    }

    @Override
    public void extensionUnloaded() {
        MAPI.getAPI().persistence().preferences().setString("hash", this.handler.getHash());
    }
}
