package dev.bbkb.ime.core.shared;

import android.content.res.TypedArray;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;



public final class XmlParseUtils {

    
    public static class ParseException extends XmlPullParserException {
        public ParseException(String str, XmlPullParser xmlPullParser) {
            super(str + " at " + xmlPullParser.getPositionDescription());
        }
    }

    
    public static final class IllegalStartTag extends ParseException {
        public IllegalStartTag(XmlPullParser xmlPullParser, String str, String str2) {
            super("Illegal start tag " + str + " in " + str2, xmlPullParser);
        }
    }

    
    public static final class IllegalEndTag extends ParseException {
        public IllegalEndTag(XmlPullParser xmlPullParser, String str, String str2) {
            super("Illegal end tag " + str + " in " + str2, xmlPullParser);
        }
    }

    
    public static final class IllegalAttribute extends ParseException {
        public IllegalAttribute(XmlPullParser xmlPullParser, String str, String str2) {
            super("Tag " + str + " has illegal attribute " + str2, xmlPullParser);
        }
    }

    
    public static final class NonEmptyTag extends ParseException {
        public NonEmptyTag(XmlPullParser xmlPullParser, String str) {
            super(str + " must be empty tag", xmlPullParser);
        }
    }

    public static void checkEndTag(String str, XmlPullParser xmlPullParser) throws NonEmptyTag {
        try {
            if (xmlPullParser.next() != 3 || !str.equals(xmlPullParser.getName())) {
                throw new NonEmptyTag(xmlPullParser, str);
            }
        } catch (IOException | XmlPullParserException e) {
            // XML parsing failed - treat as invalid structure
            throw new NonEmptyTag(xmlPullParser, str);
        }
    }

    public static void checkAttributeExists(TypedArray typedArray, int i, String str, String str2, XmlPullParser xmlPullParser) throws ParseException {
        if (typedArray.hasValue(i)) {
            return;
        }
        throw new ParseException("No " + str + " attribute found in <" + str2 + "/>", xmlPullParser);
    }
}
