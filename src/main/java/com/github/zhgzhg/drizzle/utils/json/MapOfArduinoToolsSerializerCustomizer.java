package com.github.zhgzhg.drizzle.utils.json;

import com.github.zhgzhg.drizzle.utils.source.SourceExtractor;
import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonSerializationContext;
import com.google.gson.JsonSerializer;

import java.lang.reflect.Type;
import java.util.LinkedHashMap;
import java.util.Map;

public class MapOfArduinoToolsSerializerCustomizer implements JsonSerializer<Map<String, SourceExtractor.ArduinoTool>>,
        JsonDeserializer<Map<String, SourceExtractor.ArduinoTool>> {

    @Override
    public JsonElement serialize(final Map<String, SourceExtractor.ArduinoTool> src, final Type typeOfSrc,
            final JsonSerializationContext context) {

        JsonObject jsonObject = new JsonObject();

        if (src != null && !src.isEmpty()) {
            for (SourceExtractor.ArduinoTool at : src.values()) {
                JsonObject atObj = context.serialize(at, SourceExtractor.ArduinoTool.class).getAsJsonObject();
                atObj.remove("name");
                jsonObject.add(at.name, atObj);
            }
        }

        return jsonObject;
    }

    @Override
    public Map<String, SourceExtractor.ArduinoTool> deserialize(final JsonElement json, final Type typeOfT,
            final JsonDeserializationContext context) {
        LinkedHashMap<String, SourceExtractor.ArduinoTool> result = new LinkedHashMap<>();

        JsonObject origObj = json.getAsJsonObject();

        for (Map.Entry<String, JsonElement> entry : origObj.entrySet()) {
            JsonObject contents = entry.getValue().getAsJsonObject();

            result.put(entry.getKey(), new SourceExtractor.ArduinoTool(
                    entry.getKey(),
                    contents.get("version").getAsString(),
                    contents.get("url").getAsString()
                )
            );
        }

        return result;
    }
}
