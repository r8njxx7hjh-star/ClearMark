package com.example.drawingapp;

import android.os.Bundle;
import android.widget.FrameLayout;
import android.widget.ImageButton;

import androidx.appcompat.app.AppCompatActivity;

public class MainActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main_activity);

        FastDrawingView drawingView = new FastDrawingView(this);
        CanvasOverlay cursorOverlay = new CanvasOverlay(this);

        FrameLayout container = findViewById(R.id.drawing_view);
        container.addView(drawingView);
        container.addView(cursorOverlay, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        drawingView.setcanvasOverlay(cursorOverlay);

        ImageButton BUTTON_Brush = findViewById(R.id.BUTTON_BRUSH);
        ImageButton BUTTON_Eraser = findViewById(R.id.BUTTON_ERASER);

        BUTTON_Brush.setOnClickListener(v -> selectTool(drawingView, BUTTON_Brush, BUTTON_Eraser, FastDrawingView.ToolMode.BRUSH));
        BUTTON_Eraser.setOnClickListener(v -> selectTool(drawingView, BUTTON_Brush, BUTTON_Eraser, FastDrawingView.ToolMode.ERASER));

        selectTool(drawingView, BUTTON_Brush, BUTTON_Eraser, FastDrawingView.ToolMode.BRUSH);
    }

    private void selectTool(FastDrawingView drawingView, ImageButton BUTTON_Brush, ImageButton BUTTON_Eraser, FastDrawingView.ToolMode mode) {
        if (drawingView.getToolMode() == mode) {
            drawingView.setToolMode(FastDrawingView.ToolMode.EMPTY);
            BUTTON_Brush.setSelected(false);
            BUTTON_Eraser.setSelected(false);
        } else {
            drawingView.setToolMode(mode);
            BUTTON_Brush.setSelected(mode == FastDrawingView.ToolMode.BRUSH);
            BUTTON_Eraser.setSelected(mode == FastDrawingView.ToolMode.ERASER);
        }
    }
}