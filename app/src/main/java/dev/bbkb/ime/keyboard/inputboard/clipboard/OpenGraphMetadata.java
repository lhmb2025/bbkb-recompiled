package dev.bbkb.ime.keyboard.inputboard.clipboard;

import android.graphics.Bitmap;

import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.util.Locale;



public final class OpenGraphMetadata {

    private static final String OG_TITLE_SELECTOR = String.format(Locale.getDefault(), "meta[property=og:%s]", "title");

    private static final String OG_IMAGE_SELECTOR = String.format(Locale.getDefault(), "meta[property=og:%s]", "image");

    private String mUrl;

    private String mTitle;

    private String mImageUrl;

    private Bitmap mImage;

    public void parse(Document document) {
        Elements c1646cC;
        if (document == null || (c1646cC = document.select("meta[property^=og:]")) == null) {
            return;
        }
        Element c1662hM11332d = c1646cC.select(OG_TITLE_SELECTOR).first();
        Element c1662hM11332d2 = c1646cC.select(OG_IMAGE_SELECTOR).first();
        if (c1662hM11332d != null) {
            // attr() returns "" when missing; keep the title null so the row shows the URL instead.
            String title = c1662hM11332d.attr("content");
            if (!title.trim().isEmpty()) {
                this.mTitle = title;
            }
        }
        if (c1662hM11332d2 != null) {
            this.mImageUrl = c1662hM11332d2.attr("content");
        }
    }

    public String getTitle() {
        return this.mTitle;
    }

    public String getUrl() {
        return this.mUrl;
    }

    /** @param url the link the preview is for */
    public void setUrl(String url) {
        this.mUrl = url;
    }

    public String getImageUrl() {
        return this.mImageUrl;
    }

    /** @param imageUrl the preview image URL */
    public void setImageUrl(String imageUrl) {
        this.mImageUrl = imageUrl;
    }

    public Bitmap getImage() {
        return this.mImage;
    }

    public void setImage(Bitmap bitmap) {
        this.mImage = bitmap;
    }
}
