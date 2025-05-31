package com.github.skrcode.javaautounittests;

import com.intellij.notification.*;
import com.intellij.openapi.project.Project;

public final class NotificationHelper {
    private static final NotificationGroup GROUP =
            NotificationGroupManager.getInstance().getNotificationGroup("JAIPilot Notifications");

    public static void info(Project p, String m)  { GROUP.createNotification(m, NotificationType.INFORMATION).notify(p); }
    public static void error(Project p, String m) { GROUP.createNotification(m, NotificationType.ERROR).notify(p); }
}