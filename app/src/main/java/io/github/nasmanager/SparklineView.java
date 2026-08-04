package io.github.nasmanager;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

import java.util.ArrayDeque;

/** Small two-series history graph used by the live application statistics card. */
final class SparklineView extends View {
    private static final int MAX_SAMPLES = 45;
    private final ArrayDeque<Double> primary = new ArrayDeque<>();
    private final ArrayDeque<Double> secondary = new ArrayDeque<>();
    private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint primaryPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint secondaryPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    SparklineView(Context context, int primaryColor, int secondaryColor, int gridColor) {
        super(context);
        gridPaint.setColor(gridColor);
        gridPaint.setStrokeWidth(dp(1));
        primaryPaint.setColor(primaryColor);
        primaryPaint.setStyle(Paint.Style.STROKE);
        primaryPaint.setStrokeWidth(dp(2));
        primaryPaint.setStrokeCap(Paint.Cap.ROUND);
        primaryPaint.setStrokeJoin(Paint.Join.ROUND);
        secondaryPaint.setColor(secondaryColor);
        secondaryPaint.setStyle(Paint.Style.STROKE);
        secondaryPaint.setStrokeWidth(dp(2));
        secondaryPaint.setStrokeCap(Paint.Cap.ROUND);
        secondaryPaint.setStrokeJoin(Paint.Join.ROUND);
    }

    void addSample(double first, double second) {
        append(primary, Math.max(0, first));
        append(secondary, Math.max(0, second));
        invalidate();
    }

    private static void append(ArrayDeque<Double> values, double value) {
        if (values.size() == MAX_SAMPLES) values.removeFirst();
        values.addLast(value);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float width = getWidth() - getPaddingLeft() - getPaddingRight();
        float height = getHeight() - getPaddingTop() - getPaddingBottom();
        float left = getPaddingLeft();
        float top = getPaddingTop();
        for (int i = 1; i < 4; i++) {
            float y = top + height * i / 4f;
            canvas.drawLine(left, y, left + width, y, gridPaint);
        }
        double maximum = 1;
        for (double value : primary) maximum = Math.max(maximum, value);
        for (double value : secondary) maximum = Math.max(maximum, value);
        drawSeries(canvas, primary, maximum, left, top, width, height, primaryPaint);
        drawSeries(canvas, secondary, maximum, left, top, width, height, secondaryPaint);
    }

    private static void drawSeries(Canvas canvas, ArrayDeque<Double> values, double maximum,
                                   float left, float top, float width, float height, Paint paint) {
        if (values.isEmpty()) return;
        Path path = new Path();
        int index = 0;
        int count = Math.max(2, values.size());
        for (double value : values) {
            float x = left + width * index / (count - 1f);
            float y = top + height - (float) (height * value / maximum);
            if (index == 0) path.moveTo(x, y); else path.lineTo(x, y);
            index++;
        }
        canvas.drawPath(path, paint);
    }

    private float dp(int value) {
        return value * getResources().getDisplayMetrics().density;
    }
}
