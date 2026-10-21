package android.widget;

import android.view.View;
public class TextView extends View {
    private CharSequence text = "";
    private int maxLines;
    public void setText(CharSequence t) { text = t; }
    public CharSequence getText() { return text; }
    public void setMaxLines(int n) { maxLines = n; }
    public int getMaxLines() { return maxLines; }
    public int getVisibility() { return 0; }
    public void setVisibility(int v) { }
    public android.content.Context getContext() { return new android.content.Context(); }
}
