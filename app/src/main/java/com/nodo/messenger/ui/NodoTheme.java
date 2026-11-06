package com.nodo.messenger.ui;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Design system: reusable, consistent visual tokens for every NODO screen. */
public final class NodoTheme {
    public static final int EMERALD=Color.rgb(0,210,106);
    public static final int EMERALD_DARK=Color.rgb(0,135,88);
    public static final int MINT=Color.rgb(225,249,238);
    public static final int PALE_BLUE=Color.rgb(235,248,251);
    public static final int BLUE=Color.rgb(48,169,209);
    public static final int INK=Color.rgb(23,43,49);
    public static final int SECONDARY=Color.rgb(104,120,129);
    public static final int BACKGROUND=Color.rgb(247,250,250);
    public static final int OUTLINE=Color.rgb(230,238,235);
    private NodoTheme() { }
    public static int dp(Context ctx,float units){return Math.round(units*ctx.getResources().getDisplayMetrics().density);}
    public static GradientDrawable rounded(Context ctx,int fill,int radius,int strokeColor){
        GradientDrawable bg=new GradientDrawable();bg.setColor(fill);bg.setCornerRadius(dp(ctx,radius));
        if(strokeColor!=Color.TRANSPARENT)bg.setStroke(dp(ctx,1),strokeColor);
        return bg;
    }
    public static GradientDrawable primary(Context ctx){
        GradientDrawable bg=new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,new int[]{EMERALD_DARK,EMERALD});
        bg.setCornerRadius(dp(ctx,18));return bg;
    }
    public static RippleDrawable touch(Context ctx,int fill,int radius){
        return new RippleDrawable(ColorStateList.valueOf(Color.argb(44,0,160,105)),
            rounded(ctx,fill,radius,Color.TRANSPARENT),null);
    }
    public static TextView label(Context ctx,String title,int size,int color,boolean strong){
        TextView text=new TextView(ctx);text.setText(title);text.setTextSize(size);text.setTextColor(color);
        text.setIncludeFontPadding(false);text.setGravity(Gravity.CENTER_VERTICAL);
        text.setTypeface(Typeface.create(strong?"sans-serif-medium":"sans-serif",Typeface.NORMAL));
        text.setLineSpacing(dp(ctx,2),1.03f);return text;
    }
    public static LinearLayout card(Context ctx){
        LinearLayout view=new LinearLayout(ctx);view.setOrientation(LinearLayout.VERTICAL);
        int p=dp(ctx,16);view.setPadding(p,p,p,p);
        view.setBackground(rounded(ctx,Color.WHITE,22,OUTLINE));view.setElevation(dp(ctx,2));return view;
    }
    public static TextView chip(Context ctx,String title,boolean active){
        TextView t=label(ctx,title,13,active?EMERALD_DARK:SECONDARY,active);
        t.setPadding(dp(ctx,14),dp(ctx,10),dp(ctx,14),dp(ctx,10));t.setGravity(Gravity.CENTER);
        t.setBackground(touch(ctx,active?MINT:Color.rgb(242,246,246),18));return t;
    }
}
