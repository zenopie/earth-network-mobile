package com.example.earthwallet.ui.components;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/**
 * Simple custom pie chart view for governance allocation display
 */
public class PieChartView extends View {
    
    private Paint paint;
    private RectF rectF;
    private List<PieSlice> slices;
    
    public static class PieSlice {
        public String name;
        public float percentage;
        public int color;
        
        public PieSlice(String name, float percentage, int color) {
            this.name = name;
            this.percentage = percentage;
            this.color = color;
        }
    }
    
    public PieChartView(Context context) {
        super(context);
        init();
    }
    
    public PieChartView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }
    
    public PieChartView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }
    
    private void init() {
        paint = new Paint();
        paint.setAntiAlias(true);
        paint.setStyle(Paint.Style.FILL);
        
        rectF = new RectF();
        slices = new ArrayList<>();
    }
    
    public void setData(List<PieSlice> slices) {
        this.slices = slices;
        invalidate(); // Trigger a redraw
    }
    
    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        
        if (slices == null || slices.isEmpty()) {
            return;
        }
        
        float width = getWidth();
        float height = getHeight();
        float radius = Math.min(width, height) * 0.4f; // 40% of the smaller dimension (bigger pie)
        
        float centerX = width / 2;
        float centerY = height / 2;
        
        // Set up the rectangle for drawing arcs
        rectF.set(centerX - radius, centerY - radius, centerX + radius, centerY + radius);
        
        float startAngle = 0f;
        
        // Draw each slice
        for (PieSlice slice : slices) {
            paint.setColor(slice.color);
            
            float sweepAngle = (slice.percentage / 100f) * 360f;
            
            // Draw the pie slice
            canvas.drawArc(rectF, startAngle, sweepAngle, true, paint);
            
            startAngle += sweepAngle;
        }
        
        // Draw center circle for donut effect (optional)
        paint.setColor(0xFFFFFFFF); // White center
        float innerRadius = radius * 0.4f;
        canvas.drawCircle(centerX, centerY, innerRadius, paint);
    }
    
    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        // Make it square
        int size = Math.min(
            MeasureSpec.getSize(widthMeasureSpec),
            MeasureSpec.getSize(heightMeasureSpec)
        );
        
        // Minimum size (bigger minimum)
        size = Math.max(size, 500);
        
        setMeasuredDimension(size, size);
    }
}