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
    public KeyCharacterResult.Interpretation interpretKeyCharacter(KeyEvent keyEvent, int i) {
        KeyCharacterResult.Interpretation interpretation = this.first.interpretKeyCharacter(keyEvent, i);
        if (interpretation == null) {
            interpretation = this.second.interpretKeyCharacter(keyEvent, i);
        }
        return interpretation == null ? this.third.interpretKeyCharacter(keyEvent, i) : interpretation;
    }
}
