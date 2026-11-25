package app.cloudipix.gallery.core.settings

enum class Language(val tag: String, val label: String) {
    System("", "System"),
    English("en", "English"),
    Ukrainian("uk", "Українська"),
    German("de", "Deutsch"),
    Spanish("es", "Español"),
    French("fr", "Français"),
    Portuguese("pt", "Português"),
    Korean("ko", "한국어"),
    Chinese("zh-Hans", "简体中文"),
    Japanese("ja", "日本語");

    companion object {
        val options = listOf(System, Korean, English, Japanese, Chinese)
    }
}
