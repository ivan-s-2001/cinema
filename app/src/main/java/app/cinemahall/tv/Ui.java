package app.cinemahall.tv;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.*;
import android.view.*;
import android.widget.*;

final class Ui {
    static final int BG=0xff080b10, PANEL=0xff181e27, TEXT=0xfff4f5f7, MUTED=0xffafb5c0, ACCENT=0xffd6f470;
    static int dp(Context c,int n){return Math.round(n*c.getResources().getDisplayMetrics().density);}
    static GradientDrawable shape(Context c,int color,int border,int stroke,int radius){
        GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(c,radius));d.setStroke(dp(c,stroke),border);return d;
    }
    static StateListDrawable background(Context c,boolean primary){
        StateListDrawable d=new StateListDrawable();
        d.addState(new int[]{android.R.attr.state_focused},shape(c,primary?ACCENT:0xff343941,0xffffffff,2,10));
        d.addState(new int[]{android.R.attr.state_pressed},shape(c,0xff5b4028,ACCENT,2,10));
        d.addState(new int[]{},shape(c,primary?ACCENT:PANEL,Color.TRANSPARENT,2,10));return d;
    }
    static TextView text(Context c,String label,int size,int color){
        TextView t=new TextView(c);t.setText(label);t.setTextSize(size);t.setTextColor(color);t.setIncludeFontPadding(false);return t;
    }
    static TextView button(Context c,String label,int icon,boolean primary,Runnable action){
        TextView b=text(c,label,15,primary?BG:TEXT);b.setGravity(Gravity.CENTER_VERTICAL);b.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        b.setMinHeight(dp(c,48));b.setPadding(dp(c,16),dp(c,10),dp(c,16),dp(c,10));b.setFocusable(true);b.setClickable(true);b.setBackground(background(c,primary));
        if(icon!=0){Drawable d=c.getDrawable(icon).mutate();d.setTint(primary?BG:TEXT);d.setBounds(0,0,dp(c,22),dp(c,22));b.setCompoundDrawables(d,null,null,null);b.setCompoundDrawablePadding(dp(c,10));}
        b.setOnClickListener(v->action.run());b.setContentDescription(label);return b;
    }
    static void icon(Context c,TextView b,int icon){
        Drawable d=c.getDrawable(icon).mutate();d.setTint(TEXT);d.setBounds(0,0,dp(c,25),dp(c,25));b.setCompoundDrawables(null,d,null,null);b.setCompoundDrawablePadding(dp(c,6));
    }
}
