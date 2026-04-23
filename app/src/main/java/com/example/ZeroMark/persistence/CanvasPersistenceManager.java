package com.example.zeromark.persistence;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.RectF;
import android.net.Uri;
import android.util.Base64;

import com.example.zeromark.brushes.BrushDescriptor;
import com.example.zeromark.canvas.model.CanvasModel;
import com.example.zeromark.canvas.model.Stroke;
import com.example.zeromark.model.CanvasSettings;
import com.example.zeromark.model.CanvasType;
import com.example.zeromark.model.GridType;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import android.graphics.pdf.PdfDocument;
import com.example.zeromark.canvas.StrokeRenderer;

/**
 * CanvasPersistenceManager handles serialization of the canvas to a compact yet
 * AI-readable JSON format (.cmark) and exports to PDF.
 */
public class CanvasPersistenceManager {

    private static final int CURRENT_VERSION = 1;

    public static void exportToPdf(Context context, Uri uri, CanvasModel model, CanvasSettings settings) throws IOException {
        PdfDocument document = new PdfDocument();
        
        List<Stroke> strokes = model.getStrokes();
        int width = 0;
        int height = 0;
        float offsetX = 0;
        float offsetY = 0;

        if (settings.getType() == CanvasType.FIXED) {
            width = settings.getWidth() != null ? settings.getWidth() : 1200;
            height = settings.getHeight() != null ? settings.getHeight() : 1800;
        } else if (settings.getType() == CanvasType.A4_VERTICAL) {
            width = 2480;
            height = 3508;
        } else if (settings.getType() == CanvasType.A4_HORIZONTAL) {
            width = 3508;
            height = 2480;
        } else {
            // Infinite: calculate bounding box of all strokes
            RectF bounds = new RectF();
            if (strokes.isEmpty()) {
                width = 100; height = 100;
            } else {
                for (int i = 0; i < strokes.size(); i++) {
                    if (i == 0) bounds.set(strokes.get(i).getBounds());
                    else bounds.union(strokes.get(i).getBounds());
                }
                // Add some padding
                bounds.inset(-50, -50);
                width = (int) Math.max(1, bounds.width());
                height = (int) Math.max(1, bounds.height());
                offsetX = -bounds.left;
                offsetY = -bounds.top;
            }
        }

        // Ensure minimum dimensions to avoid PdfDocument crashes
        width = Math.max(1, width);
        height = Math.max(1, height);

        PdfDocument.PageInfo pageInfo = new PdfDocument.PageInfo.Builder(width, height, 1).create();
        PdfDocument.Page page = document.startPage(pageInfo);
        Canvas canvas = page.getCanvas();

        // 1. Draw Background
        canvas.drawColor(android.graphics.Color.WHITE);

        // 2. Translate if we are exporting a sub-region (Infinite)
        if (offsetX != 0 || offsetY != 0) {
            canvas.translate(offsetX, offsetY);
        }

        // 3. Draw Strokes directly
        StrokeRenderer renderer = new StrokeRenderer();
        for (Stroke s : strokes) {
            renderer.commitStrokeForTile(canvas, s);
        }

        document.finishPage(page);

        try (OutputStream os = context.getContentResolver().openOutputStream(uri)) {
            document.writeTo(os);
            os.flush(); // Ensure everything is written
        } finally {
            document.close();
        }
    }

    public static void saveCanvas(Context context, Uri uri, CanvasModel model, CanvasSettings settings) throws IOException, JSONException {
        JSONObject root = new JSONObject();
        root.put("version", CURRENT_VERSION);

        // 1. Settings
        JSONObject jSettings = new JSONObject();
        jSettings.put("type", settings.getType().name());
        jSettings.put("grid", settings.getGridType().name());
        if (settings.getWidth() != null) jSettings.put("width", settings.getWidth());
        if (settings.getHeight() != null) jSettings.put("height", settings.getHeight());
        root.put("settings", jSettings);

        // 2. Palette (Unique Brushes)
        List<Stroke> strokes = model.getStrokes();
        List<BrushDescriptor> palette = new ArrayList<>();
        Map<String, Integer> brushKeyToIndex = new HashMap<>();

        for (Stroke s : strokes) {
            BrushDescriptor b = s.getBrush();
            String key = getBrushPaletteKey(b);
            if (!brushKeyToIndex.containsKey(key)) {
                brushKeyToIndex.put(key, palette.size());
                palette.add(b);
            }
        }

        JSONArray jPalette = new JSONArray();
        for (BrushDescriptor b : palette) {
            jPalette.put(serializeBrush(b));
        }
        root.put("palette", jPalette);

        // 3. Strokes
        JSONArray jStrokes = new JSONArray();
        for (Stroke s : strokes) {
            JSONObject jStroke = new JSONObject();
            jStroke.put("brushIndex", brushKeyToIndex.get(getBrushPaletteKey(s.getBrush())));
            jStroke.put("points", encodePoints(s.getPoints()));
            jStrokes.put(jStroke);
        }
        root.put("strokes", jStrokes);

        // Write to stream
        try (OutputStream os = context.getContentResolver().openOutputStream(uri);
             BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(os))) {
            writer.write(root.toString(2)); // Indented for AI readability
        }
    }

    private static String getBrushPaletteKey(BrushDescriptor b) {
        // Create a unique key based on properties that define a visual brush state
        return String.format("%s_%d_%d_%d_%d_%s",
                b.name, b.color, b.size, b.opacity, b.spacing, b.blendMode.name());
    }

    public static List<Stroke> loadCanvas(Context context, Uri uri, CanvasModel model, CanvasSettings[] outSettings) throws IOException, JSONException {
        StringBuilder sb = new StringBuilder();
        try (InputStream is = context.getContentResolver().openInputStream(uri);
             BufferedReader reader = new BufferedReader(new InputStreamReader(is))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
        }

        JSONObject root = new JSONObject(sb.toString());
        int version = root.getInt("version");

        // 1. Settings
        JSONObject jSettings = root.getJSONObject("settings");
        CanvasType type = CanvasType.valueOf(jSettings.getString("type"));
        GridType grid = GridType.valueOf(jSettings.getString("grid"));
        Integer width = jSettings.has("width") ? jSettings.getInt("width") : null;
        Integer height = jSettings.has("height") ? jSettings.getInt("height") : null;
        outSettings[0] = new CanvasSettings(type, grid, width, height);

        // 2. Palette
        JSONArray jPalette = root.getJSONArray("palette");
        List<BrushDescriptor> palette = new ArrayList<>();
        for (int i = 0; i < jPalette.length(); i++) {
            palette.add(deserializeBrush(jPalette.getJSONObject(i)));
        }

        // 3. Strokes
        JSONArray jStrokes = root.getJSONArray("strokes");
        List<Stroke> strokes = new ArrayList<>();
        for (int i = 0; i < jStrokes.length(); i++) {
            JSONObject jStroke = jStrokes.getJSONObject(i);
            int brushIndex = jStroke.getInt("brushIndex");
            float[] points = decodePoints(jStroke.getString("points"));
            BrushDescriptor brush = palette.get(brushIndex);
            
            // Re-calculate bounds for the loaded stroke
            RectF bounds = model.calculateStrokeBounds(points, brush);
            strokes.add(new Stroke(points, brush, bounds));
        }

        return strokes;
    }

    private static String encodePoints(float[] points) {
        ByteBuffer byteBuffer = ByteBuffer.allocate(points.length * 4);
        byteBuffer.order(ByteOrder.LITTLE_ENDIAN);
        for (float f : points) {
            byteBuffer.putFloat(f);
        }
        return Base64.encodeToString(byteBuffer.array(), Base64.NO_WRAP);
    }

    private static float[] decodePoints(String base64) {
        byte[] bytes = Base64.decode(base64, Base64.DEFAULT);
        FloatBuffer floatBuffer = ByteBuffer.wrap(bytes)
                .order(ByteOrder.LITTLE_ENDIAN)
                .asFloatBuffer();
        float[] points = new float[floatBuffer.remaining()];
        floatBuffer.get(points);
        return points;
    }

    private static JSONObject serializeBrush(BrushDescriptor b) throws JSONException {
        JSONObject j = new JSONObject();
        j.put("id", b.id);
        j.put("name", b.name);
        j.put("color", b.color);
        j.put("size", b.size);
        j.put("sizeMin", b.sizeMin);
        j.put("sizeMax", b.sizeMax);
        j.put("pSize", b.pressureControlsSize);
        j.put("opacity", b.opacity);
        j.put("smoothing", b.smoothing);
        j.put("spacing", b.spacing);
        j.put("blend", b.blendMode.name());
        // Curves are omitted for brevity in v1, defaults are used on load
        return j;
    }

    private static BrushDescriptor deserializeBrush(JSONObject j) throws JSONException {
        return new BrushDescriptor.Builder(j.getString("name"))
                .color(j.getInt("color"))
                .size(j.getInt("size"))
                .sizeRange(j.getInt("sizeMin"), j.getInt("sizeMax"))
                .pressureControlsSize(j.getBoolean("pSize"))
                .opacity(j.getInt("opacity"))
                .smoothing(j.getInt("smoothing"))
                .spacing(j.getInt("spacing"))
                .blendMode(BrushDescriptor.BlendMode.valueOf(j.getString("blend")))
                .build();
    }
}
