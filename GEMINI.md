# ClearMark (ZeroMark) Project Context

ClearMark (internally referred to as ZeroMark in package names) is a high-performance Android drawing and note-taking application. It is specifically optimized for low-latency stylus input, targeting Android 15 (SDK 35).

## Project Overview

*   **Main Technologies:**
    *   **Language:** Hybrid Kotlin and Java. Java is used for performance-critical drawing logic.
    *   **UI Framework:** Jetpack Compose for the main application shell and toolbars.
    *   **Drawing Engine:** Custom low-latency engine using `CanvasFrontBufferedRenderer` from `androidx.graphics:graphics-core`.
    *   **Strokes & Ink:** Leverages `androidx.ink` (Google Ink API) for brush descriptors and stroke mathematics.
    *   **Target SDK:** 35 (Android 15).
    *   **Minimum SDK:** 35.

## Architecture & Key Components

The project follows a hybrid architecture combining modern Jetpack Compose UI with high-performance `SurfaceView` based drawing.

### 1. UI Layer (`com.example.zeromark.ui`)
*   **`MainActivity.kt`**: Entry point, handles edge-to-edge display and manages the high-level navigation state between `CanvasSelectionScreen` and `DrawingScreen`.
*   **`DrawingScreen.kt`**: The primary Compose-based screen that hosts the drawing canvas via `AndroidView` interop and manages the toolbars/sidebars.
*   **`DrawingToolbar.kt` / `DrawingSidebar.kt`**: Custom Compose components for tool selection, color picking, and brush size adjustment.

### 2. Drawing Engine (`com.example.zeromark.canvas`)
*   **`FastDrawingView.java`**: A `SurfaceView` implementation that manages low-latency rendering. It uses `CanvasFrontBufferedRenderer` to provide immediate feedback for stylus input while committing strokes to a multi-buffered layer.
*   **`StrokeRenderer.java`**: Handles the actual drawing of stroke segments on the Android `Canvas`.
*   **`StrokeInputHandler.java`**: Processes `MotionEvent` data (focusing on stylus input) and prepares it for the renderer.
*   **`CanvasOverlay.java`**: An overlay view used for drawing temporary UI elements like the brush cursor.

### 3. Data Model (`com.example.zeromark.canvas.model` & `com.example.zeromark.model`)
*   **`CanvasModel.java`**: Manages the collection of strokes and handles tile-based rendering for efficient redraws.
*   **`Stroke.kt`**: A data class representing a single drawing stroke, stored as a flat `FloatArray` of points `[x, y, pressure, ...]` to minimize GC pressure.
*   **`CanvasSettings.kt`**: Configuration for the canvas (e.g., A4 Vertical, A4 Horizontal, Infinite).

### 4. Brushes & Tools (`com.example.zeromark.brushes`)
*   **`ToolManager.java`**: A thread-safe singleton that manages the active tool (Pen, Eraser, Highlighter) and brush selection.
*   **`BrushDescriptor.java`**: Defines brush properties like size, color, opacity, smoothing, and blend modes.

## Development & Build

### Building and Running
*   **Prerequisites:** Android Studio Ladybug or newer, Android 15 (API 35) SDK.
*   **Build Command:** `./gradlew assembleDebug`
*   **Run Command:** `./gradlew installDebug`
*   **Testing:** `./gradlew test` (Unit tests) and `./gradlew connectedAndroidTest` (Instrumented tests).

### Key Conventions
*   **Low Latency:** Always prefer `CanvasFrontBufferedRenderer` for input-related drawing.
*   **Performance:** Use flat arrays (`FloatArray`) for point data instead of lists of objects in the drawing path to avoid GC churn.
*   **Interoperability:** UI should be written in Compose, while the core drawing engine remains in Java for precise control over the graphics pipeline if needed, though Kotlin is also used for data structures.

## Usage
*   The app starts on a `CanvasSelectionScreen`.
*   Users can select a canvas type (e.g., A4) to enter the `DrawingScreen`.
*   The application is optimized for **Stylus** input. Stylus input triggers drawing, while finger input (touch) triggers panning and zooming.
*   Zoom level is displayed temporarily at the top of the screen when changed.

## Specialized Systems

### Eraser System (True Cutout)
The eraser is implemented as a "true cutout" system that reveals the underlying grid lines even during live drawing, rather than using a solid white fallback.

*   **Implementation Detail:**
    *   **Layer Orchestration:** In `FastDrawingView`, the grid is drawn first, followed by a `canvas.saveLayer(null, null)`. Both persistent strokes and the live front-buffer stroke are drawn inside this isolated layer.
    *   **CLEAR Blend Mode:** The eraser uses `PorterDuff.Mode.CLEAR`. Because it operates inside the stroke-only `saveLayer`, it punches through the strokes to reveal the grid below, but does *not* punch through the grid or white background to the window.
    *   **Live Feedback:** To achieve real-time cutout feedback, the eraser bypasses the standard hardware front-buffer overlay (which can't perform complex cutouts) and instead forces a full multi-buffer refresh via `frontRenderer.commit()` for every segment in `wireInputHandler`.
    *   **Thread Safety:** `StrokeRenderer` utilizes `ThreadLocal<Paint>` objects. This ensures that concurrent rendering operations (live preview on the renderer thread vs. tile baking on background threads) do not interfere with each other's paint configurations, preventing color corruption.
*   **Why it works:** This hybrid approach provides the mathematical correctness of software-based `CLEAR` operations with the visual performance of hardware acceleration, resulting in a seamless "what you see is what you erase" experience.
