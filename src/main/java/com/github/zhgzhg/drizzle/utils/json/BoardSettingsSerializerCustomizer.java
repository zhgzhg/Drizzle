package com.github.zhgzhg.drizzle.utils.json;

import com.github.zhgzhg.drizzle.utils.source.SourceExtractor;
import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.google.gson.JsonSerializationContext;
import com.google.gson.JsonSerializer;

import java.lang.reflect.Type;
import java.util.List;

public class BoardSettingsSerializerCustomizer implements JsonSerializer<SourceExtractor.BoardSettings>,
        JsonDeserializer<SourceExtractor.BoardSettings> {

    public static final String BOARD_INFO_HOLDER = "board";
    public static final String CLICKABLE_OPTIONS_HOLDER = "clickable_options";

    public static final String NAME = "name";
    public static final String PLATFORM = "platform";
    public static final String PROVIDER_PACKAGE = "providerPackage";

    @Override
    public JsonElement serialize(final SourceExtractor.BoardSettings src, final Type typeOfSrc,
            final JsonSerializationContext context) {

        JsonObject board = context.serialize(src.board, SourceExtractor.Board.class).getAsJsonObject();

        JsonPrimitive platform = board.getAsJsonPrimitive(PLATFORM);
        if (platform == null || platform.getAsString().isEmpty()) {
            board.add(PLATFORM, new JsonPrimitive("*"));
        }
        JsonPrimitive boardName = board.getAsJsonPrimitive(NAME);
        if (boardName == null || boardName.getAsString().isEmpty()) {
            board.add(NAME, new JsonPrimitive("*"));
        }
        board.remove(PROVIDER_PACKAGE);

        JsonElement clickableOptions = context.serialize(src.clickableOptions, List.class);

        JsonObject result = new JsonObject();
        result.add(BOARD_INFO_HOLDER, board);
        result.add(CLICKABLE_OPTIONS_HOLDER, clickableOptions);

        return result;
    }

    @Override
    public SourceExtractor.BoardSettings deserialize(final JsonElement json, final Type typeOfT,
            final JsonDeserializationContext context) {

        SourceExtractor.Board board = context.deserialize(
                json.getAsJsonObject().getAsJsonObject(BOARD_INFO_HOLDER), SourceExtractor.Board.class);

        List<List<String>> clickableOptions =
                context.deserialize(json.getAsJsonObject().getAsJsonArray(CLICKABLE_OPTIONS_HOLDER), List.class);

        SourceExtractor.BoardSettings boardSettings = new SourceExtractor.BoardSettings(board);
        boardSettings.clickableOptions.addAll(clickableOptions);
        return boardSettings;
    }
}
