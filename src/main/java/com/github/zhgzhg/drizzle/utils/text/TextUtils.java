package com.github.zhgzhg.drizzle.utils.text;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.Collection;
import java.util.Collections;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class TextUtils {
    private TextUtils() {
        throw new UnsupportedOperationException("Not intended for instantiation");
    }

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

    public static boolean allNullOrBlank(String param, String... otherParams) {
        return !anyNotBlank(param, otherParams);
    }

    public static boolean isNotNullOrBlank(String param) {
        return !isNullOrBlank(param);
    }

    public static boolean allNotBlank(String param, String... otherParams) {
        if (isNullOrBlank(param) || otherParams == null || otherParams.length == 0) return false;

        return !anyNullOrBlank(param, otherParams);
    }

    public static boolean anyNotBlank(String param, String... otherParams) {
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

    public static String returnAnyNotBlank(String param, String... otherParams) {
        if (isNotNullOrBlank(param)) return param;

        if (otherParams != null && otherParams.length > 0) {
            for (String op : otherParams) {
                if (isNotNullOrBlank(op)) {
                    return op;
                }
            }
        }

        return null;
    }

    public static String[] labelAndUnquotedValue(String param) {
        if (isNullOrBlank(param)) return new String[0];

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
        return (res.length != 2 ? null : res[1]);
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

    public static URL toURL(String url, Consumer<MalformedURLException> onError) {
        try {
            return new URL(url);
        } catch (MalformedURLException ex) {
            if (onError != null) {
                onError.accept(ex);
            }
        }
        return null;
    }
}
