package com.example.fast2smsmockgateway.util;

import com.example.fast2smsmockgateway.validation.Fields;

public record SmsParts(int characters, int count, String language) {
    private static final String GSM_BASIC = "@£$¥èéùìòÇ\nØø\rÅåΔ_ΦΓΛΩΠΨΣΘΞÆæßÉ "
            + "!\"#¤%&'()*+,-./0123456789:;<=>?¡ABCDEFGHIJKLMNOPQRSTUVWXYZÄÖÑÜ§"
            + "¿abcdefghijklmnopqrstuvwxyzäöñüà";
    private static final String GSM_EXTENDED = "\f^{}\\[~]|€";

    public static String text(String route, Fields payload) {
        return switch (route) {
            case "dlt" -> payload.get("rendered_message");
            case "otp" -> payload.get("variables_values");
            default -> payload.get("message");
        };
    }

    public static SmsParts measure(String message) {
        int units = 0;
        boolean unicode = false;
        for (int point : message.codePoints().toArray()) {
            if (GSM_BASIC.indexOf(point) >= 0) {
                units++;
            } else if (GSM_EXTENDED.indexOf(point) >= 0) {
                units += 2;
            } else {
                unicode = true;
                units = message.length();
                break;
            }
        }
        int single = unicode ? 70 : 160;
        int count = units <= single ? 1 : Math.ceilDiv(units, unicode ? 67 : 153);
        return new SmsParts(message.codePointCount(0, message.length()), count, unicode ? "unicode" : "english");
    }
}
