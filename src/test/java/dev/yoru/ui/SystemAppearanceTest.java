package dev.yoru.ui;

import java.awt.Color;
import dev.yoru.domain.Model.ThemeId;

/** Every system-accent option remains readable on its light and dark surface. */
public final class SystemAppearanceTest {
    private static double luminance(Color color) {
        double[] c = {color.getRed()/255d,color.getGreen()/255d,color.getBlue()/255d};
        for (int i=0;i<c.length;i++) c[i]=c[i]<=.04045?c[i]/12.92:Math.pow((c[i]+.055)/1.055,2.4);
        return .2126*c[0]+.7152*c[1]+.0722*c[2];
    }
    public static void main(String[] args) {
        for (boolean dark : new boolean[]{false,true}) for (int accent=-1;accent<=6;accent++) {
            var style = new SystemAppearance.Style(dark,accent);
            assert style.theme() == (dark?ThemeId.MIDNIGHT:ThemeId.LINEN);
            double a=luminance(style.colour()), b=luminance(Theme.palette(style.theme()).panel());
            assert (Math.max(a,b)+.05)/(Math.min(a,b)+.05)>=4.5 : "Unreadable system accent " + accent;
        }
        SystemAppearance.choose(false); assert !SystemAppearance.enabled();
        System.out.println("PASS: system light/dark choices and all eight accessible accent families");
    }
}
