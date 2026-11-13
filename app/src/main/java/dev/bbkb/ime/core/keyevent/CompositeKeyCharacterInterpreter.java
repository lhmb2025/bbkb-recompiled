package dev.bbkb.ime.core.keyevent;

import android.view.KeyEvent;



public class CompositeKeyCharacterInterpreter implements KeyCharacterInterpreter {

    private final KeyCharacterInterpreter first;

    private final KeyCharacterInterpreter second;

    private final KeyCharacterInterpreter third;

    public CompositeKeyCharacterInterpreter(KeyCharacterInterpreter first, KeyCharacterInterpreter second, KeyCharacterInterpreter third) {
        this.first = first;
        this.second = second;
        this.third = third;
    }

    @Override
    public KeyCharacterResult.Interpretation interpretKeyCharacter(KeyEvent keyEvent, int metaState) {
        KeyCharacterResult.Interpretation interpretation = this.first.interpretKeyCharacter(keyEvent, metaState);
        if (interpretation == null) {
            interpretation = this.second.interpretKeyCharacter(keyEvent, metaState);
        }
        return interpretation == null ? this.third.interpretKeyCharacter(keyEvent, metaState) : interpretation;
    }
}
