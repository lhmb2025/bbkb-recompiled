package com.blackberry.nuanceshim.languagepack;

/** Metadata for one language pack manifest entry. */

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

    /** @param preinstalled true if the pack ships in the APK assets */
    void setPreinstalledFlag(boolean preinstalled) {
        this.preinstalledFlag = preinstalled ? 1 : 0;
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
        // Not isPreinstalled(): it throws when unset, and rejected entries get logged.
        final String preinstalled =
                this.preinstalledFlag == -1 ? "unset" : String.valueOf(this.preinstalledFlag == 1);
        return "language=" + this.language + " country=" + this.country + " name=" + this.name
                + " preinstalled=" + preinstalled + " path=" + this.path
                + " version=" + this.version + " isDefault=" + this.isDefault;
    }
}
