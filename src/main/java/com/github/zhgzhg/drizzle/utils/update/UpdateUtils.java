package com.github.zhgzhg.drizzle.utils.update;

import cc.arduino.view.NotificationPopup;
import com.github.gundy.semver4j.model.Version;
import com.github.zhgzhg.drizzle.DrizzleCLI;
import com.github.zhgzhg.drizzle.utils.log.LogProxy;
import com.github.zhgzhg.drizzle.utils.text.TextUtils;
import com.google.gson.GsonBuilder;
import processing.app.Editor;
import processing.app.PreferencesData;

import javax.swing.event.HyperlinkEvent;
import javax.swing.event.HyperlinkListener;
import java.awt.*;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.util.Collections;
import java.util.List;
import java.util.Map;

public class UpdateUtils {
    private UpdateUtils() { }

    public static String version() {
        String implementationVersion = DrizzleCLI.class.getPackage().getImplementationVersion();
        if (TextUtils.isNullOrBlank(implementationVersion)) implementationVersion = "SNAPSHOT";
        return implementationVersion;
    }

    public static String webUrl() {
        return "https://github.com/zhgzhg/Drizzle";
    }

    public static String webLatestReleaseUrl() {
        return "https://github.com/zhgzhg/Drizzle/releases/latest";
    }

    public static Map<String, Object> webLatestStats() {
        try {
            URL url = new URL("https://api.github.com/repos/zhgzhg/Drizzle/releases/latest");

            HttpURLConnection connection = (HttpURLConnection) url.openConnection();

            connection.setRequestMethod("GET");
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(5000);
            connection.connect();

            StringBuilder content = new StringBuilder();

            try (BufferedReader in = new BufferedReader(new InputStreamReader(connection.getInputStream()))) {
                String inputLine;
                while ((inputLine = in.readLine()) != null) {
                    content.append(inputLine);
                }
            }

            connection.disconnect();

            return new GsonBuilder().create().fromJson(content.toString(), Map.class);
        } catch (Exception e) {
            e.printStackTrace(System.err);
        }

        return Collections.emptyMap();
    }

    public static String latestVersion() {
        Map<String, Object> projectStats = webLatestStats();
        return projectStats.getOrDefault("tag_name", version()).toString();
    }

    public static String latestVersionOfDistZIP() {
        Map<String, Object> projectStats = webLatestStats();
        List<Map<String, Object>> assets = (List<Map<String, Object>> ) projectStats.getOrDefault("assets", Collections.emptyList());

        for (Map<String, Object> asset : assets) {
            if ("application/zip".equalsIgnoreCase((String) asset.get("content_type"))) {
                String filename = asset.getOrDefault("name", "").toString();

                if (filename.endsWith("-dist.zip")) {
                    return (String) asset.get("browser_download_url");
                }
            }
        }

        return null;
    }

    public static boolean isTheLatestVersion(LogProxy logProxy) {
        try {
            Version latest = Version.fromString(latestVersion());
            Version current = Version.fromString(version());

            return current.compareTo(latest) >= 0;
        } catch (Exception e) {
            logProxy.cliErrorln(e);
            return true;
        }
    }

    public static NotificationPopup createNewVersionPopupNotification(
            Editor ed, LogProxy logProxy, String button1Title, Runnable button1Callback) {

        return createNewVersionPopupNotification(ed, logProxy, new NotificationPopup.OptionalButtonCallbacks() {
            @Override
            public void onOptionalButton1Callback() { button1Callback.run(); }

            @Override
            public void onOptionalButton2Callback() { }
        }, button1Title, null);
    }

    public static NotificationPopup createNewVersionPopupNotification(Editor ed, LogProxy logProxy,
            NotificationPopup.OptionalButtonCallbacks callbacks, String button1Title, String button2Title) {

        boolean isAccessible = PreferencesData.getBoolean("ide.accessible");
        String newVersionOfDrizzle = "A newer version of <a href=\"" + webLatestReleaseUrl() + "\"> the Drizzle tool is available</a>";

        HyperlinkListener hyperlinkListener = hyperlinkEvent -> {
            if (hyperlinkEvent.getEventType() != HyperlinkEvent.EventType.ACTIVATED) return;

            try {
                if (Desktop.isDesktopSupported()) {
                    Desktop desktop = Desktop.getDesktop();
                    if (desktop.isSupported(Desktop.Action.BROWSE)) {
                        desktop.browse(new URI(webLatestReleaseUrl()));
                    }
                } else {
                    String currOS = System.getProperty("os.name").toLowerCase();

                    Runtime runtime = Runtime.getRuntime();
                    if (currOS.contains("mac")) {
                        runtime.exec("open " + webLatestReleaseUrl());
                    } else if(currOS.contains("nix") || currOS.contains("nux")) {
                        runtime.exec("xdg-open " + webLatestReleaseUrl());
                    }
                }
            } catch (Exception e) {
                logProxy.cliErrorln(e);
            }
        };

        if (isAccessible) {
            return new NotificationPopup(ed, hyperlinkListener, newVersionOfDrizzle, false, callbacks, button1Title, button2Title);
        }

        return new NotificationPopup(ed, hyperlinkListener, newVersionOfDrizzle, true, callbacks, button1Title, button2Title);
    }
}
