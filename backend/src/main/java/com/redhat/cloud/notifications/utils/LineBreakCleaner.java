package com.redhat.cloud.notifications.utils;

public class LineBreakCleaner {

    public static String clean(String value) {
        return value.replace("\r", "").replace("\n", "");
    }
}