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

public class MapOfDependentLibrariesSerializerCustomizer implements JsonSerializer<Map<String, SourceExtractor.DependentLibrary>>,
        JsonDeserializer<Map<String, SourceExtractor.DependentLibrary>> {

    @Override
    public JsonElement serialize(final Map<String, SourceExtractor.DependentLibrary> src, final Type typeOfSrc,
            final JsonSerializationContext context) {

        JsonObject jsonObject = new JsonObject();

        if (src != null && !src.isEmpty()) {
            for (SourceExtractor.DependentLibrary at : src.values()) {
                JsonObject atObj = context.serialize(at, SourceExtractor.DependentLibrary.class).getAsJsonObject();
                atObj.remove("name");
                jsonObject.add(at.name, atObj);
            }
        }

        return jsonObject;
    }

    @Override
    public Map<String, SourceExtractor.DependentLibrary> deserialize(final JsonElement json, final Type typeOfT,
            final JsonDeserializationContext context) {

        LinkedHashMap<String, SourceExtractor.DependentLibrary> result = new LinkedHashMap<>();

        JsonObject origObj = json.getAsJsonObject();

        for (Map.Entry<String, JsonElement> entry : origObj.entrySet()) {
            JsonObject contents = entry.getValue().getAsJsonObject();

            result.put(entry.getKey(), new SourceExtractor.DependentLibrary(
                        entry.getKey(),
                        contents.get("version").getAsString()
                    )
            );
        }

        return result;
    }
}
