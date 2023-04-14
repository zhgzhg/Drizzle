package com.github.zhgzhg.drizzle.utils.json;

import com.github.zhgzhg.drizzle.utils.source.SourceExtractor;
import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.google.gson.JsonSerializationContext;
import com.google.gson.JsonSerializer;

import java.lang.reflect.Type;
import java.util.List;
import java.util.Map;

public class PreferencesSerializerCustomizer implements JsonSerializer<SourceExtractor.Preferences>,
        JsonDeserializer<SourceExtractor.Preferences> {

    public static final String BOARD_INFO_HOLDER = "board";
    public static final String PREFERENCES_HOLDER = "preferences";

    public static final String NAME = "name";
    public static final String PLATFORM = "platform";
    public static final String PROVIDER_PACKAGE = "providerPackage";

    @Override
    public JsonElement serialize(final SourceExtractor.Preferences src, final Type typeOfSrc,
            final JsonSerializationContext context) {

        JsonObject board = context.serialize(src.board, SourceExtractor.Board.class).getAsJsonObject();


        JsonElement providerPackage = board.get(PROVIDER_PACKAGE);
        if (providerPackage == null || providerPackage instanceof JsonNull || providerPackage.getAsString().isEmpty()) {
            board.add(PROVIDER_PACKAGE, new JsonPrimitive("*"));
        }
        JsonElement platform = board.get(PLATFORM);
        if (platform == null || platform instanceof JsonNull || platform.getAsString().isEmpty()) {
            board.add(PLATFORM, new JsonPrimitive("*"));
        }
        JsonElement boardName = board.get(NAME);
        if (boardName == null || boardName instanceof JsonNull || boardName.getAsString().isEmpty()) {
            board.add(NAME, new JsonPrimitive("*"));
        }

        JsonElement preferences = context.serialize(src.preferences, Map.class);

        JsonObject result = new JsonObject();
        result.add(BOARD_INFO_HOLDER, board);
        result.add(PREFERENCES_HOLDER, preferences);

        return result;
    }

    @Override
    public SourceExtractor.Preferences deserialize(final JsonElement json, final Type typeOfT,
            final JsonDeserializationContext context) {

        SourceExtractor.Board board = context.deserialize(
                json.getAsJsonObject().getAsJsonObject(BOARD_INFO_HOLDER), SourceExtractor.Board.class);

        Map<String, String> preferencesMap =
                context.deserialize(json.getAsJsonObject().getAsJsonObject(PREFERENCES_HOLDER), Map.class);

        SourceExtractor.Preferences prefs = new SourceExtractor.Preferences(board);
        prefs.preferences.putAll(preferencesMap);
        return prefs;
    }
}
