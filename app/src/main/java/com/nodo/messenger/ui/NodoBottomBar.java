package com.nodo.messenger.ui;

import android.content.Context;
import android.graphics.Color;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

/** 4-tab navigation: Descubrir / Chats / Llamadas / Perfil. */
public final class NodoBottomBar {
    public interface OnSelect { void select(String key); }
    private static final String[] KEYS={"discover","chats","calls","settings"};
    private static final String[] LABELS={"Descubrir","Chats","Llamadas","Mi perfil"};
    private static final NodoNavIcon.Symbol[] SYMBOLS={
        NodoNavIcon.Symbol.RADAR,NodoNavIcon.Symbol.CHAT,NodoNavIcon.Symbol.CALL,NodoNavIcon.Symbol.PROFILE};
    private NodoBottomBar(){ }
    public static LinearLayout create(Context c,String active,OnSelect onSelect){
        LinearLayout bar=new LinearLayout(c);bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);bar.setBackgroundColor(Color.WHITE);
        int pad=NodoTheme.dp(c,5);bar.setPadding(pad,NodoTheme.dp(c,5),pad,NodoTheme.dp(c,5));
        bar.setElevation(NodoTheme.dp(c,5));
        for(int i=0;i<KEYS.length;i++){
            final String key=KEYS[i];boolean selected=key.equals(active);
            LinearLayout col=new LinearLayout(c);col.setOrientation(LinearLayout.VERTICAL);col.setGravity(Gravity.CENTER);
            col.setBackground(NodoTheme.touch(c,selected?NodoTheme.MINT:Color.WHITE,17));
            NodoNavIcon symbol=new NodoNavIcon(c,SYMBOLS[i],selected?NodoTheme.EMERALD_DARK:NodoTheme.SECONDARY);
            col.addView(symbol,new LinearLayout.LayoutParams(NodoTheme.dp(c,26),NodoTheme.dp(c,27)));
            TextView label=NodoTheme.label(c,LABELS[i],11,selected?NodoTheme.EMERALD_DARK:NodoTheme.SECONDARY,selected);
            label.setGravity(Gravity.CENTER);col.addView(label);
            LinearLayout.LayoutParams item=new LinearLayout.LayoutParams(0,NodoTheme.dp(c,55),1);
            item.setMargins(NodoTheme.dp(c,2),0,NodoTheme.dp(c,2),0);bar.addView(col,item);
            col.setContentDescription(LABELS[i]);col.setOnClickListener(v->onSelect.select(key));
        }
        return bar;
    }
}
