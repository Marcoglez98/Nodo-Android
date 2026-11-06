package com.nodo.messenger.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

/** Vector-style icons drawn without font-dependent glyphs or external libraries. */
public final class NodoNavIcon extends View {
    public enum Symbol { RADAR, CHAT, CALL, PROFILE, FILE }
    private final Symbol symbol;
    private final Paint pen=new Paint(Paint.ANTI_ALIAS_FLAG);
    private int foreground;
    public NodoNavIcon(Context context,Symbol symbol,int color){
        super(context);this.symbol=symbol;this.foreground=color;setLayerType(View.LAYER_TYPE_SOFTWARE,null);
        setContentDescription(symbol.name());
    }
    public void setForegroundColor(int color){foreground=color;invalidate();}
    private void stroke(float weight){pen.setColor(foreground);pen.setStyle(Paint.Style.STROKE);
        pen.setStrokeWidth(weight);pen.setStrokeCap(Paint.Cap.ROUND);pen.setStrokeJoin(Paint.Join.ROUND);}
    @Override protected void onDraw(Canvas canvas){
        super.onDraw(canvas);float scale=Math.min(getWidth(),getHeight())/30f;
        canvas.save();canvas.translate((getWidth()-30f*scale)/2,(getHeight()-30f*scale)/2);canvas.scale(scale,scale);
        stroke(1.9f);
        switch(symbol){
            case RADAR:
                canvas.drawCircle(15,15,10,pen);canvas.drawCircle(15,15,5.4f,pen);
                pen.setStyle(Paint.Style.FILL);canvas.drawCircle(15,15,2.4f,pen);break;
            case CHAT:
                Path bubble=new Path();bubble.moveTo(6,7);bubble.quadTo(6,4,10,4);
                bubble.lineTo(22,4);bubble.quadTo(26,4,26,9);bubble.lineTo(26,19);
                bubble.quadTo(26,23,21,23);bubble.lineTo(12,23);bubble.lineTo(6,27);
                bubble.lineTo(6,19);bubble.close();canvas.drawPath(bubble,pen);
                canvas.drawLine(11,11,21,11,pen);canvas.drawLine(11,16,19,16,pen);break;
            case CALL:
                Path call=new Path();call.moveTo(9,4);call.lineTo(6,6);call.quadTo(3,9,7,16);
                call.quadTo(12,25,20,27);call.quadTo(23,27,26,23);call.lineTo(24,20);
                call.lineTo(19,18);call.lineTo(16,21);call.quadTo(11,18,9,13);
                call.lineTo(12,10);call.close();canvas.drawPath(call,pen);break;
            case PROFILE:
                canvas.drawCircle(15,10,5,pen);canvas.drawArc(5,18,25,28,188,164,false,pen);break;
            case FILE:
                canvas.drawRoundRect(7,4,23,26,2,2,pen);canvas.drawLine(11,11,19,11,pen);
                canvas.drawLine(11,16,19,16,pen);canvas.drawLine(11,21,16,21,pen);break;
        }
        canvas.restore();
    }
}
