package com.example.drawingapp;

import android.os.Bundle;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageButton;

import androidx.appcompat.app.AppCompatActivity;

public class MainActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main_activity);

        FastDrawingView drawingView = new FastDrawingView(this);
        CanvasOverlay visualOverlay = new CanvasOverlay(this);

        FrameLayout container = findViewById(R.id.drawing_view);
        container.addView(drawingView);
        container.addView(visualOverlay, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        drawingView.setCanvasOverlay(visualOverlay);
        drawingView.requestFocus();

        ImageButton buttonBrush  = findViewById(R.id.BUTTON_BRUSH);
        ImageButton buttonEraser = findViewById(R.id.BUTTON_ERASER);

        buttonBrush.setOnClickListener(v  -> selectTool(ToolManager.ToolType.PEN,    buttonBrush, buttonEraser));
        buttonEraser.setOnClickListener(v -> selectTool(ToolManager.ToolType.ERASER, buttonBrush, buttonEraser));

        selectTool(ToolManager.ToolType.PEN, buttonBrush, buttonEraser);
    }

    private void selectTool(ToolManager.ToolType tool, ImageButton buttonBrush, ImageButton buttonEraser) {
        ToolManager.getInstance().setCurrentTool(tool, 0);
        buttonBrush.setSelected(tool  == ToolManager.ToolType.PEN);
        buttonEraser.setSelected(tool == ToolManager.ToolType.ERASER);
    }


}