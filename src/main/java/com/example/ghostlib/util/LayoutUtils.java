package com.example.ghostlib.util;

import com.lowdragmc.lowdraglib2.gui.ui.style.LayoutStyle;

public class LayoutUtils {
    public static void apply(LayoutStyle layout, float left, float top, float width, float height) {
        layout.left(left);
        layout.top(top);
        layout.width(width);
        layout.height(height);
    }
    
    public static void margin(LayoutStyle layout, float top, float bottom, float left, float right) {
        layout.marginTop(top);
        layout.marginBottom(bottom);
        layout.marginLeft(left);
        layout.marginRight(right);
    }
}