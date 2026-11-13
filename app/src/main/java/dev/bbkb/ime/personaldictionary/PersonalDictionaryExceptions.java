package dev.bbkb.ime.personaldictionary;

/** Personal dictionary exceptions: AUD failures, locale issues, key/term conflicts. */
public class PersonalDictionaryExceptions {

    public static class BASLException extends Exception {
        /** @param str the message */
        public BASLException(String str) {
            super(str);
        }

        public BASLException() {
        }
    }

    public static class AudAddException extends BASLException {
        /** @param str the message */
        public AudAddException(String str) {
            super(str);
        }
    }

    public static class AudDeleteException extends BASLException {
        /** @param str the message */
        public AudDeleteException(String str) {
            super(str);
        }
    }

    public static class AudLocaleException extends BASLException {
        /** @param str the message */
        public AudLocaleException(String str) {
            super(str);
        }
    }

    public static class InitialisationIncompleteException extends BASLException {
    }

    public static class KeyAlreadyDefinedException extends BASLException {
        public KeyAlreadyDefinedException() {
        }

        /** @param str the message */

        public KeyAlreadyDefinedException(String str) {
            super(str);
        }
    }

    public static class KeyNotFoundException extends BASLException {
        /** @param str the message */
        public KeyNotFoundException(String str) {
            super(str);
        }

        public KeyNotFoundException() {
        }
    }

    public static class TermAlreadyDefinedException extends BASLException {
        public TermAlreadyDefinedException() {
        }

        /** @param str the message */

        public TermAlreadyDefinedException(String str) {
            super(str);
        }
    }

    public static class TermNotFoundException extends KeyNotFoundException {
    }
}
