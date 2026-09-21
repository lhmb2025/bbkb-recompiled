package dev.bbkb.ime.keyboard.inputboard;



public interface UnifiedInputBoardComponent {
    void onRefresh();

    int getKeyCode();

    void hide();

    void show();

    boolean isShowing();

    boolean isEnabled();
}
