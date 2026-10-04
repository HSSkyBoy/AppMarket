-dontwarn org.slf4j.helpers.SubstituteLogger

-keep class top.app.market.data.install.backend.root.RootBridgeMain {
    public static void main(java.lang.String[]);
}

-keep class top.app.market.install.InstallResultReceiver { *; }
-keep class top.app.market.install.InstallForegroundService { *; }
-keep class top.app.market.install.InstallNotificationActionReceiver { *; }
