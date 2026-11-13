package dev.bbkb.ime.core.ime;



public interface CursorMovementListener {
    void moveRight(int count);

    void moveLeft(int count);

    void moveUp(int count);

    void moveDown(int count);
}
