package dev.bbkb.ime.core.shared;

import android.content.res.TypedArray;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;



public final class XmlParseUtils {

    
    public static class ParseException extends XmlPullParserException {
        public ParseException(String msg, XmlPullParser xmlPullParser) {
            super(msg + " at " + xmlPullParser.getPositionDescription());
        }
    }

    
    public static final class IllegalStartTag extends ParseException {
        public IllegalStartTag(XmlPullParser xmlPullParser, String tag, String parent) {
            super("Illegal start tag " + tag + " in " + parent, xmlPullParser);
        }
    }

    
    public static final class IllegalEndTag extends ParseException {
        public IllegalEndTag(XmlPullParser xmlPullParser, String tag, String parent) {
            super("Illegal end tag " + tag + " in " + parent, xmlPullParser);
        }
    }

    
    public static final class IllegalAttribute extends ParseException {
        public IllegalAttribute(XmlPullParser xmlPullParser, String tag, String attribute) {
            super("Tag " + tag + " has illegal attribute " + attribute, xmlPullParser);
        }
    }

    
    public static final class NonEmptyTag extends ParseException {
        public NonEmptyTag(XmlPullParser xmlPullParser, String tag) {
            super(tag + " must be empty tag", xmlPullParser);
        }
    }

    public static void checkEndTag(String tag, XmlPullParser xmlPullParser) throws NonEmptyTag {
        try {
            if (xmlPullParser.next() != 3 || !tag.equals(xmlPullParser.getName())) {
                throw new NonEmptyTag(xmlPullParser, tag);
            }
        } catch (IOException | XmlPullParserException e) {
            // A parse failure counts as a non-empty tag.
            throw new NonEmptyTag(xmlPullParser, tag);
        }
    }

    /**
     * @param attrId attribute index in {@code typedArray}
     * @param attrName attribute name, for the error message
     * @param tag tag name, for the error message
     */
    public static void checkAttributeExists(TypedArray typedArray, int attrId, String attrName, String tag, XmlPullParser xmlPullParser) throws ParseException {
        if (typedArray.hasValue(attrId)) {
            return;
        }
        throw new ParseException("No " + attrName + " attribute found in <" + tag + "/>", xmlPullParser);
    }
}
