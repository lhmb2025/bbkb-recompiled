package dev.bbkb.ime.core.suggestion;

import android.text.TextUtils;

import java.util.Arrays;


public class PrevWordsInfo {

    public static final PrevWordsInfo EMPTY_PREV_WORDS_INFO = new PrevWordsInfo(WordInfo.EMPTY_WORD_INFO);

    public WordInfo[] wordInfos = new WordInfo[2];

    private final String contextBefore;

    
    public static class WordInfo {

        public static final WordInfo EMPTY_WORD_INFO = new WordInfo(null);

        public static final WordInfo BEGINNING_OF_SENTENCE_WORD_INFO = new WordInfo();

        public final CharSequence word;

        public final boolean isBeginningOfSentence;

        public WordInfo() {
            this.word = "";
            this.isBeginningOfSentence = true;
        }

        public WordInfo(CharSequence charSequence) {
            this.word = charSequence;
            this.isBeginningOfSentence = false;
        }

        public boolean isValid() {
            return this.word != null;
        }

        public int hashCode() {
            return Arrays.hashCode(new Object[]{this.word, Boolean.valueOf(this.isBeginningOfSentence)});
        }

        public boolean equals(Object obj) {
            CharSequence charSequence;
            if (this == obj) {
                return true;
            }
            if (!(obj instanceof WordInfo)) {
                return false;
            }
            WordInfo wordInfo = (WordInfo) obj;
            CharSequence charSequence2 = this.word;
            return (charSequence2 == null || (charSequence = wordInfo.word) == null) ? this.word == wordInfo.word && this.isBeginningOfSentence == wordInfo.isBeginningOfSentence : TextUtils.equals(charSequence2, charSequence) && this.isBeginningOfSentence == wordInfo.isBeginningOfSentence;
        }
    }

    public PrevWordsInfo(WordInfo wordInfo) {
        this(wordInfo, "");
    }

    /**
     * UT-33/TI-2: {@code contextBefore} is set once, at construction. It used to be mutable, and
     * {@code RichInputConnection.getPrevWordsInfo} wrote it into the shared
     * {@link #EMPTY_PREV_WORDS_INFO} singleton on every call. The companion {@code contextAfter}
     * field was written from the same place and read by nothing, so it is gone.
     */
    public PrevWordsInfo(WordInfo wordInfo, String contextBefore) {
        this.wordInfos[0] = wordInfo;
        this.contextBefore = contextBefore;
    }

    public String getContextBefore() {
        return this.contextBefore;
    }

    public boolean isValid() {
        return this.wordInfos[0].isValid();
    }

    public int hashCode() {
        return Arrays.hashCode(this.wordInfos);
    }

    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj instanceof PrevWordsInfo) {
            return Arrays.equals(this.wordInfos, ((PrevWordsInfo) obj).wordInfos);
        }
        return false;
    }

    public String toString() {
        StringBuffer stringBuffer = new StringBuffer();
        int i = 0;
        while (true) {
            WordInfo[] wordInfoArr = this.wordInfos;
            if (i < wordInfoArr.length) {
                WordInfo wordInfo = wordInfoArr[i];
                stringBuffer.append("PrevWord[");
                stringBuffer.append(i);
                stringBuffer.append("]: ");
                if (wordInfo == null || !wordInfo.isValid()) {
                    stringBuffer.append("Empty. ");
                } else {
                    stringBuffer.append(wordInfo.word);
                    stringBuffer.append(", isBeginningOfSentence: ");
                    stringBuffer.append(wordInfo.isBeginningOfSentence);
                    stringBuffer.append(". ");
                }
                i++;
            } else {
                return stringBuffer.toString();
            }
        }
    }
}
