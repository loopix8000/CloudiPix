package app.cloudipix.gallery.core.settings

/**
 * 앱 표시 언어입니다. [tag]는 BCP 47 언어 태그이고, 빈 값은 기기 언어를 따른다는 뜻입니다.
 * [label]은 언어 이름을 해당 언어로 쓴 것이라 번역하지 않습니다.
 *
 * 선택한 항목의 이름을 설정에 저장합니다. 지금 고를 수 없는 언어도 이전 버전에서 저장한
 * 값을 그대로 읽을 수 있도록 남겨 둡니다.
 */
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
    Japanese("ja", "日本語"),
    ;

    companion object {
        /** 설정 화면에서 고를 수 있는 언어와 표시 순서입니다. */
        val options: List<Language> = listOf(System, Korean, English, Japanese, Chinese)
    }
}
