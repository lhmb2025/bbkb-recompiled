package dev.bbkb.ime.keyboard.slideboard;



public interface SlideboardComponent {

    
    public interface Listener {
        void onSlideComplete();
    }

    boolean isShowing();

    /** Make the panel visible. */
    void show();

    /** Take the panel off screen. */
    void hide();

    int getSlideBoardWidth();

    void setListener(Listener listener);

    void setXTranslation(float f);
}
