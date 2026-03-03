package com.example.drawingapp.experiments;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.MotionEvent;
import android.view.View;

public class drawingTest extends View {

    private Paint circlePaint;
    private float touchX, touchY;
    private boolean touching = false;

    public drawingTest(Context context) {
        super(context);

        circlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        circlePaint.setStyle(Paint.Style.FILL);
        circlePaint.setColor(Color.BLACK);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        canvas.drawColor(Color.WHITE);

        if (touching) {
            canvas.drawCircle(touchX, touchY, 30, circlePaint);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {

        switch (event.getAction()) {

            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_MOVE:

                // Use history samples to reduce perceived lag
                int historySize = event.getHistorySize();
                if (historySize > 0) {
                    touchX = event.getHistoricalX(historySize - 1);
                    touchY = event.getHistoricalY(historySize - 1);
                }

                touchX = event.getX();
                touchY = event.getY();

                touching = true;

                postInvalidateOnAnimation(); // sync with 120hz
                return true;

            case MotionEvent.ACTION_UP:
                touching = false;
                postInvalidateOnAnimation();
                return true;
        }

        return true;
    }
}
