package dev.bbkb.ime.personaldictionary;

/** Personal dictionary exceptions: AUD failures, locale issues, key/term conflicts. */
public class PersonalDictionaryExceptions {

    public static class BASLException extends Exception {
        /** @param message the message */
        public BASLException(String message) {
            super(message);
        }

        public BASLException() {
        }
    }

    public static class AudAddException extends BASLException {
        /** @param message the message */
        public AudAddException(String message) {
            super(message);
        }
    }

    public static class AudDeleteException extends BASLException {
        /** @param message the message */
        public AudDeleteException(String message) {
            super(message);
        }
    }

    public static class AudLocaleException extends BASLException {
        /** @param message the message */
        public AudLocaleException(String message) {
            super(message);
        }
    }

    public static class InitialisationIncompleteException extends BASLException {
    }

    public static class KeyAlreadyDefinedException extends BASLException {
        public KeyAlreadyDefinedException() {
        }

        /** @param message the message */

        public KeyAlreadyDefinedException(String message) {
            super(message);
        }
    }

    public static class KeyNotFoundException extends BASLException {
        /** @param message the message */
        public KeyNotFoundException(String message) {
            super(message);
        }

        public KeyNotFoundException() {
        }
    }

    public static class TermAlreadyDefinedException extends BASLException {
        public TermAlreadyDefinedException() {
        }

        /** @param message the message */

        public TermAlreadyDefinedException(String message) {
            super(message);
        }
    }

    public static class TermNotFoundException extends KeyNotFoundException {
    }
}
