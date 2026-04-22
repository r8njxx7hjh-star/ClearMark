package com.example.zeromark.canvas.model;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.Rect;
import android.graphics.RectF;
import android.util.LruCache;

import com.example.zeromark.brushes.BrushDescriptor;
import com.example.zeromark.canvas.StrokeRenderer;
import com.example.zeromark.model.CanvasSettings;
import com.example.zeromark.model.CanvasType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class CanvasModel {
    private static final int TILE_SIZE = 512;
    private final List<Stroke> strokes = java.util.Collections.synchronizedList(new ArrayList<>());
    
    // Spatial index: Grid of stroke lists, using packed long key (x << 32 | y)
    private final java.util.concurrent.ConcurrentHashMap<Long, List<Stroke>> spatialIndex = 
            new java.util.concurrent.ConcurrentHashMap<>();
    
    // Per-tile versioning to track updates and fix race conditions
    private final java.util.concurrent.ConcurrentHashMap<Long, java.util.concurrent.atomic.AtomicInteger> tileVersions = 
            new java.util.concurrent.ConcurrentHashMap<>();

    private final CanvasSettings settings;
    private final LruCache<Long, CachedTile> tileCache;
    private final StrokeRenderer renderer = new StrokeRenderer();
    private final Paint tilePaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint placeholderPaint = new Paint();

    private final android.os.Handler mainHandler = new android.os.Handler(android.os.Looper.getMainLooper());

    private static class CachedTile {
        final Bitmap bitmap;
        final int version;
        final int level;

        CachedTile(Bitmap bitmap, int version, int level) {
            this.bitmap = bitmap;
            this.version = version;
            this.level = level;
        }
    }

    // Bitmap Pool to reuse tile bitmaps and reduce allocation churn
    // Limit pool sizes to avoid holding too much memory
    private static final int MAX_POOL_SIZE_1X = 32;
    private static final int MAX_POOL_SIZE_2X = 16;
    private final java.util.concurrent.ConcurrentLinkedQueue<Bitmap> bitmapPool1x = new java.util.concurrent.ConcurrentLinkedQueue<>();
    private final java.util.concurrent.ConcurrentLinkedQueue<Bitmap> bitmapPool2x = new java.util.concurrent.ConcurrentLinkedQueue<>();
    
    // Strokes that are added but not yet fully baked into all their tiles
    private final List<Stroke> pendingStrokes = java.util.Collections.synchronizedList(new ArrayList<>());

    public interface OnTileUpdatedListener {
        void onTileUpdated();
    }
    private OnTileUpdatedListener onTileUpdatedListener;
    public void setOnTileUpdatedListener(OnTileUpdatedListener l) { this.onTileUpdatedListener = l; }

    // Background worker for async tile rendering
    private final java.util.concurrent.ExecutorService renderExecutor = 
            java.util.concurrent.Executors.newFixedThreadPool(Math.max(1, Runtime.getRuntime().availableProcessors() / 2));
    private final java.util.Set<Long> pendingRenders = java.util.Collections.synchronizedSet(new java.util.HashSet<>());

    public CanvasModel(CanvasSettings settings) {
        this.settings = settings;
        this.placeholderPaint.setColor(0xFFF5F5F5); // Very light gray
        
        // 256MB is a safer sweet spot for large-heap devices to avoid OOM in long sessions
        int cacheSizeK = 256 * 1024; 
        
        this.tileCache = new LruCache<Long, CachedTile>(cacheSizeK) {
            @Override
            protected int sizeOf(Long key, CachedTile value) {
                return value.bitmap.getByteCount() / 1024;
            }
            
            @Override
            protected void entryRemoved(boolean evicted, Long key, CachedTile oldValue, CachedTile newValue) {
                if (oldValue != null) {
                    // Return to pool instead of immediate recycling to reduce allocation churn
                    if (oldValue.level == 1 && bitmapPool1x.size() < MAX_POOL_SIZE_1X) bitmapPool1x.offer(oldValue.bitmap);
                    else if (oldValue.level == 2 && bitmapPool2x.size() < MAX_POOL_SIZE_2X) bitmapPool2x.offer(oldValue.bitmap);
                    else oldValue.bitmap.recycle();
                }
            }
        };
    }

    public void addStroke(Stroke stroke) {
        strokes.add(stroke);
        pendingStrokes.add(stroke);
        
        // Add to spatial index
        RectF bounds = stroke.getBounds();

        int left = (int) Math.floor(bounds.left / TILE_SIZE);
        int top = (int) Math.floor(bounds.top / TILE_SIZE);
        int right = (int) Math.floor(bounds.right / TILE_SIZE);
        int bottom = (int) Math.floor(bounds.bottom / TILE_SIZE);

        for (int x = left; x <= right; x++) {
            for (int y = top; y <= bottom; y++) {
                long packedKey = getTilePackedKey(x, y);
                
                // Increment version to mark tile as needing update
                java.util.concurrent.atomic.AtomicInteger v = tileVersions.get(packedKey);
                if (v == null) {
                    v = new java.util.concurrent.atomic.AtomicInteger(0);
                    java.util.concurrent.atomic.AtomicInteger existing = tileVersions.putIfAbsent(packedKey, v);
                    if (existing != null) v = existing;
                }
                v.incrementAndGet();
                
                List<Stroke> list = spatialIndex.get(packedKey);
                if (list == null) {
                    list = java.util.Collections.synchronizedList(new ArrayList<>());
                    List<Stroke> existing = spatialIndex.putIfAbsent(packedKey, list);
                    if (existing != null) list = existing;
                }
                list.add(stroke);
            }
        }
    }

    public RectF calculateStrokeBounds(float[] points, BrushDescriptor brush) {
        RectF bounds = new RectF();
        if (points.length == 0) return bounds;

        float maxScale = Math.max(1.0f, brush.sizeMax / 100f);
        float maxR = (brush.size * maxScale) / 2f;

        bounds.set(points[0] - maxR, points[1] - maxR, points[0] + maxR, points[1] + maxR);

        for (int i = 0; i < points.length; i += 6) {
            bounds.union(points[i] - maxR, points[i+1] - maxR, points[i] + maxR, points[i+1] + maxR);
            bounds.union(points[i+3] - maxR, points[i+4] - maxR, points[i+3] + maxR, points[i+4] + maxR);
        }
        return bounds;
    }

    private long getTilePackedKey(int x, int y) {
        return ((long) x << 32) | (y & 0xFFFFFFFFL);
    }

    private long getTileKey(int x, int y, int level) {
        // Packed key: [level: 8bit] [x: 24bit] [y: 32bit]
        return ((long) level << 56) | (((long) x & 0xFFFFFFL) << 32) | (y & 0xFFFFFFFFL);
    }

    public void draw(Canvas canvas, Matrix viewMatrix, int width, int height) {
        draw(canvas, viewMatrix, width, height, true);
    }

    public void draw(Canvas canvas, Matrix viewMatrix, int width, int height, boolean drawGrid) {
        Matrix inverse = new Matrix();
        viewMatrix.invert(inverse);
        RectF visibleRect = new RectF(0, 0, width, height);
        inverse.mapRect(visibleRect);

        float[] matrixValues = new float[9];
        viewMatrix.getValues(matrixValues);
        float scale = matrixValues[Matrix.MSCALE_X];

        int left = (int) Math.floor(visibleRect.left / TILE_SIZE);
        int top = (int) Math.floor(visibleRect.top / TILE_SIZE);
        int right = (int) Math.floor(visibleRect.right / TILE_SIZE);
        int bottom = (int) Math.floor(visibleRect.bottom / TILE_SIZE);

        if (settings.getType() == CanvasType.FIXED) {
            left = Math.max(0, left);
            top = Math.max(0, top);
            right = Math.min((settings.getWidth() - 1) / TILE_SIZE, right);
            bottom = Math.min((settings.getHeight() - 1) / TILE_SIZE, bottom);
        } else if (settings.getType() == CanvasType.A4_VERTICAL) {
             left = Math.max(0, left);
             right = Math.min((2480 - 1) / TILE_SIZE, right);
             top = Math.max(0, top);
        } else if (settings.getType() == CanvasType.A4_HORIZONTAL) {
             top = Math.max(0, top);
             bottom = Math.min((2480 - 1) / TILE_SIZE, bottom);
             left = Math.max(0, left);
        }

        canvas.save();
        canvas.concat(viewMatrix);
        
        // --- DRAW GRID FIRST ---
        // Drawing grid before tiles ensures it stays behind strokes
        if (drawGrid) {
            drawGrid(canvas, visibleRect, scale);
        }
        
        // --- DRAW TILES ---
        long tileCount = (long) (right - left + 1) * (bottom - top + 1);

        if (tileCount > 400 && tileCount > strokes.size()) {
            boolean simplified = scale < 0.35f;
            for (Stroke stroke : strokes) {
                RectF b = stroke.getBounds();
                if (b != null && RectF.intersects(b, visibleRect)) {
                    if (simplified) {
                        renderer.commitStrokeForTileSimplified(canvas, stroke);
                    } else {
                        renderer.commitStrokeForTile(canvas, stroke);
                    }
                }
            }
        } else {
            // High-DPI tiling: choose resolution based on scale. Limit to 2x for memory efficiency.
            int targetLevel = 1;
            if (scale > 1.1f) targetLevel = 2;

            for (int x = left; x <= right; x++) {
                for (int y = top; y <= bottom; y++) {
                    long packedKey = getTilePackedKey(x, y);
                    java.util.concurrent.atomic.AtomicInteger vObj = tileVersions.get(packedKey);
                    int currentVersion = (vObj != null) ? vObj.get() : 0;
                    
                    List<Stroke> tileStrokes = spatialIndex.get(packedKey);
                    boolean hasStrokes = tileStrokes != null && !tileStrokes.isEmpty();
                    
                    CachedTile tile = hasStrokes ? getTileAsync(x, y, targetLevel, currentVersion) : null;
                    RectF dst = new RectF(x * TILE_SIZE, y * TILE_SIZE, (x + 1) * TILE_SIZE, (y + 1) * TILE_SIZE);
                    
                    if (tile != null) {
                        Rect src = new Rect(0, 0, tile.bitmap.getWidth(), tile.bitmap.getHeight());
                        canvas.drawBitmap(tile.bitmap, src, dst, tilePaint);
                        
                        // SEAMLESS HANDOFF: If the tile is stale (rendering in background), 
                        // patch it with pending strokes so it never "flickers".
                        if (tile.version < currentVersion) {
                            drawPendingStrokes(canvas, dst);
                        }
                    } else if (hasStrokes) {
                        // Only draw pending strokes if no tile at all is available
                        drawPendingStrokes(canvas, dst);
                    }
                }
            }
        }

        canvas.restore();
    }

    private void drawPendingStrokes(Canvas canvas, RectF dst) {
        canvas.save();
        canvas.clipRect(dst);
        
        // Snapshot to avoid long lock on UI thread
        Stroke[] snapshot;
        synchronized(pendingStrokes) {
            if (pendingStrokes.isEmpty()) {
                canvas.restore();
                return;
            }
            // Limit to most recent 20 strokes for safety. In practice it's usually 1-5.
            snapshot = pendingStrokes.toArray(new Stroke[0]);
        }

        for (Stroke s : snapshot) {
            if (RectF.intersects(s.getBounds(), dst)) {
                renderer.commitStrokeForTile(canvas, s);
            }
        }
        canvas.restore();
    }

    private CachedTile getTileAsync(int x, int y, int targetLevel, int currentVersion) {
        long targetKey = getTileKey(x, y, targetLevel);
        CachedTile targetTile = tileCache.get(targetKey);
        
        if (targetTile != null && targetTile.version >= currentVersion) return targetTile;

        // If it exists but is dirty, or doesn't exist at all:
        queueRender(x, y, targetLevel, currentVersion);

        if (targetTile != null) return targetTile; // Return stale for now

        // Fallback strategy: if target is missing, try lower resolution levels
        if (targetLevel > 1) {
            for (int fallbackLevel = targetLevel / 2; fallbackLevel >= 1; fallbackLevel /= 2) {
                long fallbackKey = getTileKey(x, y, fallbackLevel);
                CachedTile fallback = tileCache.get(fallbackKey);
                if (fallback != null) return fallback;
                
                // If fallback is also missing, queue it too (1x is fast and fixes holes quickly)
                queueRender(x, y, fallbackLevel, currentVersion);
            }
        }

        return null; // Let the caller draw a placeholder
    }

    private void queueRender(final int x, final int y, final int level, final int version) {
        final long key = getTileKey(x, y, level);
        if (pendingRenders.contains(key)) return;

        pendingRenders.add(key);
        renderExecutor.execute(() -> {
            try {
                Bitmap bitmap = renderTile(x, y, level);
                if (bitmap != null) {
                    CachedTile tile = new CachedTile(bitmap, version, level);
                    tileCache.put(key, tile);
                    
                    cleanupPendingStrokes();

                    if (onTileUpdatedListener != null) {
                        mainHandler.post(() -> onTileUpdatedListener.onTileUpdated());
                    }
                }
            } finally {
                pendingRenders.remove(key);
            }
        });
    }

    private void cleanupPendingStrokes() {
        // Snapshot of strokes to check
        Stroke[] toCheck;
        synchronized(pendingStrokes) {
            if (pendingStrokes.isEmpty()) return;
            toCheck = pendingStrokes.toArray(new Stroke[0]);
        }

        List<Stroke> toRemove = new ArrayList<>();
        for (Stroke s : toCheck) {
            RectF b = s.getBounds();
            int l = (int) Math.floor(b.left / TILE_SIZE);
            int t = (int) Math.floor(b.top / TILE_SIZE);
            int r = (int) Math.floor(b.right / TILE_SIZE);
            int bot = (int) Math.floor(b.bottom / TILE_SIZE);
            
            boolean stillDirty = false;
            outer: for (int x = l; x <= r; x++) {
                for (int y = t; y <= bot; y++) {
                    long packedKey = getTilePackedKey(x, y);
                    java.util.concurrent.atomic.AtomicInteger vObj = tileVersions.get(packedKey);
                    int currentVersion = (vObj != null) ? vObj.get() : 0;
                    
                    boolean tileUpToDate = false;
                    for (int level : new int[]{1, 2}) {
                        CachedTile ct = tileCache.get(getTileKey(x, y, level));
                        if (ct != null && ct.version >= currentVersion) {
                            tileUpToDate = true;
                            break;
                        }
                    }
                    
                    if (!tileUpToDate) {
                        stillDirty = true;
                        break outer;
                    }
                }
            }
            if (!stillDirty) toRemove.add(s);
        }

        if (!toRemove.isEmpty()) {
            synchronized(pendingStrokes) {
                pendingStrokes.removeAll(toRemove);
            }
        }
    }

    private Bitmap acquireBitmap(int res, int level) {
        Bitmap b = null;
        if (level == 1) b = bitmapPool1x.poll();
        else if (level == 2) b = bitmapPool2x.poll();

        if (b != null) {
            b.eraseColor(Color.TRANSPARENT);
            return b;
        }

        try {
            return Bitmap.createBitmap(res, res, Bitmap.Config.ARGB_8888);
        } catch (OutOfMemoryError e) {
            tileCache.evictAll();
            System.gc();
            try {
                return Bitmap.createBitmap(res, res, Bitmap.Config.ARGB_8888);
            } catch (OutOfMemoryError e2) {
                return null;
            }
        }
    }

    private Bitmap renderTile(int x, int y, int level) {
        long packedKey = getTilePackedKey(x, y);
        List<Stroke> tileStrokes = spatialIndex.get(packedKey);
        if (tileStrokes == null) return null;

        // Snapshot the list to iterate safely
        Object[] strokesArray;
        synchronized(tileStrokes) {
            if (tileStrokes.isEmpty()) return null;
            strokesArray = tileStrokes.toArray();
        }

        int res = TILE_SIZE * level;
        Bitmap bitmap = acquireBitmap(res, level);
        if (bitmap == null) return null;

        Canvas canvas = new Canvas(bitmap);
        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR);
        canvas.scale(level, level);
        canvas.translate(-x * TILE_SIZE, -y * TILE_SIZE);
        
        for (Object s : strokesArray) {
            renderer.commitStrokeForTile(canvas, (Stroke) s);
        }
        return bitmap;
    }


    public void drawGrid(Canvas canvas, RectF visibleRect, float scale) {
        if (settings.getGridType() == com.example.zeromark.model.GridType.BLANK) return;
        
        // Skip grid if too dense (less than 8 pixels on screen)
        float step = 50f;
        if (step * scale < 8f) return;

        Paint p = new Paint();
        p.setColor(Color.LTGRAY);
        p.setStrokeWidth(1f / scale); // Keep lines thin on screen

        if (settings.getGridType() == com.example.zeromark.model.GridType.SQUARED) {
            float startX = (float) Math.floor(visibleRect.left / step) * step;
            float startY = (float) Math.floor(visibleRect.top / step) * step;
            for (float x = startX; x <= visibleRect.right; x += step) {
                canvas.drawLine(x, visibleRect.top, x, visibleRect.bottom, p);
            }
            for (float y = startY; y <= visibleRect.bottom; y += step) {
                canvas.drawLine(visibleRect.left, y, visibleRect.right, y, p);
            }
        } else if (settings.getGridType() == com.example.zeromark.model.GridType.LINED) {
            float startY = (float) Math.floor(visibleRect.top / step) * step;
            for (float y = startY; y <= visibleRect.bottom; y += step) {
                canvas.drawLine(visibleRect.left, y, visibleRect.right, y, p);
            }
        }
        
        if (settings.getType() == CanvasType.A4_VERTICAL) {
            p.setColor(Color.GRAY);
            p.setStrokeWidth(2f / scale);
            canvas.drawLine(0, visibleRect.top, 0, visibleRect.bottom, p);
            canvas.drawLine(2480, visibleRect.top, 2480, visibleRect.bottom, p);
            float pageHeight = 3508f;
            float startY = (float) Math.floor(visibleRect.top / pageHeight) * pageHeight;
            for (float y = startY; y <= visibleRect.bottom; y += pageHeight) {
                canvas.drawLine(0, y, 2480, y, p);
            }
        }
    }
    
    public void clear() {
        strokes.clear();
        spatialIndex.clear();
        tileCache.evictAll();
    }

    public void release() {
        clear();
        renderExecutor.shutdownNow();
        bitmapPool1x.clear();
        bitmapPool2x.clear();
    }
}
