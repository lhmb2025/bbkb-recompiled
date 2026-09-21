package com.blackberry.nuanceshim.languagepack;

/**
 * LanguagePackInfo - Metadata for a single NuanceSDK language pack
 * Contains language/country codes, name, version, SHA-256 hash, file path, and installation status.
 * Used by LanguagePackRegistry to track available and installed language packs.
 */

class LanguagePackInfo {

    String language = null;

    String country = null;

    String name = null;

    private int preinstalledFlag = -1;

    String path = null;

    double version = 0.0d;

    boolean isDefault = false;

    LanguagePackInfo() {
    }

    void setPreinstalledFlag(boolean z) {
        this.preinstalledFlag = z ? 1 : 0;
    }

    boolean isPreinstalled() {
        int i = this.preinstalledFlag;
        if (i == 1) {
            return true;
        }
        if (i == 0) {
            return false;
        }
        throw new IllegalStateException("Preinstalled attribute not set");
    }

    boolean isValid() {
        return this.language != null && this.name != null && this.preinstalledFlag != -1 && this.path != null && this.version > 0.0d;
    }

    public String toString() {
        // Reads the field, not isPreinstalled(): that throws when the manifest entry omitted
        // the key, and ManifestParser populates this object field by field and only validates
        // at the end -- so logging a mid-parse or rejected entry used to crash. isValid()
        // already treats the unset flag as a normal "incomplete" value.
        final String preinstalled =
                this.preinstalledFlag == -1 ? "unset" : String.valueOf(this.preinstalledFlag == 1);
        return "language=" + this.language + " country=" + this.country + " name=" + this.name
                + " preinstalled=" + preinstalled + " path=" + this.path
                + " version=" + this.version + " isDefault=" + this.isDefault;
    }
}
