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

    public static String latestVersion() {
        try {
            URL url = new URL("https://api.github.com/repos/zhgzhg/Drizzle/releases/latest");

            HttpURLConnection connection = (HttpURLConnection) url.openConnection();

            connection.setRequestMethod("GET");
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(5000);
            connection.connect();

            StringBuffer content = new StringBuffer();

            try (BufferedReader in = new BufferedReader(new InputStreamReader(connection.getInputStream()))) {
                String inputLine;
                while ((inputLine = in.readLine()) != null) {
                    content.append(inputLine);
                }
            }

            connection.disconnect();

            Map<String, Object> results = new GsonBuilder().create().fromJson(content.toString(), Map.class);

            return results.getOrDefault("tag_name", version()).toString();
        } catch (Exception e) {
            return version();
        }
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

    public static NotificationPopup createNewVersionPopupNotification(Editor ed, LogProxy logProxy) {
        boolean isAccessible = PreferencesData.getBoolean("ide.accessible");
        String newVersionOfDrizzle = "A new version of <a href=\"" + webLatestReleaseUrl() + "\"> the Drizzle Tool is available</a>";

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
            return new NotificationPopup(ed, hyperlinkListener, newVersionOfDrizzle, false);
        }

        return new NotificationPopup(ed, hyperlinkListener, newVersionOfDrizzle);
    }
}
