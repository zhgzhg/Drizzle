package com.github.zhgzhg.drizzle.utils.text;

import java.util.function.Predicate;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;

public class TextUtils {
    private TextUtils() { }

    public static boolean isNullOrBlank(String param) {
        return param == null || param.trim().length() == 0;
    }

    public static boolean anyNullOrBlank(String param, String... otherParams) {
        if (isNullOrBlank(param)) return true;

        if (otherParams != null && otherParams.length > 0) {
            for (String op : otherParams) {
                if (isNullOrBlank(op)) {
                    return true;
                }
            }
        }

        return false;
    }

    public static boolean isNotNullOrBlank(String param) {
        return !isNullOrBlank(param);
    }

    public static boolean allNotNullOrBlank(String param, String... otherParams) {
        if (isNullOrBlank(param) || otherParams == null || otherParams.length == 0) return false;

        return !anyNullOrBlank(param, otherParams);
    }

    public static boolean anyNotNullOrBlank(String param, String... otherParams) {
        if (isNotNullOrBlank(param)) return true;

        if (otherParams != null && otherParams.length > 0) {
            for (String op : otherParams) {
                if (isNotNullOrBlank(op)) {
                    return true;
                }
            }
        }

        return false;
    }

    public static String[] labelAndUnquotedValue(String param) {
        if (isNullOrBlank(param)) return null;

        String[] split = param.split(": ", 2);
        if (split.length == 2) {
            String s = split[1];

            if (s.length() > 1 && s.charAt(0) == '"' && s.charAt(s.length() - 1) == '"') {
                split[1] = s.substring(1, s.length() - 1);
            }
        }

        return split;
    }

    public static String unquotedValueFromLabelPair(String param) {
        String[] res = labelAndUnquotedValue(param);
        return (res == null || res.length != 2 ? null : res[1]);
    }

    public static String concatenate(Predicate<String> filter, UnaryOperator<String> preparer, String... strings) {
        if (strings == null || strings.length == 0) return null;

        StringBuilder sb = new StringBuilder();

        for (String s : strings) {
            if (filter.test(s)) {
                sb.append(preparer.apply(s));
            }
        }

        return sb.toString();
    }

    public static String extractIf(String[] from, int index, Predicate<String> condition, String alternative) {
        if (from != null && from.length > index && condition.test(from[index])) {
            return from[index];
        }

        return alternative;
    }

    public static String ltrim(String str, String characters) {
        if (str == null || str.isEmpty() || characters == null || characters.isEmpty()) return str;
        return Pattern.compile("^" + Pattern.quote(characters)).matcher(str).replaceAll("");
    }

    public static String rtrim(String str, String characters) {
        if (str == null || str.isEmpty() || characters == null || characters.isEmpty()) return str;
        return Pattern.compile(Pattern.quote(characters) + "$").matcher(str).replaceAll("");
    }

    public static String trim(String str, String characters) {
        return rtrim(ltrim(str, characters), characters);
    }
}
