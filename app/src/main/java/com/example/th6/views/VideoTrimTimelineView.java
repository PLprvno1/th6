package com.example.th6.views;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.MotionEvent;
import android.view.View;
import java.util.ArrayList;
import java.util.List;

/** Thumbnail-backed trim timeline with start/end handles and a draggable playhead. */
public class VideoTrimTimelineView extends View {
    public interface Listener { void onRangeChanged(long startMs, long endMs); void onPlayheadChanged(long positionMs); }
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final List<Drawable> frames = new ArrayList<>();
    private long durationMs = 1, startMs, endMs = 1, playheadMs;
    private float left, right, top, bottom;
    private int active;
    private Listener listener;
    private final float density;
    private int surfaceColor, primaryColor, onSurfaceColor, shadeColor;
    public VideoTrimTimelineView(Context c, AttributeSet a) { super(c,a); density=getResources().getDisplayMetrics().density; setMinimumHeight((int)(76*density)); }
    public void setListener(Listener l) { listener=l; }
    public void setDuration(long d) { durationMs=Math.max(1,d); startMs=0; endMs=durationMs; invalidate(); }
    public void setRange(long s,long e) { startMs=Math.max(0,Math.min(s,durationMs)); endMs=Math.max(startMs,Math.min(e,durationMs)); invalidate(); }
    public void setPlayhead(long p) { playheadMs=Math.max(0,Math.min(p,durationMs)); invalidate(); }
    public void setFrames(List<Drawable> values) { frames.clear(); if(values!=null)frames.addAll(values); invalidate(); }
    private int resolveColor(int attr, int fallback) { TypedValue value=new TypedValue(); return getContext().getTheme().resolveAttribute(attr,value,true) ? (value.resourceId!=0 ? getResources().getColor(value.resourceId,getContext().getTheme()) : value.data) : fallback; }
    @Override protected void onDraw(Canvas c) { super.onDraw(c); surfaceColor=resolveColor(com.google.android.material.R.attr.colorSurface,Color.rgb(48,38,42)); primaryColor=resolveColor(androidx.appcompat.R.attr.colorPrimary,Color.rgb(221,157,119)); onSurfaceColor=resolveColor(com.google.android.material.R.attr.colorOnSurface,Color.WHITE); shadeColor=resolveColor(com.google.android.material.R.attr.colorOnSurface,Color.BLACK); float pad=14*density; left=pad; right=getWidth()-pad; top=10*density; bottom=getHeight()-10*density; float w=right-left;
      paint.setColor(surfaceColor); c.drawRoundRect(left,top,right,bottom,8*density,8*density,paint);
      if(!frames.isEmpty()){ float fw=w/frames.size(); for(int i=0;i<frames.size();i++){ Drawable d=frames.get(i); if(d!=null){int x=(int)(left+i*fw); d.setBounds(x,(int)top,(int)Math.ceil(x+fw+1),(int)bottom); d.draw(c);} } }
      float sx=x(startMs), ex=x(endMs), px=x(playheadMs);
      paint.setColor((shadeColor & 0x00ffffff) | 0x99000000); c.drawRect(left,top,sx,bottom,paint); c.drawRect(ex,top,right,bottom,paint);
      paint.setColor(primaryColor); paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(3*density); c.drawRoundRect(new RectF(sx,top,ex,bottom),6*density,6*density,paint); paint.setStyle(Paint.Style.FILL);
      paint.setColor(onSurfaceColor); c.drawRoundRect(sx-5*density,top-3*density,sx+7*density,bottom+3*density,4*density,4*density,paint); c.drawRoundRect(ex-7*density,top-3*density,ex+5*density,bottom+3*density,4*density,4*density,paint);
      paint.setColor(primaryColor); paint.setStrokeWidth(2*density); c.drawLine(px,top-5*density,px,bottom+5*density,paint);
    }
    private float x(long ms){return left+(right-left)*Math.max(0,Math.min(1,ms/(float)durationMs));}
    private long time(float x){return Math.round(Math.max(0,Math.min(1,(x-left)/(right-left)))*durationMs);}
    @Override public boolean onTouchEvent(MotionEvent e){float x=e.getX(); if(e.getAction()==MotionEvent.ACTION_DOWN){float sx=x(startMs),ex=x(endMs),px=x(playheadMs); if(Math.abs(x-sx)<28*density)active=1; else if(Math.abs(x-ex)<28*density)active=2; else if(Math.abs(x-px)<24*density)active=3; else active=3; getParent().requestDisallowInterceptTouchEvent(true); move(x); return true;} if(e.getAction()==MotionEvent.ACTION_MOVE){move(x);return true;} if(e.getAction()==MotionEvent.ACTION_UP||e.getAction()==MotionEvent.ACTION_CANCEL){active=0; performClick();return true;} return true; }
    private void move(float x){long p=time(x); if(active==1)startMs=Math.min(p,Math.max(0,endMs-500)); else if(active==2)endMs=Math.max(p,Math.min(durationMs,startMs+500)); else playheadMs=p; invalidate(); if(listener!=null){if(active==3)listener.onPlayheadChanged(playheadMs);else listener.onRangeChanged(startMs,endMs);} }
    @Override public boolean performClick(){super.performClick();return true;}
}
