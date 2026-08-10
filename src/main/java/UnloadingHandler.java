import burp.api.montoya.extension.ExtensionUnloadingHandler;

public class UnloadingHandler implements ExtensionUnloadingHandler {
    private MyHttpHandler handler;

    public UnloadingHandler(MyHttpHandler handlerArg) {
        this.handler = handlerArg;
    }

    @Override
    public void extensionUnloaded() {
        if (MAPI.getAPI() != null) {
            LogManager.log("Extension unloading; saving hash=" + this.handler.getHash());
            MAPI.getAPI().persistence().preferences().setString("hash", this.handler.getHash());
        }
        LogManager.close();
    }
}
