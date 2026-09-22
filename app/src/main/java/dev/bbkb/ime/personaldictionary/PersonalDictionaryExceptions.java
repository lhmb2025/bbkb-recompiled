package dev.bbkb.ime.personaldictionary;

/**
 * Exception hierarchy for Personal Dictionary operations.
 * Includes exceptions for AUD sync failures, locale issues, and key/term conflicts.
 *
 * <p><TextChangeType>Renamed from:</TextChangeType> {@code BASLExceptions.java} (original {@code com.blackberry.basl} package)</p>
 */
public class PersonalDictionaryExceptions {

    public static class BASLException extends Exception {
        public BASLException(String str) {
            super(str);
        }

        public BASLException() {
        }
    }

    public static class AudAddException extends BASLException {
        public AudAddException(String str) {
            super(str);
        }
    }

    public static class AudDeleteException extends BASLException {
        public AudDeleteException(String str) {
            super(str);
        }
    }

    public static class AudLocaleException extends BASLException {
        public AudLocaleException(String str) {
            super(str);
        }
    }

    public static class InitialisationIncompleteException extends BASLException {
    }

    public static class KeyAlreadyDefinedException extends BASLException {
        public KeyAlreadyDefinedException() {
        }

        public KeyAlreadyDefinedException(String str) {
            super(str);
        }
    }

    public static class KeyNotFoundException extends BASLException {
        public KeyNotFoundException(String str) {
            super(str);
        }

        public KeyNotFoundException() {
        }
    }

    public static class TermAlreadyDefinedException extends BASLException {
        public TermAlreadyDefinedException() {
        }

        public TermAlreadyDefinedException(String str) {
            super(str);
        }
    }

    public static class TermNotFoundException extends KeyNotFoundException {
    }
}
