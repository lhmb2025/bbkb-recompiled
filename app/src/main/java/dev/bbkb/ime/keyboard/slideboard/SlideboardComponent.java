package dev.bbkb.ime.keyboard.slideboard;



public interface SlideboardComponent {

    
    public interface Listener {
        void onSlideComplete();
    }

    boolean isShowing();

    void show();

    void hide();

    int getSlideBoardWidth();

    void setListener(Listener listener);

    /** @param translationX the panel's x translation */
    void setXTranslation(float translationX);
}
